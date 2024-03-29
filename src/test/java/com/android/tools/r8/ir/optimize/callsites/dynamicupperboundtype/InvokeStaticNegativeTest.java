// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.optimize.callsites.dynamicupperboundtype;

import static com.android.tools.r8.utils.codeinspector.Matchers.isPresent;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertTrue;

import com.android.tools.r8.NeverInline;
import com.android.tools.r8.TestBase;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.TestParametersCollection;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.ir.analysis.type.TypeLatticeElement;
import com.android.tools.r8.ir.optimize.info.CallSiteOptimizationInfo;
import com.android.tools.r8.utils.codeinspector.ClassSubject;
import com.android.tools.r8.utils.codeinspector.CodeInspector;
import com.android.tools.r8.utils.codeinspector.InstructionSubject;
import com.android.tools.r8.utils.codeinspector.MethodSubject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class InvokeStaticNegativeTest extends TestBase {
  private static final Class<?> MAIN = Main.class;

  @Parameterized.Parameters(name = "{0}")
  public static TestParametersCollection data() {
    return getTestParameters().withAllRuntimesAndApiLevels().build();
  }

  private final TestParameters parameters;

  public InvokeStaticNegativeTest(TestParameters parameters) {
    this.parameters = parameters;
  }

  @Test
  public void testR8() throws Exception {
    testForR8(parameters.getBackend())
        .addInnerClasses(InvokeStaticNegativeTest.class)
        .addKeepMainRule(MAIN)
        .enableInliningAnnotations()
        .addOptionsModification(o -> {
          o.testing.callSiteOptimizationInfoInspector = this::callSiteOptimizationInfoInspect;
        })
        .setMinApi(parameters.getApiLevel())
        .run(parameters.getRuntime(), MAIN)
        .assertSuccessWithOutputLines("Sub1", "Sub2")
        .inspect(this::inspect);
  }

  private void callSiteOptimizationInfoInspect(DexEncodedMethod encodedMethod) {
    assert encodedMethod.method.name.toString().equals("test")
        : "Unexpected revisit: " + encodedMethod.toSourceString();
    CallSiteOptimizationInfo callSiteOptimizationInfo = encodedMethod.getCallSiteOptimizationInfo();
    TypeLatticeElement upperBoundType = callSiteOptimizationInfo.getDynamicUpperBoundType(0);
    assert upperBoundType.isDefinitelyNotNull();
    assert upperBoundType.isClassType()
        && upperBoundType.asClassTypeLatticeElement()
            .getClassType().toSourceString().endsWith("$Base");
  }

  private void inspect(CodeInspector inspector) {
    ClassSubject main = inspector.clazz(MAIN);
    assertThat(main, isPresent());

    MethodSubject test = main.uniqueMethodWithName("test");
    assertThat(test, isPresent());

    // Should not optimize branches since the type of `arg` is unsure.
    assertTrue(test.streamInstructions().anyMatch(InstructionSubject::isIf));

    // Should not optimize away Sub1, since it's still referred/instantiated.
    ClassSubject sub1 = inspector.clazz(Sub1.class);
    assertThat(sub1, isPresent());

    // Should not optimize away Sub2, since it's still referred/instantiated.
    ClassSubject sub2 = inspector.clazz(Sub2.class);
    assertThat(sub2, isPresent());
  }

  static class Base {}
  static class Sub1 extends Base {}
  static class Sub2 extends Base {}

  static class Main {
    public static void main(String... args) {
      test(new Sub1()); // calls test with Sub1.
      test(new Sub2()); // calls test with Sub2.
    }

    @NeverInline
    static void test(Base arg) {
      if (arg instanceof Sub1) {
        System.out.println("Sub1");
      } else if (arg instanceof Sub2) {
        System.out.println("Sub2");
      }
    }
  }
}
