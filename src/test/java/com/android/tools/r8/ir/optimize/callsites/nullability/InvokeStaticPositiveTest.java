// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.optimize.callsites.nullability;

import static com.android.tools.r8.utils.codeinspector.Matchers.isPresent;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertTrue;

import com.android.tools.r8.NeverInline;
import com.android.tools.r8.TestBase;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.TestParametersCollection;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.ir.optimize.info.CallSiteOptimizationInfo;
import com.android.tools.r8.utils.codeinspector.ClassSubject;
import com.android.tools.r8.utils.codeinspector.CodeInspector;
import com.android.tools.r8.utils.codeinspector.InstructionSubject;
import com.android.tools.r8.utils.codeinspector.MethodSubject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class InvokeStaticPositiveTest extends TestBase {
  private static final Class<?> MAIN = Main.class;

  @Parameterized.Parameters(name = "{0}")
  public static TestParametersCollection data() {
    return getTestParameters().withAllRuntimesAndApiLevels().build();
  }

  private final TestParameters parameters;

  public InvokeStaticPositiveTest(TestParameters parameters) {
    this.parameters = parameters;
  }

  @Test
  public void testR8() throws Exception {
    testForR8(parameters.getBackend())
        .addInnerClasses(InvokeStaticPositiveTest.class)
        .addKeepMainRule(MAIN)
        .enableInliningAnnotations()
        .addOptionsModification(o -> {
          o.testing.callSiteOptimizationInfoInspector = this::callSiteOptimizationInfoInspect;
        })
        .setMinApi(parameters.getApiLevel())
        .run(parameters.getRuntime(), MAIN)
        .assertSuccessWithOutputLines("non-null")
        .inspect(this::inspect);
  }

  private void callSiteOptimizationInfoInspect(DexEncodedMethod encodedMethod) {
    assert encodedMethod.method.name.toString().equals("test")
        : "Unexpected revisit: " + encodedMethod.toSourceString();
    CallSiteOptimizationInfo callSiteOptimizationInfo = encodedMethod.getCallSiteOptimizationInfo();
    assert callSiteOptimizationInfo.getDynamicUpperBoundType(0).isDefinitelyNotNull();
  }

  private void inspect(CodeInspector inspector) {
    ClassSubject main = inspector.clazz(MAIN);
    assertThat(main, isPresent());

    MethodSubject test = main.uniqueMethodWithName("test");
    assertThat(test, isPresent());
    // Can optimize branches since `arg` is definitely not null.
    assertTrue(test.streamInstructions().noneMatch(InstructionSubject::isIf));
  }

  static class Main {
    public static void main(String... args) {
      test(new Object()); // calls test with non-null instance.
    }

    @NeverInline
    static void test(Object arg) {
      if (arg != null) {
        System.out.println("non-null");
      } else {
        System.out.println("null");
      }
    }
  }
}
