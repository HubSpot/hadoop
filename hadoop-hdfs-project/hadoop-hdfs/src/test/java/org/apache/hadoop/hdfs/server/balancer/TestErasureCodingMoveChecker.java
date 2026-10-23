/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hdfs.server.balancer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.ArrayList;
import java.util.List;

import org.apache.hadoop.hdfs.DFSTestUtil;
import org.apache.hadoop.hdfs.HdfsConfiguration;
import org.apache.hadoop.hdfs.server.blockmanagement.DatanodeDescriptor;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests that {@link ErasureCodingMoveChecker} allows exactly the moves of
 * erasure-coded internal blocks whose source copy the NameNode will delete,
 * i.e. those that leave the group no less safe.
 */
public class TestErasureCodingMoveChecker {

  // RS-6-3.
  private static final int TOTAL = 9;
  private static final int PARITY = 3;

  private ErasureCodingMoveChecker checker;
  private int nextHost;
  private final List<DatanodeDescriptor> nodes = new ArrayList<>();
  private final List<Byte> indices = new ArrayList<>();

  @Before
  public void setup() {
    checker = new ErasureCodingMoveChecker(new HdfsConfiguration());
    nextHost = 0;
    nodes.clear();
    indices.clear();
  }

  private DatanodeDescriptor newNode(String rack) {
    return DFSTestUtil.getDatanodeDescriptor("1.2.3." + (++nextHost), rack);
  }

  /** Place each of {@code blockIndices} on a new node in {@code rack}. */
  private void on(String rack, int... blockIndices) {
    for (int index : blockIndices) {
      copy(newNode(rack), index);
    }
  }

  private DatanodeDescriptor copy(DatanodeDescriptor dn, int index) {
    nodes.add(dn);
    indices.add((byte) index);
    return dn;
  }

  /** The node holding the first copy of internal block {@code index}. */
  private DatanodeDescriptor holderOf(int index) {
    return nodes.get(indices.indexOf((byte) index));
  }

  /** Register every placement node and {@code extra} with the checker. */
  private void register(DatanodeDescriptor... extra) {
    for (DatanodeDescriptor dn : nodes) {
      checker.addDatanode(dn);
    }
    for (DatanodeDescriptor dn : extra) {
      checker.addDatanode(dn);
    }
  }

  private Boolean isMovable(DatanodeDescriptor source,
      DatanodeDescriptor target) {
    final String[] uuids = new String[nodes.size()];
    final byte[] idx = new byte[indices.size()];
    for (int i = 0; i < uuids.length; i++) {
      uuids[i] = nodes.get(i).getDatanodeUuid();
      idx[i] = indices.get(i);
    }
    return checker.isMovable(uuids, idx, TOTAL, PARITY, source, target);
  }

  /**
   * A 3/3/3 group must not be moved onto a rack that already holds 3 sole
   * copies: the rack-count check allows it, but the NameNode reverts it.
   */
  @Test
  public void testRejectsMoveThatOverConcentratesADomain() {
    on("/a", 0, 1, 2);
    on("/b", 3, 4, 5);
    on("/c", 6, 7, 8);
    final DatanodeDescriptor newInA = newNode("/a");
    register(newInA);
    assertEquals(false, isMovable(holderOf(3), newInA));
  }

  @Test
  public void testAllowsMoveWithinADomain() {
    on("/a", 0, 1, 2);
    on("/b", 3, 4, 5);
    on("/c", 6, 7, 8);
    final DatanodeDescriptor newInB = newNode("/b");
    register(newInB);
    assertEquals(true, isMovable(holderOf(3), newInB));
  }

  @Test
  public void testAllowsMoveOntoANewDomain() {
    on("/a", 0, 1, 2);
    on("/b", 3, 4, 5);
    on("/c", 6, 7, 8);
    final DatanodeDescriptor inD = newNode("/d");
    register(inD);
    assertEquals(true, isMovable(holderOf(0), inD));
  }

  /** A move that fixes an over-concentrated domain is allowed. */
  @Test
  public void testAllowsMoveThatRelievesADomain() {
    on("/a", 0, 1, 2, 3);
    on("/b", 4, 5);
    on("/c", 6, 7, 8);
    final DatanodeDescriptor newInB = newNode("/b");
    register(newInB);
    assertEquals(true, isMovable(holderOf(3), newInB));
  }

  /**
   * A relief copy that keeps a domain within budget may move to another
   * domain, but not into the domain it protects.
   */
  @Test
  public void testRelief() {
    on("/a", 0, 1, 2, 3);
    final DatanodeDescriptor relief = copy(newNode("/b"), 3);
    on("/b", 4, 5);
    on("/c", 6, 7, 8);
    final DatanodeDescriptor newInA = newNode("/a");
    final DatanodeDescriptor newInC = newNode("/c");
    register(newInA, newInC);
    assertEquals(false, isMovable(relief, newInA));
    assertEquals(true, isMovable(relief, newInC));
  }

  /**
   * A copy on a decommissioning node does not protect the group for the
   * NameNode, so it must not make an otherwise unsafe move look safe.
   */
  @Test
  public void testIgnoresCopiesOnDecommissioningNodes() {
    on("/a", 0, 1, 2);
    on("/b", 3, 4, 5);
    on("/c", 6, 7, 8);
    final DatanodeDescriptor decommissioning = copy(newNode("/c"), 3);
    decommissioning.startDecommission();
    final DatanodeDescriptor newInA = newNode("/a");
    register(newInA);
    assertEquals(false, isMovable(holderOf(3), newInA));
  }

  /**
   * A copy on an in-service node counts even if the Balancer ignores that node
   * (for example because it is excluded): the NameNode still counts it.
   */
  @Test
  public void testCountsCopiesOnInServiceNodes() {
    on("/a", 0, 1, 2);
    on("/b", 3, 4, 5);
    on("/c", 6, 7, 8);
    copy(newNode("/c"), 3);
    final DatanodeDescriptor newInA = newNode("/a");
    register(newInA);
    assertEquals(true, isMovable(holderOf(3), newInA));
  }

  @Test
  public void testUnknownSource() {
    on("/a", 0, 1, 2);
    on("/b", 3, 4, 5);
    on("/c", 6, 7, 8);
    final DatanodeDescriptor stranger = newNode("/a");
    final DatanodeDescriptor newInB = newNode("/b");
    register(stranger, newInB);
    assertNull(isMovable(stranger, newInB));
  }
}
