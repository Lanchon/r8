// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.graph;

import static org.junit.Assert.assertEquals;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL;

import com.android.tools.r8.CompilationFailedException;
import com.android.tools.r8.TestBase;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.TestParametersCollection;
import com.android.tools.r8.TestRunResult;
import java.io.IOException;
import java.util.concurrent.ExecutionException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

// This is a reproduction of b/144450911.
@RunWith(Parameterized.class)
public class InvokeSpecialForNonDeclaredInvokeVirtualTest extends TestBase {

  private final TestParameters parameters;

  @Parameters(name = "{0}")
  public static TestParametersCollection data() {
    return getTestParameters().withAllRuntimesAndApiLevels().build();
  }

  public InvokeSpecialForNonDeclaredInvokeVirtualTest(TestParameters parameters) {
    this.parameters = parameters;
  }

  @Test
  public void testRuntime() throws IOException, CompilationFailedException, ExecutionException {
    TestRunResult<?> runResult =
        testForRuntime(parameters.getRuntime(), parameters.getApiLevel())
            .addProgramClasses(A.class, B.class, Main.class)
            .addProgramClassFileData(getClassCWithTransformedInvoked())
            .run(parameters.getRuntime(), Main.class)
            .assertSuccessWithOutputLines("Hello World!");
  }

  private byte[] getClassCWithTransformedInvoked() throws IOException {
    return transformer(C.class)
        .transformMethodInsnInMethod(
            "bar",
            (opcode, owner, name, descriptor, isInterface, continuation) -> {
              assertEquals(INVOKEVIRTUAL, opcode);
              continuation.apply(INVOKESPECIAL, owner, name, descriptor, isInterface);
            })
        .transform();
  }

  public static class A {

    void foo() {
      System.out.println("Hello World!");
    }
  }

  public static class B extends A {}

  public static class C extends B {

    void bar() {
      foo();
    }
  }

  public static class Main {

    public static void main(String[] args) {
      new C().bar();
    }
  }
}
