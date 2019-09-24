// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.analysis.fieldvalueanalysis;

import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.DexEncodedField;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexField;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.ir.analysis.type.Nullability;
import com.android.tools.r8.ir.analysis.type.TypeLatticeElement;
import com.android.tools.r8.ir.code.BasicBlock;
import com.android.tools.r8.ir.code.DominatorTree;
import com.android.tools.r8.ir.code.DominatorTree.Assumption;
import com.android.tools.r8.ir.code.IRCode;
import com.android.tools.r8.ir.code.Instruction;
import com.android.tools.r8.ir.code.InstructionIterator;
import com.android.tools.r8.ir.code.StaticPut;
import com.android.tools.r8.ir.code.Value;
import com.android.tools.r8.ir.optimize.info.OptimizationFeedback;
import com.android.tools.r8.shaking.AppInfoWithLiveness;
import com.android.tools.r8.utils.DequeUtils;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

public class FieldValueAnalysis {

  private final AppView<AppInfoWithLiveness> appView;
  private final IRCode code;
  private final OptimizationFeedback feedback;
  private final DexEncodedMethod method;

  private Map<BasicBlock, AbstractFieldSet> fieldsMaybeReadBeforeBlockInclusiveCache;

  private FieldValueAnalysis(
      AppView<AppInfoWithLiveness> appView,
      IRCode code,
      OptimizationFeedback feedback,
      DexEncodedMethod method) {
    this.appView = appView;
    this.code = code;
    this.feedback = feedback;
    this.method = method;
  }

  public static void run(
      AppView<?> appView, IRCode code, OptimizationFeedback feedback, DexEncodedMethod method) {
    if (appView.enableWholeProgramOptimizations() && method.isClassInitializer()) {
      assert appView.appInfo().hasLiveness();
      new FieldValueAnalysis(appView.withLiveness(), code, feedback, method)
          .computeFieldOptimizationInfo();
    }
  }

  private Map<BasicBlock, AbstractFieldSet> getOrCreateFieldsMaybeReadBeforeBlockInclusive() {
    if (fieldsMaybeReadBeforeBlockInclusiveCache == null) {
      fieldsMaybeReadBeforeBlockInclusiveCache = createFieldsMaybeReadBeforeBlockInclusive();
    }
    return fieldsMaybeReadBeforeBlockInclusiveCache;
  }

  /** This method analyzes initializers with the purpose of computing field optimization info. */
  private void computeFieldOptimizationInfo() {
    AppInfoWithLiveness appInfo = appView.appInfo();
    DominatorTree dominatorTree = null;

    if (method.isClassInitializer()) {
      DexType context = method.method.holder;

      // Find all the static-put instructions that assign a field in the enclosing class which is
      // guaranteed to be assigned only in the current initializer.
      boolean isStraightLineCode = true;
      Map<DexEncodedField, LinkedList<StaticPut>> staticPutsPerField = new IdentityHashMap<>();
      for (Instruction instruction : code.instructions()) {
        if (instruction.isStaticPut()) {
          StaticPut staticPut = instruction.asStaticPut();
          DexField field = staticPut.getField();
          DexEncodedField encodedField = appInfo.resolveField(field);
          if (encodedField != null
              && encodedField.field.holder == context
              && appInfo.isStaticFieldWrittenOnlyInEnclosingStaticInitializer(encodedField)) {
            staticPutsPerField
                .computeIfAbsent(encodedField, ignore -> new LinkedList<>())
                .add(staticPut);
          }
        }
        if (instruction.isJumpInstruction()) {
          if (!instruction.isGoto() && !instruction.isReturn()) {
            isStraightLineCode = false;
          }
        }
      }

      List<BasicBlock> normalExitBlocks = code.computeNormalExitBlocks();
      for (Entry<DexEncodedField, LinkedList<StaticPut>> entry : staticPutsPerField.entrySet()) {
        DexEncodedField encodedField = entry.getKey();
        LinkedList<StaticPut> staticPuts = entry.getValue();
        if (staticPuts.size() > 1) {
          continue;
        }
        StaticPut staticPut = staticPuts.getFirst();
        if (!isStraightLineCode) {
          if (dominatorTree == null) {
            dominatorTree = new DominatorTree(code, Assumption.NO_UNREACHABLE_BLOCKS);
          }
          if (!dominatorTree.dominatesAllOf(staticPut.getBlock(), normalExitBlocks)) {
            continue;
          }
        }
        if (fieldMaybeReadBeforeInstruction(encodedField, staticPut)) {
          continue;
        }
        updateFieldOptimizationInfo(encodedField, staticPut.value());
      }
    }
  }

  private boolean fieldMaybeReadBeforeInstruction(
      DexEncodedField encodedField, Instruction instruction) {
    BasicBlock block = instruction.getBlock();

    // First check if the field may be read in any of the (transitive) predecessor blocks.
    if (fieldMaybeReadBeforeBlock(encodedField, block)) {
      return true;
    }

    // Then check if any of the instructions that precede the given instruction in the current block
    // may read the field.
    DexType context = method.method.holder;
    InstructionIterator instructionIterator = block.iterator();
    while (instructionIterator.hasNext()) {
      Instruction current = instructionIterator.next();
      if (current == instruction) {
        break;
      }
      if (current.readSet(appView, context).contains(encodedField)) {
        return true;
      }
    }

    // Otherwise, the field is not read prior to the given instruction.
    return false;
  }

  private boolean fieldMaybeReadBeforeBlock(DexEncodedField encodedField, BasicBlock block) {
    for (BasicBlock predecessor : block.getPredecessors()) {
      if (fieldMaybeReadBeforeBlockInclusive(encodedField, predecessor)) {
        return true;
      }
    }
    return false;
  }

  private boolean fieldMaybeReadBeforeBlockInclusive(
      DexEncodedField encodedField, BasicBlock block) {
    return getOrCreateFieldsMaybeReadBeforeBlockInclusive().get(block).contains(encodedField);
  }

  /**
   * Eagerly creates a mapping from each block to the set of fields that may be read in that block
   * and its transitive predecessors.
   */
  private Map<BasicBlock, AbstractFieldSet> createFieldsMaybeReadBeforeBlockInclusive() {
    DexType context = method.method.holder;
    Map<BasicBlock, AbstractFieldSet> result = new IdentityHashMap<>();
    Deque<BasicBlock> worklist = DequeUtils.newArrayDeque(code.entryBlock());
    while (!worklist.isEmpty()) {
      BasicBlock block = worklist.removeFirst();
      boolean seenBefore = result.containsKey(block);
      AbstractFieldSet readSet =
          result.computeIfAbsent(block, ignore -> EmptyFieldSet.getInstance());
      if (readSet.isTop()) {
        // We already have unknown information for this block.
        continue;
      }

      assert readSet.isKnownFieldSet();
      KnownFieldSet knownReadSet = readSet.asKnownFieldSet();
      int oldSize = seenBefore ? knownReadSet.size() : -1;

      // Everything that is read in the predecessor blocks should also be included in the read set
      // for the current block, so here we join the information from the predecessor blocks into the
      // current read set.
      boolean blockOrPredecessorMaybeReadAnyField = false;
      for (BasicBlock predecessor : block.getPredecessors()) {
        AbstractFieldSet predecessorReadSet =
            result.getOrDefault(predecessor, EmptyFieldSet.getInstance());
        if (predecessorReadSet.isBottom()) {
          continue;
        }
        if (predecessorReadSet.isTop()) {
          blockOrPredecessorMaybeReadAnyField = true;
          break;
        }
        assert predecessorReadSet.isConcreteFieldSet();
        if (!knownReadSet.isConcreteFieldSet()) {
          knownReadSet = new ConcreteMutableFieldSet();
        }
        knownReadSet.asConcreteFieldSet().addAll(predecessorReadSet.asConcreteFieldSet());
      }

      if (!blockOrPredecessorMaybeReadAnyField) {
        // Finally, we update the read set with the fields that are read by the instructions in the
        // current block.
        for (Instruction instruction : block.getInstructions()) {
          AbstractFieldSet instructionReadSet = instruction.readSet(appView, context);
          if (instructionReadSet.isBottom()) {
            continue;
          }
          if (instructionReadSet.isTop()) {
            blockOrPredecessorMaybeReadAnyField = true;
            break;
          }
          if (!knownReadSet.isConcreteFieldSet()) {
            knownReadSet = new ConcreteMutableFieldSet();
          }
          knownReadSet.asConcreteFieldSet().addAll(instructionReadSet.asConcreteFieldSet());
        }
      }

      boolean changed = false;
      if (blockOrPredecessorMaybeReadAnyField) {
        // Record that this block reads all fields.
        result.put(block, UnknownFieldSet.getInstance());
        changed = true;
      } else if (knownReadSet.size() != oldSize) {
        assert knownReadSet.size() > oldSize;
        changed = true;
      }

      if (changed) {
        // Rerun the analysis for all successors because the state of the current block changed.
        worklist.addAll(block.getSuccessors());
      }
    }
    return result;
  }

  private void updateFieldOptimizationInfo(DexEncodedField field, Value value) {
    TypeLatticeElement fieldType =
        TypeLatticeElement.fromDexType(field.field.type, Nullability.maybeNull(), appView);
    TypeLatticeElement valueType = value.getTypeLattice();
    if (valueType.strictlyLessThan(fieldType, appView)) {
      feedback.markFieldHasDynamicType(field, valueType);
    }
  }
}