#!/usr/bin/env python
# Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
# for details. All rights reserved. Use of this source code is governed by a
# BSD-style license that can be found in the LICENSE file.

# Run all internal tests, archive result to cloud storage.
# In the continuous operation flow we have a tester continuously checking
# a specific cloud storage location for a file with a git hash.
# If the file is there, the tester will remove the file, and add another
# file stating that this is now being run. After successfully running,
# the tester will add yet another file, and remove the last one.
# Complete flow with states:
# 1:
#   BOT:
#     Add file READY_FOR_TESTING (contains git hash)
#     Wait until file TESTING_COMPLETE exists (contains git hash)
#     Timeout if no progress for RUN_TIMEOUT
#       Cleanup READY_FOR_TESTING and TESTING
# 2:
#   TESTER:
#     Replace file READY_FOR_TESTING by TESTING (contains git hash)
#     Run tests for git hash
#     Upload commit specific logs if failures
#     Upload git specific overall status file (failed or succeeded)
#     Replace file TESTING by TESTING_COMPLETE (contains git hash)
# 3:
#   BOT:
#     Read overall status
#     Delete TESTING_COMPLETE
#     Exit based on status

import gradle
import optparse
import os
import subprocess
import sys
import time
import utils
import run_on_app

# How often the bot/tester should check state
PULL_DELAY = 30
TEST_RESULT_DIR = 'internal'

# Magic files
READY_FOR_TESTING = 'READY_FOR_TESTING'
TESTING = 'TESTING'
TESTING_COMPLETE = 'TESTING_COMPLETE'

ALL_MAGIC = [READY_FOR_TESTING, TESTING, TESTING_COMPLETE]

# Log file names
STDERR = 'stderr'
STDOUT = 'stdout'
EXITCODE = 'exitcode'
TIMED_OUT = 'timed_out'

BENCHMARK_APPS = [
    {
        'app': 'r8',
        'version': 'cf',
        'find-xmx-min': 128,
        'find-xmx-max': 400,
        'find-xmx-range': 16,
        'oom-threshold': 247,
    },
    {
        'app': 'chrome',
        'version': '180917',
        'find-xmx-min': 256,
        'find-xmx-max': 450,
        'find-xmx-range': 16,
        'oom-threshold': 426,
    },
    {
        'app': 'youtube',
        'version': '12.22',
        'find-xmx-min': 800,
        'find-xmx-max': 1200,
        'find-xmx-range': 32,
        'oom-threshold': 1037,
        # TODO(b/143431825): Youtube can OOM randomly in memory configurations
        #  that should work.
        'skip-find-xmx-max': True,
    },
    {
        'app': 'iosched',
        'version': '2019',
        'find-xmx-min': 128,
        'find-xmx-max': 1024,
        'find-xmx-range': 16,
        'oom-threshold': 267,
    },
]

def find_min_xmx_command(record):
  assert record['find-xmx-min'] < record['find-xmx-max']
  assert record['find-xmx-range'] < record['find-xmx-max'] - record['find-xmx-min']
  return [
      'tools/run_on_app.py',
      '--compiler=r8',
      '--compiler-build=lib',
      '--app=%s' % record['app'],
      '--version=%s' % record['version'],
      '--no-debug',
      '--no-build',
      '--find-min-xmx',
      '--find-min-xmx-min-memory=%s' % record['find-xmx-min'],
      '--find-min-xmx-max-memory=%s' % record['find-xmx-max'],
      '--find-min-xmx-range-size=%s' % record['find-xmx-range'],
      '--find-min-xmx-archive']

def compile_with_memory_max_command(record):
  return [] if 'skip-find-xmx-max' in record else [
      'tools/run_on_app.py',
      '--compiler=r8',
      '--compiler-build=lib',
      '--app=%s' % record['app'],
      '--version=%s' % record['version'],
      '--no-debug',
      '--no-build',
      '--max-memory=%s' % int(record['oom-threshold'] * 1.15)
  ]

def compile_with_memory_min_command(record):
  return [
      'tools/run_on_app.py',
      '--compiler=r8',
      '--compiler-build=lib',
      '--app=%s' % record['app'],
      '--version=%s' % record['version'],
      '--no-debug',
      '--no-build',
      '--expect-oom',
      '--max-memory=%s' % int(record['oom-threshold'] * 0.85)
  ]

TEST_COMMANDS = [
    # Run test.py internal testing.
    ['tools/test.py', '--only_internal', '--slow_tests',
     '--java_max_memory_size=8G'],
    # Ensure that all internal apps compile.
    ['tools/run_on_app.py', '--run-all', '--out=out'],
    # Find min xmx for selected benchmark apps
    ['tools/gradle.py', 'r8lib'],
] + (map(find_min_xmx_command, BENCHMARK_APPS)
     + map(compile_with_memory_max_command, BENCHMARK_APPS)
     + map(compile_with_memory_min_command, BENCHMARK_APPS))

# Command timeout, in seconds.
RUN_TIMEOUT = 3600 * 6
BOT_RUN_TIMEOUT = RUN_TIMEOUT * len(TEST_COMMANDS)

def log(str):
  print("%s: %s" % (time.strftime("%c"), str))
  sys.stdout.flush()

def ParseOptions():
  result = optparse.OptionParser()
  result.add_option('--continuous',
      help='Continuously run internal tests and post results to GCS.',
      default=False, action='store_true')
  result.add_option('--print_logs',
      help='Fetch logs from gcs and print them, takes the commit to print for.',
      default=None)
  result.add_option('--bot',
      help='Run in bot mode, i.e., scheduling runs.',
      default=False, action='store_true')
  result.add_option('--archive',
       help='Post result to GCS, implied by --continuous',
       default=False, action='store_true')
  return result.parse_args()

def get_own_file_content():
  with open(sys.argv[0], 'r') as us:
    return us.read()

def restart_if_new_version(original_content):
  new_content = get_own_file_content()
  log('Lengths %s %s' % (len(original_content), len(new_content)))
  log('is master %s ' % utils.is_master())
  # Restart if the script got updated.
  if new_content != original_content:
    log('Restarting tools/internal_test.py, content changed')
    os.execv(sys.argv[0], sys.argv)

def ensure_git_clean():
  # Ensure clean git repo.
  diff = subprocess.check_output(['git', 'diff'])
  if len(diff) > 0:
    log('Local modifications to the git repo, exiting')
    sys.exit(1)

def git_pull():
  ensure_git_clean()
  subprocess.check_call(['git', 'checkout', 'master'])
  subprocess.check_call(['git', 'pull'])
  return utils.get_HEAD_sha1()

def git_checkout(git_hash):
  ensure_git_clean()
  # Ensure that we are up to date to get the commit.
  git_pull()
  exitcode = subprocess.call(['git', 'checkout', git_hash])
  if exitcode != 0:
    return None
  return utils.get_HEAD_sha1()

def get_test_result_dir():
  return os.path.join(utils.R8_TEST_RESULTS_BUCKET, TEST_RESULT_DIR)

def get_sha_destination(sha):
  return os.path.join(get_test_result_dir(), sha)

def archive_status(failed):
  gs_destination = 'gs://%s' % get_sha_destination(utils.get_HEAD_sha1())
  utils.archive_value('status', gs_destination, failed)

def get_status(sha):
  gs_destination = 'gs://%s/status' % get_sha_destination(sha)
  return utils.cat_file_on_cloud_storage(gs_destination)

def archive_log(stdout, stderr, exitcode, timed_out, cmd):
  sha = utils.get_HEAD_sha1()
  cmd_dir = cmd.replace(' ', '_').replace('/', '_')
  destination = os.path.join(get_sha_destination(sha), cmd_dir)
  gs_destination = 'gs://%s' % destination
  url = 'https://storage.cloud.google.com/%s' % destination
  log('Archiving logs to: %s' % gs_destination)
  utils.archive_value(EXITCODE, gs_destination, exitcode)
  utils.archive_value(TIMED_OUT, gs_destination, timed_out)
  utils.archive_file(STDOUT, gs_destination, stdout)
  utils.archive_file(STDERR, gs_destination, stderr)
  log('Logs available at: %s' % url)

def get_magic_file_base_path():
  return 'gs://%s/magic' % get_test_result_dir()

def get_magic_file_gs_path(name):
  return '%s/%s' % (get_magic_file_base_path(), name)

def get_magic_file_exists(name):
  return utils.file_exists_on_cloud_storage(get_magic_file_gs_path(name))

def delete_magic_file(name):
  utils.delete_file_from_cloud_storage(get_magic_file_gs_path(name))

def put_magic_file(name, sha):
  utils.archive_value(name, get_magic_file_base_path(), sha)

def get_magic_file_content(name, ignore_errors=False):
  return utils.cat_file_on_cloud_storage(get_magic_file_gs_path(name),
                                         ignore_errors=ignore_errors)

def print_magic_file_state():
  log('Magic file status:')
  for magic in ALL_MAGIC:
    if get_magic_file_exists(magic):
      content = get_magic_file_content(magic, ignore_errors=True)
      log('%s content: %s' % (magic, content))

def fetch_and_print_logs(hash):
  gs_base = 'gs://%s' % get_sha_destination(hash)
  listing = utils.ls_files_on_cloud_storage(gs_base).strip().split('\n')
  for entry in listing:
    if not entry.endswith('/status'): # Ignore the overall status file
      for to_print in [EXITCODE, TIMED_OUT, STDERR, STDOUT]:
        gs_location = '%s%s' % (entry, to_print)
        value = utils.cat_file_on_cloud_storage(gs_location)
        print('\n\n%s had value:\n%s' % (to_print, value))
  print("\n\nPrinting find-min-xmx ranges for apps")
  run_on_app.print_min_xmx_ranges_for_hash(hash, 'r8', 'lib')

def run_bot():
  print_magic_file_state()
  # Ensure that there is nothing currently scheduled (broken/stopped run)
  for magic in ALL_MAGIC:
    if get_magic_file_exists(magic):
      log('ERROR: Synchronizing file %s exists, cleaning up' % magic)
      delete_magic_file(magic)
  print_magic_file_state()
  assert not get_magic_file_exists(READY_FOR_TESTING)
  git_hash = utils.get_HEAD_sha1()
  put_magic_file(READY_FOR_TESTING, git_hash)
  begin = time.time()
  while True:
    if time.time() - begin > BOT_RUN_TIMEOUT:
      log('Timeout exceeded: http://go/internal-r8-doc')
      raise Exception('Bot timeout')
    if get_magic_file_exists(TESTING_COMPLETE):
      if get_magic_file_content(TESTING_COMPLETE) == git_hash:
        break
      else:
        raise Exception('Non matching git hashes %s and %s' % (
            get_magic_file_content(TESTING_COMPLETE), git_hash))
    log('Still waiting for test result')
    print_magic_file_state()
    time.sleep(PULL_DELAY)
  total_time = time.time()-begin
  log('Done running test for %s in %ss' % (git_hash, total_time))
  test_status = get_status(git_hash)
  delete_magic_file(TESTING_COMPLETE)
  log('Test status is: %s' % test_status)
  if test_status != '0':
    print('Tests failed, you can print the logs by running(googlers only):')
    print('  tools/internal_test.py --print_logs %s' % git_hash)
    return 1

def run_continuously():
  # If this script changes, we will restart ourselves
  own_content = get_own_file_content()
  while True:
    restart_if_new_version(own_content)
    print_magic_file_state()
    if get_magic_file_exists(READY_FOR_TESTING):
      git_hash = get_magic_file_content(READY_FOR_TESTING)
      checked_out = git_checkout(git_hash)
      if not checked_out:
        # Gerrit change, we don't run these on internal.
        archive_status(0)
        put_magic_file(TESTING_COMPLETE, git_hash)
        delete_magic_file(READY_FOR_TESTING)
        continue
      # If the script changed, we need to restart now to get correct commands
      # Note that we have not removed the READY_FOR_TESTING yet, so if we
      # execv we will pick up the same version.
      restart_if_new_version(own_content)
      # Sanity check, if this does not succeed stop.
      if checked_out != git_hash:
        log('Inconsistent state: %s %s' % (git_hash, checked_out))
        sys.exit(1)
      put_magic_file(TESTING, git_hash)
      delete_magic_file(READY_FOR_TESTING)
      log('Running with hash: %s' % git_hash)
      exitcode = run_once(archive=True)
      log('Running finished with exit code %s' % exitcode)
      put_magic_file(TESTING_COMPLETE, git_hash)
      delete_magic_file(TESTING)
    time.sleep(PULL_DELAY)

def handle_output(archive, stderr, stdout, exitcode, timed_out, cmd):
  if archive:
    archive_log(stdout, stderr, exitcode, timed_out, cmd)
  else:
    print 'Execution of %s resulted in:' % cmd
    print 'exit code: %s ' % exitcode
    print 'timeout: %s ' % timed_out
    with open(stderr, 'r') as f:
      print 'stderr: %s' % f.read()
    with open(stdout, 'r') as f:
      print 'stdout: %s' % f.read()

def execute(cmd, archive, env=None):
  if cmd == []:
    return

  utils.PrintCmd(cmd)
  with utils.TempDir() as temp:
    try:
      stderr_fd = None
      stdout_fd = None
      exitcode = 0
      stderr = os.path.join(temp, 'stderr')
      stderr_fd = open(stderr, 'w')
      stdout = os.path.join(temp, 'stdout')
      stdout_fd = open(stdout, 'w')
      popen = subprocess.Popen(cmd,
                               bufsize=1024*1024*10,
                               stdout=stdout_fd,
                               stderr=stderr_fd,
                               env=env)
      begin = time.time()
      timed_out = False
      while popen.poll() == None:
        if time.time() - begin > RUN_TIMEOUT:
          popen.terminate()
          timed_out = True
        time.sleep(2)
      exitcode = popen.returncode
    finally:
      if stderr_fd:
        stderr_fd.close()
      if stdout_fd:
        stdout_fd.close()
      if exitcode != 0:
        handle_output(archive, stderr, stdout, popen.returncode,
                      timed_out, ' '.join(cmd))
    return exitcode

def run_once(archive):
  failed = False
  git_hash = utils.get_HEAD_sha1()
  log('Running once with hash %s' % git_hash)
  env = os.environ.copy()
  # Bot does not have a lot of memory.
  env['R8_GRADLE_CORES_PER_FORK'] = '16'
  failed = any([execute(cmd, archive, env) for cmd in TEST_COMMANDS])
  # Gradle daemon occasionally leaks memory, stop it.
  gradle.RunGradle(['--stop'])
  archive_status(1 if failed else 0)
  return failed

def Main():
  (options, args) = ParseOptions()
  if options.continuous:
    run_continuously()
  elif options.bot:
    return run_bot()
  elif options.print_logs:
    return fetch_and_print_logs(options.print_logs)
  else:
    return run_once(options.archive)

if __name__ == '__main__':
  sys.exit(Main())
