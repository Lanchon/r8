// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.desugar.backports;

import com.android.tools.r8.TestParameters;
import com.android.tools.r8.TestRuntime.CfVm;
import com.android.tools.r8.ToolHelper;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import static com.android.tools.r8.utils.FileUtils.JAR_EXTENSION;

@RunWith(Parameterized.class)
public class SetBackportJava9Test extends AbstractBackportTest {
  @Parameters(name = "{0}")
  public static Iterable<?> data() {
    return getTestParameters()
        .withCfRuntimesStartingFromIncluding(CfVm.JDK9)
        .withDexRuntimes()
        .withAllApiLevels()
        .build();
  }

  private static final Path TEST_JAR =
      Paths.get(ToolHelper.EXAMPLES_JAVA9_BUILD_DIR).resolve("backport" + JAR_EXTENSION);

  public SetBackportJava9Test(TestParameters parameters) {
    super(parameters, Set.class, TEST_JAR, "backport.SetBackportJava9Main");
    // TODO Once shipped in an actual API level, migrate to SetBackportTest

    // Available since API 1 and used to test created sets.
    ignoreInvokes("add");
    ignoreInvokes("contains");
    ignoreInvokes("size");
  }
}
