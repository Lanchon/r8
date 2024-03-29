#!/usr/bin/env python
# Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
# for details. All rights reserved. Use of this source code is governed by a
# BSD-style license that can be found in the LICENSE file.

import argparse
import datetime
import os.path
import re
import shutil
import subprocess
import sys
import urllib

import update_prebuilds_in_android
import utils

R8_DEV_BRANCH = '2.0'
R8_VERSION_FILE = os.path.join(
    'src', 'main', 'java', 'com', 'android', 'tools', 'r8', 'Version.java')
THIS_FILE_RELATIVE = os.path.join('tools', 'r8_release.py')


def prepare_release(args):
  if args.version:
    print "Cannot manually specify version when making a dev release."
    sys.exit(1)

  def make_release(args):
    commithash = args.dev_release

    with utils.TempDir() as temp:
      subprocess.check_call(['git', 'clone', utils.REPO_SOURCE, temp])
      with utils.ChangedWorkingDirectory(temp):
        subprocess.check_call([
          'git',
          'new-branch',
          '--upstream',
          'origin/%s' % R8_DEV_BRANCH,
          'dev-release'])

        # Compute the current and new version on the branch.
        result = None
        for line in open(R8_VERSION_FILE, 'r'):
          result = re.match(
              r'.*LABEL = "%s\.(\d+)\-dev";' % R8_DEV_BRANCH, line)
          if result:
            break
        if not result or not result.group(1):
          print 'Failed to find version label matching %s(\d+)-dev'\
                % R8_DEV_BRANCH
          sys.exit(1)
        try:
          patch_version = int(result.group(1))
        except ValueError:
          print 'Failed to convert version to integer: %s' % result.group(1)

        old_version = '%s.%s-dev' % (R8_DEV_BRANCH, patch_version)
        version = '%s.%s-dev' % (R8_DEV_BRANCH, patch_version + 1)

        # Verify that the merge point from master is not empty.
        merge_diff_output = subprocess.check_output([
          'git', 'diff', 'HEAD..%s' % commithash])
        other_diff = version_change_diff(
            merge_diff_output, old_version, "master")
        if not other_diff:
          print 'Merge point from master (%s)' % commithash, \
            'is the same as exiting release (%s).' % old_version
          sys.exit(1)

        # Merge the desired commit from master on to the branch.
        subprocess.check_call([
          'git', 'merge', '--no-ff', '--no-edit', commithash])

        # Rewrite the version, commit and validate.
        sed(old_version, version, R8_VERSION_FILE)

        subprocess.check_call([
          'git', 'commit', '-a', '-m', 'Version %s' % version])

        version_diff_output = subprocess.check_output([
          'git', 'diff', '%s..HEAD' % commithash])

        validate_version_change_diff(version_diff_output, "master", version)

        # Double check that we want to push the release.
        if not args.dry_run:
          input = raw_input('Publish dev release version %s [y/N]:' % version)
          if input != 'y':
            print 'Aborting dev release for %s' % version
            sys.exit(1)

        maybe_check_call(args, [
          'git', 'push', 'origin', 'HEAD:%s' % R8_DEV_BRANCH])
        maybe_tag(args, version)

        return "%s dev version %s from hash %s" % (
          'DryRun: omitted publish of' if args.dry_run else 'Published',
          version,
          commithash)

  return make_release


def maybe_tag(args, version):
  maybe_check_call(args, [
    'git', 'tag', '-a', version, '-m', '"%s"' % version])
  maybe_check_call(args, [
    'git', 'push', 'origin', 'refs/tags/%s' % version])

def version_change_diff(diff, old_version, new_version):
  invalid_line = None
  for line in diff.splitlines():
    if line.startswith('-  ') and \
        line != '-  public static final String LABEL = "%s";' % old_version:
      invalid_line = line
    elif line.startswith('+  ') and \
        line != '+  public static final String LABEL = "%s";' % new_version:
      invalid_line = line
  return invalid_line


def validate_version_change_diff(version_diff_output, old_version, new_version):
  invalid = version_change_diff(version_diff_output, old_version, new_version)
  if invalid:
    print "Unexpected diff:"
    print "=" * 80
    print version_diff_output
    print "=" * 80
    accept_string = 'THE DIFF IS OK!'
    input = raw_input(
      "Accept the additonal diff as part of the release? "
      "Type '%s' to accept: " % accept_string)
    if input != accept_string:
      print "You did not type '%s'" % accept_string
      print 'Aborting dev release for %s' % version
      sys.exit(1)


def maybe_check_call(args, cmd):
  if args.dry_run:
    print 'DryRun:', ' '.join(cmd)
  else:
    print ' '.join(cmd)
    return subprocess.check_call(cmd)


def update_prebuilds(version, checkout):
  update_prebuilds_in_android.main_download('', True, 'lib', checkout, version)


def release_studio_or_aosp(path, options, git_message):
  with utils.ChangedWorkingDirectory(path):
    subprocess.call(['repo', 'abandon', 'update-r8'])
    if not options.no_sync:
      subprocess.check_call(['repo', 'sync', '-cq', '-j', '16'])

    prebuilts_r8 = os.path.join(path, 'prebuilts', 'r8')

    with utils.ChangedWorkingDirectory(prebuilts_r8):
      subprocess.check_call(['repo', 'start', 'update-r8'])

    update_prebuilds(options.version, path)

    with utils.ChangedWorkingDirectory(prebuilts_r8):
      subprocess.check_call(['git', 'commit', '-a', '-m', git_message])
      process = subprocess.Popen(['repo', 'upload', '.', '--verify'],
                                 stdin=subprocess.PIPE)
      return process.communicate(input='y\n')[0]


def prepare_aosp(args):
  assert args.version
  assert os.path.exists(args.aosp), "Could not find AOSP path %s" % args.aosp

  def release_aosp(options):
    print "Releasing for AOSP"
    if options.dry_run:
      return 'DryRun: omitting AOSP release for %s' % options.version

    git_message = ("""Update D8 and R8 to %s

Version: master %s
This build IS NOT suitable for preview or public release.

Built here: go/r8-releases/raw/%s

Test: TARGET_PRODUCT=aosp_arm64 m -j core-oj"""
                   % (args.version, args.version, args.version))
    return release_studio_or_aosp(args.aosp, options, git_message)

  return release_aosp


def git_message_dev(version):
  return """Update D8 R8 master to %s

This is a development snapshot, it's fine to use for studio canary build, but
not for BETA or release, for those we would need a release version of R8
binaries.
This build IS suitable for preview release but IS NOT suitable for public release.

Built here: go/r8-releases/raw/%s
Test: ./gradlew check
Bug: """ % (version, version)


def git_message_release(version, bugs):
  return """D8 R8 version %s

Built here: go/r8-releases/raw/%s/
Test: ./gradlew check
Bug: %s """ % (version, version, '\nBug: '.join(bugs))


def prepare_studio(args):
  assert args.version
  assert os.path.exists(args.studio), ("Could not find STUDIO path %s"
                                       % args.studio)

  def release_studio(options):
    print "Releasing for STUDIO"
    if options.dry_run:
      return 'DryRun: omitting studio release for %s' % options.version

    git_message = (git_message_dev(options.version)
                   if 'dev' in options.version
                   else git_message_release(options.version, options.bug))
    return release_studio_or_aosp(args.studio, options, git_message)

  return release_studio


def g4_cp(old, new, file):
  subprocess.check_call('g4 cp {%s,%s}/%s' % (old, new, file), shell=True)


def g4_open(file):
  subprocess.check_call('g4 open %s' % file, shell=True)


def g4_add(files):
  subprocess.check_call(' '.join(['g4', 'add'] + files), shell=True)


def g4_change(version, r8version):
  return subprocess.check_output(
      'g4 change --desc "Update R8 to version %s %s\n\n'
      'IGNORE_COMPLIANCELINT=D8 and R8 are built externally to produce a fully '
      'tested R8lib"' % (version, r8version),
      shell=True)


def sed(pattern, replace, path):
  with open(path, "r") as sources:
    lines = sources.readlines()
  with open(path, "w") as sources:
    for line in lines:
      sources.write(re.sub(pattern, replace, line))


def download_file(version, file, dst):
  urllib.urlretrieve(
      ('http://storage.googleapis.com/r8-releases/raw/%s/%s' % (version, file)),
      dst)


def blaze_run(target):
  return subprocess.check_output(
      'blaze run %s' % target, shell=True, stderr=subprocess.STDOUT)


def prepare_google3(args):
  assert args.version
  # Check if an existing client exists.
  if ':update-r8:' in subprocess.check_output('g4 myclients', shell=True):
    print "Remove the existing 'update-r8' client before continuing."
    sys.exit(1)

  def release_google3(options):
    print "Releasing for Google 3"
    if options.dry_run:
      return 'DryRun: omitting g3 release for %s' % options.version

    google3_base = subprocess.check_output(
        ['p4', 'g4d', '-f', 'update-r8']).rstrip()
    third_party_r8 = os.path.join(google3_base, 'third_party', 'java', 'r8')

    # Check if new version folder is already created
    today = datetime.date.today()
    new_version='v%d%02d%02d' % (today.year, today.month, today.day)
    new_version_path = os.path.join(third_party_r8, new_version)

    if os.path.exists(new_version_path):
      shutil.rmtree(new_version_path)

    # Remove old version
    old_versions = []
    for name in os.listdir(third_party_r8):
      if os.path.isdir(os.path.join(third_party_r8, name)):
        old_versions.append(name)
    old_versions.sort()

    if len(old_versions) >= 2:
      shutil.rmtree(os.path.join(third_party_r8, old_versions[0]))

    # Take current version to copy from
    old_version=old_versions[-1]

    # Create new version
    assert not os.path.exists(new_version_path)
    os.mkdir(new_version_path)

    with utils.ChangedWorkingDirectory(third_party_r8):
      g4_cp(old_version, new_version, 'BUILD')
      g4_cp(old_version, new_version, 'LICENSE')
      g4_cp(old_version, new_version, 'METADATA')

      with utils.ChangedWorkingDirectory(new_version_path):
        # update METADATA
        g4_open('METADATA')
        sed(r'[1-9]\.[0-9]{1,2}\.[0-9]{1,3}-dev',
            options.version,
            os.path.join(new_version_path, 'METADATA'))
        sed(r'\{ year.*\}',
            ('{ year: %i month: %i day: %i }'
             % (today.year, today.month, today.day))
            , os.path.join(new_version_path, 'METADATA'))

        # update BUILD (is not necessary from v20190923)
        g4_open('BUILD')
        sed(old_version, new_version, os.path.join(new_version_path, 'BUILD'))

        # download files
        download_file(options.version, 'r8-full-exclude-deps.jar', 'r8.jar')
        download_file(options.version, 'r8-src.jar', 'r8-src.jar')
        download_file(options.version, 'r8lib-exclude-deps.jar', 'r8lib.jar')
        download_file(
            options.version, 'r8lib-exclude-deps.jar.map', 'r8lib.jar.map')
        g4_add(['r8.jar', 'r8-src.jar', 'r8lib.jar', 'r8lib.jar.map'])

      subprocess.check_output('chmod u+w %s/*' % new_version, shell=True)

      g4_open('BUILD')
      sed(old_version, new_version, os.path.join(third_party_r8, 'BUILD'))

    with utils.ChangedWorkingDirectory(google3_base):
      blaze_result = blaze_run('//third_party/java/r8:d8 -- --version')

      assert options.version in blaze_result

      return g4_change(new_version, options.version)

  return release_google3


def branch_change_diff(diff, old_version, new_version):
  invalid_line = None
  for line in diff.splitlines():
    if line.startswith('-R8') and \
        line != "-R8_DEV_BRANCH = '%s'" % old_version:
      print line
      invalid_line = line
    elif line.startswith('+R8') and \
        line != "+R8_DEV_BRANCH = '%s'" % new_version:
      print line
      invalid_line = line
  return invalid_line


def validate_branch_change_diff(version_diff_output, old_version, new_version):
  invalid = branch_change_diff(version_diff_output, old_version, new_version)
  if invalid:
    print
    print "The diff for the branch change in tools/release.py is not as expected:"
    print
    print "=" * 80
    print version_diff_output
    print "=" * 80
    print
    print "Validate the uploaded CL before landing."
    print


def prepare_branch(args):
  branch_version = args.new_dev_branch[0]
  commithash = args.new_dev_branch[1]

  current_semver = utils.check_basic_semver_version(
    R8_DEV_BRANCH, ", current release branch version should be x.y", 2)
  semver = utils.check_basic_semver_version(
    branch_version, ", release branch version should be x.y", 2)
  if not semver.larger_than(current_semver):
    print ('New branch version "'
      + branch_version
      + '" must be strictly larger than the current "'
      + R8_DEV_BRANCH
      + '"')
    sys.exit(1)

  def make_branch(options):
    with utils.TempDir() as temp:
      subprocess.check_call(['git', 'clone', utils.REPO_SOURCE, temp])
      with utils.ChangedWorkingDirectory(temp):
        subprocess.check_call(['git', 'branch', branch_version, commithash])

        subprocess.check_call(['git', 'checkout', branch_version])

        # Rewrite the version, commit and validate.
        old_version = 'master'
        full_version = branch_version + '.0-dev'
        version_prefix = 'LABEL = "'
        sed(version_prefix + old_version,
          version_prefix + full_version,
          R8_VERSION_FILE)

        subprocess.check_call([
          'git', 'commit', '-a', '-m', 'Version %s' % full_version])

        version_diff_output = subprocess.check_output([
          'git', 'diff', '%s..HEAD' % commithash])

        validate_version_change_diff(version_diff_output, old_version, full_version)

        # Double check that we want to create a new release branch.
        if not options.dry_run:
          input = raw_input('Create new branch for %s [y/N]:' % branch_version)
          if input != 'y':
            print 'Aborting new branch for %s' % branch_version
            sys.exit(1)

        maybe_check_call(options, [
          'git', 'push', 'origin', 'HEAD:%s' % branch_version])
        maybe_tag(options, full_version)

        print ('Updating tools/r8_release.py to make new dev releases on %s'
          % branch_version)

        subprocess.check_call(['git', 'new-branch', 'update-release-script'])

        # Check this file for the setting of the current dev branch.
        result = None
        for line in open(THIS_FILE_RELATIVE, 'r'):
          result = re.match(
              r"^R8_DEV_BRANCH = '(\d+).(\d+)'", line)
          if result:
            break
        if not result or not result.group(1):
          print 'Failed to find version label in %s' % THIS_FILE_RELATIVE
          sys.exit(1)

        # Update this file with the new dev branch.
        sed("R8_DEV_BRANCH = '%s.%s" % (result.group(1), result.group(2)),
          "R8_DEV_BRANCH = '%s.%s" % (str(semver.major), str(semver.minor)),
          THIS_FILE_RELATIVE)

        message = \
            'Prepare %s for branch %s' % (THIS_FILE_RELATIVE, branch_version)
        subprocess.check_call(['git', 'commit', '-a', '-m', message])

        branch_diff_output = subprocess.check_output(['git', 'diff', 'HEAD~'])

        validate_branch_change_diff(
          branch_diff_output, R8_DEV_BRANCH, branch_version)

        maybe_check_call(options, ['git', 'cl', 'upload', '-f', '-m', message])

        print
        print 'Make sure to send out the branch change CL for review.'
        print

  return make_branch


def parse_options():
  result = argparse.ArgumentParser(description='Release r8')
  group = result.add_mutually_exclusive_group()
  group.add_argument('--dev-release',
                      metavar=('<master hash>'),
                      help='The hash to use for the new dev version of R8')
  group.add_argument('--version',
                      metavar=('<version>'),
                      help='The new version of R8 (e.g., 1.4.51) to release to selected channels')
  group.add_argument('--new-dev-branch',
                      nargs=2,
                      metavar=('<version>', '<master hash>'),
                      help='Create a new branch starting a version line (e.g. 2.0)')
  result.add_argument('--no-sync', '--no_sync',
                      default=False,
                      action='store_true',
                      help='Do not sync repos before uploading')
  result.add_argument('--bug',
                      metavar=('<bug(s)>'),
                      default=[],
                      action='append',
                      help='List of bugs for release version')
  result.add_argument('--studio',
                      metavar=('<path>'),
                      help='Release for studio by setting the path to a studio '
                           'checkout')
  result.add_argument('--aosp',
                      metavar=('<path>'),
                      help='Release for aosp by setting the path to the '
                           'checkout')
  result.add_argument('--google3',
                      default=False,
                      action='store_true',
                      help='Release for google 3')
  result.add_argument('--dry-run',
                      default=False,
                      action='store_true',
                      help='Only perform non-commiting tasks and print others.')
  args = result.parse_args()
  if args.version and not 'dev' in args.version and args.bug == []:
    print "When releasing a release version add the list of bugs by using '--bug'"
    sys.exit(1)

  if args.version and not 'dev' in args.version and args.google3:
    print "You should not roll a release version into google 3"
    sys.exit(1)

  return args


def main():
  args = parse_options()
  targets_to_run = []

  if args.new_dev_branch:
    if args.google3 or args.studio or args.aosp:
      print 'Cannot create a branch and roll at the same time.'
      sys.exit(1)
    targets_to_run.append(prepare_branch(args))

  if args.dev_release:
    if args.google3 or args.studio or args.aosp:
      print 'Cannot create a dev release and roll at the same time.'
      sys.exit(1)
    targets_to_run.append(prepare_release(args))

  if args.google3 or args.studio:
    utils.check_prodacces()

  if args.google3:
    targets_to_run.append(prepare_google3(args))
  if args.studio:
    targets_to_run.append(prepare_studio(args))
  if args.aosp:
    targets_to_run.append(prepare_aosp(args))

  final_results = []
  for target_closure in targets_to_run:
    final_results.append(target_closure(args))

  print '\n\n**************************************************************'
  print 'PRINTING SUMMARY'
  print '**************************************************************\n\n'

  for result in final_results:
    if result is not None:
      print result


if __name__ == '__main__':
  sys.exit(main())

