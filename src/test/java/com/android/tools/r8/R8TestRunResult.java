// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8;

import static org.junit.Assert.assertNotNull;

import com.android.tools.r8.ToolHelper.ProcessResult;
import com.android.tools.r8.naming.retrace.StackTrace;
import com.android.tools.r8.retrace.Retrace;
import com.android.tools.r8.retrace.RetraceCommand;
import com.android.tools.r8.utils.AndroidApp;
import com.android.tools.r8.utils.StringUtils;
import com.android.tools.r8.utils.ThrowingConsumer;
import com.android.tools.r8.utils.codeinspector.CodeInspector;
import com.android.tools.r8.utils.graphinspector.GraphInspector;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

public class R8TestRunResult extends TestRunResult<R8TestRunResult> {

  public interface GraphInspectorSupplier {
    GraphInspector get() throws IOException, ExecutionException;
  }

  private final String proguardMap;
  private final GraphInspectorSupplier graphInspector;

  public R8TestRunResult(
      AndroidApp app,
      TestRuntime runtime,
      ProcessResult result,
      String proguardMap,
      GraphInspectorSupplier graphInspector) {
    super(app, runtime, result);
    this.proguardMap = proguardMap;
    this.graphInspector = graphInspector;
  }

  @Override
  protected R8TestRunResult self() {
    return this;
  }

  @Override
  public StackTrace getStackTrace() {
    return super.getStackTrace().retrace(proguardMap);
  }

  public StackTrace getOriginalStackTrace() {
    return super.getStackTrace();
  }

  @Override
  public CodeInspector inspector() throws IOException, ExecutionException {
    // See comment in base class.
    assertSuccess();
    assertNotNull(app);
    return new CodeInspector(app, proguardMap);
  }

  public <E extends Throwable> R8TestRunResult inspectOriginalStackTrace(
      ThrowingConsumer<StackTrace, E> consumer) throws E {
    consumer.accept(getOriginalStackTrace());
    return self();
  }

  public GraphInspector graphInspector() throws IOException, ExecutionException {
    assertSuccess();
    return graphInspector.get();
  }

  public R8TestRunResult  inspectGraph(Consumer<GraphInspector> consumer)
      throws IOException, ExecutionException {
    consumer.accept(graphInspector());
    return self();
  }

  public String proguardMap() {
    return proguardMap;
  }

  public List<String> retrace() {
    class Box {
      List<String> result;
    }
    Box box = new Box();
    Retrace.run(
        RetraceCommand.builder()
            .setProguardMapProducer(() -> proguardMap)
            .setStackTrace(StringUtils.splitLines(getStdErr()))
            .setRetracedStackTraceConsumer(retraced -> box.result = retraced)
            .build());
    return box.result;
  }
}
