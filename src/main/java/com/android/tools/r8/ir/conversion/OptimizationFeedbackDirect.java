// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.conversion;

import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexEncodedMethod.ClassInlinerEligibility;
import com.android.tools.r8.graph.ParameterUsagesInfo;
import com.android.tools.r8.graph.DexEncodedMethod.TrivialInitializer;
import com.android.tools.r8.ir.optimize.Inliner.ConstraintWithTarget;
import java.util.BitSet;

public class OptimizationFeedbackDirect implements OptimizationFeedback {

  @Override
  public void methodReturnsArgument(DexEncodedMethod method, int argument) {
    method.markReturnsArgument(argument);
  }

  @Override
  public void methodReturnsConstant(DexEncodedMethod method, long value) {
    method.markReturnsConstant(value);
  }

  @Override
  public void methodNeverReturnsNull(DexEncodedMethod method) {
    method.markNeverReturnsNull();
  }

  @Override
  public void methodNeverReturnsNormally(DexEncodedMethod method) {
    method.markNeverReturnsNormally();
  }

  @Override
  public void markProcessed(DexEncodedMethod method, ConstraintWithTarget state) {
    method.markProcessed(state);
  }

  @Override
  public void markCheckNullReceiverBeforeAnySideEffect(DexEncodedMethod method, boolean mark) {
    method.markCheckNullReceiverBeforeAnySideEffect(mark);
  }

  @Override
  public void markTriggerClassInitBeforeAnySideEffect(DexEncodedMethod method, boolean mark) {
    method.markTriggerClassInitBeforeAnySideEffect(mark);
  }

  @Override
  public void setClassInlinerEligibility(
      DexEncodedMethod method, ClassInlinerEligibility eligibility) {
    method.setClassInlinerEligibility(eligibility);
  }

  @Override
  public void setTrivialInitializer(DexEncodedMethod method, TrivialInitializer info) {
    method.setTrivialInitializer(info);
  }

  @Override
  public void setInitializerEnablingJavaAssertions(DexEncodedMethod method) {
    method.setInitializerEnablingJavaAssertions();
  }

  @Override
  public void setParameterUsages(DexEncodedMethod method, ParameterUsagesInfo parameterUsagesInfo) {
    method.setParameterUsages(parameterUsagesInfo);
  }

  @Override
  public void setKotlinNotNullParamHints(DexEncodedMethod method, BitSet hints) {
    method.setKotlinNotNullParamHints(hints);
  }
}