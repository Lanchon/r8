// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.conversion;

import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.android.tools.r8.ir.conversion.CallGraph.Node;
import com.android.tools.r8.ir.conversion.CallGraphBuilderBase.CycleEliminator;
import com.android.tools.r8.utils.InternalOptions;
import com.google.common.collect.Sets;
import java.util.Set;
import java.util.TreeSet;
import org.junit.Test;

public class NodeExtractionTest extends CallGraphTestBase {

  private InternalOptions options = new InternalOptions();

  // Note that building a test graph is intentionally repeated to avoid race conditions and/or
  // non-deterministic test results due to cycle elimination.

  @Test
  public void testExtractLeaves_withoutCycle() {
    Node n1, n2, n3, n4, n5, n6;
    Set<Node> nodes;

    n1 = createNode("n1");
    n2 = createNode("n2");
    n3 = createNode("n3");
    n4 = createNode("n4");
    n5 = createNode("n5");
    n6 = createNode("n6");

    n2.addCallerConcurrently(n1);
    n3.addCallerConcurrently(n2);
    n4.addCallerConcurrently(n2);
    n4.addCallerConcurrently(n5);
    n6.addCallerConcurrently(n5);

    nodes = new TreeSet<>();
    nodes.add(n1);
    nodes.add(n2);
    nodes.add(n3);
    nodes.add(n4);
    nodes.add(n5);
    nodes.add(n6);

    Set<Node> wave = Sets.newIdentityHashSet();

    PrimaryMethodProcessor.extractLeaves(nodes, wave::add);
    assertEquals(3, wave.size());
    assertThat(wave, hasItem(n3));
    assertThat(wave, hasItem(n4));
    assertThat(wave, hasItem(n6));
    wave.clear();

    PrimaryMethodProcessor.extractLeaves(nodes, wave::add);
    assertEquals(2, wave.size());
    assertThat(wave, hasItem(n2));
    assertThat(wave, hasItem(n5));
    wave.clear();

    PrimaryMethodProcessor.extractLeaves(nodes, wave::add);
    assertEquals(1, wave.size());
    assertThat(wave, hasItem(n1));
    assertTrue(nodes.isEmpty());
  }

  @Test
  public void testExtractLeaves_withCycle() {
    Node n1, n2, n3, n4, n5, n6;
    Set<Node> nodes;

    n1 = createNode("n1");
    n2 = createNode("n2");
    n3 = createNode("n3");
    n4 = createNode("n4");
    n5 = createNode("n5");
    n6 = createNode("n6");

    n2.addCallerConcurrently(n1);
    n3.addCallerConcurrently(n2);
    n4.addCallerConcurrently(n2);
    n4.addCallerConcurrently(n5);
    n6.addCallerConcurrently(n5);

    nodes = new TreeSet<>();
    nodes.add(n1);
    nodes.add(n2);
    nodes.add(n3);
    nodes.add(n4);
    nodes.add(n5);
    nodes.add(n6);

    n1.addCallerConcurrently(n3);
    n3.method.getMutableOptimizationInfo().markForceInline();
    CycleEliminator cycleEliminator = new CycleEliminator(nodes, options);
    assertEquals(1, cycleEliminator.breakCycles().numberOfRemovedEdges());

    Set<Node> wave = Sets.newIdentityHashSet();

    PrimaryMethodProcessor.extractLeaves(nodes, wave::add);
    assertEquals(3, wave.size());
    assertThat(wave, hasItem(n3));
    assertThat(wave, hasItem(n4));
    assertThat(wave, hasItem(n6));
    wave.clear();

    PrimaryMethodProcessor.extractLeaves(nodes, wave::add);
    assertEquals(2, wave.size());
    assertThat(wave, hasItem(n2));
    assertThat(wave, hasItem(n5));
    wave.clear();

    PrimaryMethodProcessor.extractLeaves(nodes, wave::add);
    assertEquals(1, wave.size());
    assertThat(wave, hasItem(n1));
    assertTrue(nodes.isEmpty());
  }

  @Test
  public void testExtractRoots_withoutCycle() {
    Node n1, n2, n3, n4, n5, n6;
    Set<Node> nodes;

    n1 = createNode("n1");
    n2 = createNode("n2");
    n3 = createNode("n3");
    n4 = createNode("n4");
    n5 = createNode("n5");
    n6 = createNode("n6");

    n2.addCallerConcurrently(n1);
    n3.addCallerConcurrently(n2);
    n4.addCallerConcurrently(n2);
    n4.addCallerConcurrently(n5);
    n6.addCallerConcurrently(n5);

    nodes = new TreeSet<>();
    nodes.add(n1);
    nodes.add(n2);
    nodes.add(n3);
    nodes.add(n4);
    nodes.add(n5);
    nodes.add(n6);

    Set<Node> wave = Sets.newIdentityHashSet();

    PostMethodProcessor.extractRoots(nodes, wave::add);
    assertEquals(2, wave.size());
    assertThat(wave, hasItem(n1));
    assertThat(wave, hasItem(n5));
    wave.clear();

    PostMethodProcessor.extractRoots(nodes, wave::add);
    assertEquals(2, wave.size());
    assertThat(wave, hasItem(n2));
    assertThat(wave, hasItem(n6));
    wave.clear();

    PostMethodProcessor.extractRoots(nodes, wave::add);
    assertEquals(2, wave.size());
    assertThat(wave, hasItem(n3));
    assertThat(wave, hasItem(n4));
    assertTrue(nodes.isEmpty());
  }

  @Test
  public void testExtractRoots_withCycle() {
    Node n1, n2, n3, n4, n5, n6;
    Set<Node> nodes;

    n1 = createNode("n1");
    n2 = createNode("n2");
    n3 = createNode("n3");
    n4 = createNode("n4");
    n5 = createNode("n5");
    n6 = createNode("n6");

    n2.addCallerConcurrently(n1);
    n3.addCallerConcurrently(n2);
    n4.addCallerConcurrently(n2);
    n4.addCallerConcurrently(n5);
    n6.addCallerConcurrently(n5);

    nodes = new TreeSet<>();
    nodes.add(n1);
    nodes.add(n2);
    nodes.add(n3);
    nodes.add(n4);
    nodes.add(n5);
    nodes.add(n6);

    n1.addCallerConcurrently(n3);
    n3.method.getMutableOptimizationInfo().markForceInline();
    CycleEliminator cycleEliminator = new CycleEliminator(nodes, options);
    assertEquals(1, cycleEliminator.breakCycles().numberOfRemovedEdges());

    Set<Node> wave = Sets.newIdentityHashSet();

    PostMethodProcessor.extractRoots(nodes, wave::add);
    assertEquals(2, wave.size());
    assertThat(wave, hasItem(n1));
    assertThat(wave, hasItem(n5));
    wave.clear();

    PostMethodProcessor.extractRoots(nodes, wave::add);
    assertEquals(2, wave.size());
    assertThat(wave, hasItem(n2));
    assertThat(wave, hasItem(n6));
    wave.clear();

    PostMethodProcessor.extractRoots(nodes, wave::add);
    assertEquals(2, wave.size());
    assertThat(wave, hasItem(n3));
    assertThat(wave, hasItem(n4));
    assertTrue(nodes.isEmpty());
  }
}
