// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.optimize.callsites.constants;

import static com.android.tools.r8.utils.codeinspector.Matchers.isPresent;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertTrue;

import com.android.tools.r8.NeverClassInline;
import com.android.tools.r8.NeverInline;
import com.android.tools.r8.NeverMerge;
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
public class InvokeVirtualNegativeTest extends TestBase {

  private static final Class<?> MAIN = Main.class;

  @Parameterized.Parameters(name = "{0}")
  public static TestParametersCollection data() {
    return getTestParameters().withAllRuntimesAndApiLevels().build();
  }

  private final TestParameters parameters;

  public InvokeVirtualNegativeTest(TestParameters parameters) {
    this.parameters = parameters;
  }

  @Test
  public void testR8() throws Exception {
    testForR8(parameters.getBackend())
        .addInnerClasses(InvokeVirtualNegativeTest.class)
        .addKeepMainRule(MAIN)
        .enableMergeAnnotations()
        .enableClassInliningAnnotations()
        .enableInliningAnnotations()
        .addOptionsModification(o -> {
          o.testing.callSiteOptimizationInfoInspector = this::callSiteOptimizationInfoInspect;
        })
        .setMinApi(parameters.getApiLevel())
        .run(parameters.getRuntime(), MAIN)
        .assertSuccessWithOutputLines("null", "non-null", "null", "non-null")
        .inspect(this::inspect);
  }

  private void callSiteOptimizationInfoInspect(DexEncodedMethod encodedMethod) {
    String methodName = encodedMethod.method.name.toString();
    assert methodName.equals("m") || methodName.equals("test")
        : "Unexpected revisit: " + encodedMethod.toSourceString();
    CallSiteOptimizationInfo callSiteOptimizationInfo = encodedMethod.getCallSiteOptimizationInfo();
    if (methodName.equals("m")) {
      assert callSiteOptimizationInfo.getDynamicUpperBoundType(1).isDefinitelyNotNull();
      assert callSiteOptimizationInfo.getAbstractArgumentValue(1).isUnknown();
    } else {
      assert methodName.equals("test");
      assert callSiteOptimizationInfo.getDynamicUpperBoundType(0).isDefinitelyNotNull();
      assert callSiteOptimizationInfo.getAbstractArgumentValue(0).isUnknown();
    }
  }

  private void inspect(CodeInspector inspector) {
    ClassSubject a = inspector.clazz(A.class);
    assertThat(a, isPresent());

    MethodSubject a_m = a.uniqueMethodWithName("m");
    assertThat(a_m, isPresent());
    // Should not optimize branches since the value of `arg` is unsure.
    assertTrue(a_m.streamInstructions().anyMatch(InstructionSubject::isIf));

    ClassSubject b = inspector.clazz(B.class);
    assertThat(b, isPresent());

    MethodSubject b_m = b.uniqueMethodWithName("m");
    assertThat(b_m, isPresent());
    // Should not optimize branches since the value of `arg` is unsure.
    assertTrue(a_m.streamInstructions().anyMatch(InstructionSubject::isIf));
  }

  @NeverMerge
  @NeverClassInline
  static class A {
    @NeverInline
    void m(String arg) {
      if (arg.contains("null")) {
        System.out.println("null");
      } else {
        System.out.println("non-null");
      }
    }

    @NeverInline
    @Override
    public String toString() {
      return "A";
    }
  }

  @NeverClassInline
  static class B extends A {
    @NeverInline
    @Override
    void m(String arg) {
      // Same as A#m.
      if (arg.contains("null")) {
        System.out.println("null");
      } else {
        System.out.println("non-null");
      }
    }

    @NeverInline
    @Override
    public String toString() {
      return "B";
    }
  }

  static class Main {
    public static void main(String... args) {
      A a = new A();
      test(a);    // calls A.m() with "null".
      a.m("nul"); // calls A.m() with "nul".
      B b = new B();
      test(b);    // calls B.m() with "null".
      b.m("nul"); // calls B.m() with "nul".
    }

    @NeverInline
    static void test(A arg) {
      arg.m("null");
    }
  }
}
