// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.desugar.backports;

import static com.android.tools.r8.utils.codeinspector.Matchers.isPresent;
import static java.util.stream.Collectors.toList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;

import com.android.tools.r8.TestBase;
import com.android.tools.r8.TestBuilder;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.utils.AndroidApiLevel;
import com.android.tools.r8.utils.codeinspector.ClassSubject;
import com.android.tools.r8.utils.codeinspector.CodeInspector;
import com.android.tools.r8.utils.codeinspector.InstructionSubject;
import com.android.tools.r8.utils.codeinspector.MethodSubject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import org.junit.Assert;
import org.junit.Test;

abstract class AbstractBackportTest extends TestBase {
  private final TestParameters parameters;
  private final Class<?> targetClass;
  private final Class<?> testClass;
  private final Path testJar;
  private final String testClassName;
  private final NavigableMap<AndroidApiLevel, Integer> invokeStaticCounts = new TreeMap<>();
  private final Set<String> ignoredInvokes = new HashSet<>();

  AbstractBackportTest(TestParameters parameters, Class<?> targetClass,
      Class<?> testClass) {
    this(parameters, targetClass, testClass, null, null);
  }

  AbstractBackportTest(TestParameters parameters, Class<?> targetClass,
      Path testJar, String testClassName) {
    this(parameters, targetClass, null, testJar, testClassName);
  }

  private AbstractBackportTest(TestParameters parameters, Class<?> targetClass,
      Class<?> testClass, Path testJar, String testClassName) {
    this.parameters = parameters;
    this.targetClass = targetClass;
    this.testClass = testClass;
    this.testJar = testJar;

    if (testClass != null) {
      assert testJar == null;
      assert testClassName == null;
      this.testClassName = testClass.getName();
    } else {
      assert testJar != null;
      assert testClassName != null;
      this.testClassName = testClassName;
    }

    // Assume all method calls will be rewritten on the lowest API level.
    invokeStaticCounts.put(AndroidApiLevel.B, 0);
  }

  void registerTarget(AndroidApiLevel apiLevel, int invokeStaticCount) {
    invokeStaticCounts.put(apiLevel, invokeStaticCount);
  }

  void ignoreInvokes(String methodName) {
    ignoredInvokes.add(methodName);
  }

  private void configureProgram(TestBuilder<?, ?> builder) throws IOException {
    builder.addProgramClasses(MiniAssert.class, IgnoreInvokes.class);
    if (testClass != null) {
      builder.addProgramClassesAndInnerClasses(testClass);
    } else {
      builder.addProgramFiles(testJar);
    }
  }

  @Test
  public void desugaring() throws Exception {
    if (parameters.isCfRuntime()) {
      testForJvm()
          .apply(this::configureProgram)
          .run(parameters.getRuntime(), testClassName)
          .assertSuccess();
    } else {
      testForD8()
          .setMinApi(parameters.getApiLevel())
          .apply(this::configureProgram)
          .setIncludeClassesChecksum(true)
          .compile()
          .run(parameters.getRuntime(), testClassName)
          .assertSuccess()
          .inspect(this::assertDesugaring);
    }
  }

  private void assertDesugaring(CodeInspector inspector) {
    ClassSubject testSubject = inspector.clazz(testClassName);
    assertThat(testSubject, isPresent());

    List<InstructionSubject> javaInvokeStatics = testSubject.allMethods()
        .stream()
        // Do not count @IgnoreInvokes-annotated methods.
        .filter(i -> !i.annotation(IgnoreInvokes.class.getName()).isPresent())
        .flatMap(MethodSubject::streamInstructions)
        .filter(InstructionSubject::isInvoke)
        .filter(is -> is.getMethod().holder.toSourceString().equals(targetClass.getName()))
        // Do not count invokes if explicitly ignored.
        .filter(is -> !ignoredInvokes.contains(is.getMethod().name.toString()))
        .collect(toList());

    AndroidApiLevel apiLevel = parameters.getApiLevel();
    long expectedTargetInvokes = invokeStaticCounts.ceilingEntry(apiLevel).getValue();
    long actualTargetInvokes = javaInvokeStatics.size();
    assertEquals("Expected "
        + expectedTargetInvokes
        + " invokes on "
        + targetClass.getName()
        + " but found "
        + actualTargetInvokes
        + ": "
        + javaInvokeStatics, expectedTargetInvokes, actualTargetInvokes);
  }

  /** JUnit {@link Assert} isn't available in the VM runtime. This is a mini mirror of its API. */
  static abstract class MiniAssert {
    static void assertTrue(boolean value) {
      assertEquals(true, value);
    }

    static void assertFalse(boolean value) {
      assertEquals(false, value);
    }

    static void assertEquals(boolean expected, boolean actual) {
      if (expected != actual) {
        throw new AssertionError("Expected <" + expected + "> but was <" + actual + '>');
      }
    }

    static void assertEquals(int expected, int actual) {
      if (expected != actual) {
        throw new AssertionError("Expected <" + expected + "> but was <" + actual + '>');
      }
    }

    static void assertEquals(long expected, long actual) {
      if (expected != actual) {
        throw new AssertionError("Expected <" + expected + "> but was <" + actual + '>');
      }
    }

    static void assertEquals(float expected, float actual) {
      if (Float.compare(expected, actual) != 0) {
        throw new AssertionError("Expected <" + expected + "> but was <" + actual + '>');
      }
    }

    static void assertEquals(double expected, double actual) {
      if (Double.compare(expected, actual) != 0) {
        throw new AssertionError("Expected <" + expected + "> but was <" + actual + '>');
      }
    }

    static void assertEquals(Object expected, Object actual) {
      if (expected != actual && (expected == null || !expected.equals(actual))) {
        throw new AssertionError("Expected <" + expected + "> but was <" + actual + '>');
      }
    }

    static void assertSame(Object expected, Object actual) {
      if (expected != actual) {
        throw new AssertionError(
            "Expected <" + expected + "> to be same instance as <" + actual + '>');
      }
    }
  }
}
