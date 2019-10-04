// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.optimize.inliner;

import com.android.tools.r8.graph.DexEncodedMethod;
import java.io.PrintStream;

class WhyAreYouNotInliningReporterImpl extends WhyAreYouNotInliningReporter {

  private final DexEncodedMethod callee;
  private final DexEncodedMethod context;
  private final PrintStream output;

  private boolean reasonHasBeenReported = false;

  WhyAreYouNotInliningReporterImpl(
      DexEncodedMethod callee, DexEncodedMethod context, PrintStream output) {
    this.callee = callee;
    this.context = context;
    this.output = output;
  }

  private void print(String reason) {
    output.print("Method `");
    output.print(callee.method.toSourceString());
    output.print("` was not inlined into `");
    output.print(context.method.toSourceString());
    output.print("`: ");
    output.println(reason);
    reasonHasBeenReported = true;
  }

  @Override
  public void reportUnknownTarget() {
    print("could not find a single target.");
  }

  @Override
  public boolean verifyReasonHasBeenReported() {
    assert reasonHasBeenReported;
    return true;
  }
}