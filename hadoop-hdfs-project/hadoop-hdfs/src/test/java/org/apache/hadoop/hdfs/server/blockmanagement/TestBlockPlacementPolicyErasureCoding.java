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
package org.apache.hadoop.hdfs.server.blockmanagement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.apache.hadoop.hdfs.DFSTestUtil;
import org.apache.hadoop.hdfs.protocol.DatanodeInfo;
import org.apache.hadoop.net.NetworkTopology;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests the index-aware {@code verifyBlockPlacement} of
 * {@link BlockPlacementPolicyErasureCoding}. The key property is that placement
 * is judged safe only when no single failure domain holds more than {@code k}
 * internal blocks that exist nowhere else — unlike the rack-count check, which
 * reports a two-rack RS-6-3 group as satisfied even though one rack loss
 * destroys it.
 */
public class TestBlockPlacementPolicyErasureCoding {

  // RS-6-3.
  private static final int DATA_UNITS = 6;
  private static final int PARITY_UNITS = 3;

  private BlockPlacementPolicyErasureCoding policy;
  private NetworkTopology topology;
  private int nextHost;

  @Before
  public void setup() {
    topology = new NetworkTopology();
    policy = new BlockPlacementPolicyErasureCoding();
    policy.clusterMap = topology;
    nextHost = 0;
  }

  /**
   * Collects a placement (locations + the internal-block index each one holds)
   * for a block group as it is built up across failure domains.
   */
  private final class Placement {
    private final List<DatanodeInfo> locs = new ArrayList<>();
    private final List<Byte> indices = new ArrayList<>();

    /** Place each of {@code blockIndices} on a distinct node in {@code rack}. */
    Placement on(String rack, int... blockIndices) {
      for (int index : blockIndices) {
        DatanodeInfo dn = newNode(rack);
        locs.add(dn);
        indices.add((byte) index);
      }
      return this;
    }

    /** Add {@code count} proposed extra copies (unknown index) in {@code rack}. */
    Placement relief(String rack, int count) {
      for (int i = 0; i < count; i++) {
        locs.add(newNode(rack));
        indices.add((byte) -1);
      }
      return this;
    }

    BlockPlacementStatus verify(int totalInternalBlocks, int maxLossesPerDomain) {
      byte[] idx = new byte[indices.size()];
      for (int i = 0; i < idx.length; i++) {
        idx[i] = indices.get(i);
      }
      return policy.verifyBlockPlacement(
          locs.toArray(new DatanodeInfo[0]), idx, totalInternalBlocks,
          maxLossesPerDomain);
    }
  }

  private DatanodeInfo newNode(String rack) {
    DatanodeDescriptor dn = DFSTestUtil.getDatanodeDescriptor(
        "1.2.3." + (++nextHost), rack);
    // Register the node so the topology records that the cluster spans multiple
    // failure domains; the EC verify short-circuits to "satisfied" otherwise.
    topology.add(dn);
    return dn;
  }

  @Test
  public void testEvenlySpreadAcrossEnoughRacksIsSafe() {
    BlockPlacementStatus status = new Placement()
        .on("/r1", 0, 1, 2)
        .on("/r2", 3, 4, 5)
        .on("/r3", 6, 7, 8)
        .verify(DATA_UNITS + PARITY_UNITS, PARITY_UNITS);
    assertTrue(status.isPlacementPolicySatisfied());
    assertEquals(0, status.getAdditionalReplicasRequired());
  }

  @Test
  public void testOneInternalBlockPerRackIsSafe() {
    BlockPlacementStatus status = new Placement()
        .on("/r1", 0).on("/r2", 1).on("/r3", 2).on("/r4", 3).on("/r5", 4)
        .on("/r6", 5).on("/r7", 6).on("/r8", 7).on("/r9", 8)
        .verify(DATA_UNITS + PARITY_UNITS, PARITY_UNITS);
    assertTrue(status.isPlacementPolicySatisfied());
    assertEquals(0, status.getAdditionalReplicasRequired());
  }

  @Test
  public void testTwoRackSplitIsReportedUnsafe() {
    // This is the case the rack-count policy wrongly reports as satisfied.
    BlockPlacementStatus status = new Placement()
        .on("/r1", 0, 1, 2, 3, 4)
        .on("/r2", 5, 6, 7, 8)
        .verify(DATA_UNITS + PARITY_UNITS, PARITY_UNITS);
    assertFalse(status.isPlacementPolicySatisfied());
    assertEquals(3, status.getAdditionalReplicasRequired());
  }

  @Test
  public void testOverReplicationAcrossTwoRacksBecomesSafe() {
    // Indices 3,4,5 are duplicated onto both racks, so each rack now holds only
    // three sole-copy internal blocks.
    BlockPlacementStatus status = new Placement()
        .on("/r1", 0, 1, 2, 3, 4, 5)
        .on("/r2", 3, 4, 5, 6, 7, 8)
        .verify(DATA_UNITS + PARITY_UNITS, PARITY_UNITS);
    assertTrue(status.isPlacementPolicySatisfied());
    assertEquals(0, status.getAdditionalReplicasRequired());
  }

  @Test
  public void testProposedTargetsCreditedAsRelief() {
    BlockPlacementStatus status = new Placement()
        .on("/r1", 0, 1, 2, 3, 4)
        .on("/r2", 5, 6, 7, 8)
        .relief("/r3", 3)
        .verify(DATA_UNITS + PARITY_UNITS, PARITY_UNITS);
    assertTrue(status.isPlacementPolicySatisfied());
    assertEquals(0, status.getAdditionalReplicasRequired());
  }

  @Test
  public void testDataAndParityTreatedSymmetrically() {
    // Four data blocks (0-3) alone on one rack is exactly as unsafe as four
    // parity-heavy blocks would be: it is the count, not the kind, that matters.
    BlockPlacementStatus status = new Placement()
        .on("/r1", 0, 1, 2, 3)
        .on("/r2", 4, 5)
        .on("/r3", 6, 7, 8)
        .verify(DATA_UNITS + PARITY_UNITS, PARITY_UNITS);
    assertFalse(status.isPlacementPolicySatisfied());
    assertEquals(1, status.getAdditionalReplicasRequired());
  }

  @Test
  public void testRedundantCopyThatProtectsNoDomainIsNotNeeded() {
    // /r1 is over budget (4 sole, budget 3). Index 4 is duplicated within /r2,
    // which protects no domain. Removing that redundant copy must not increase
    // the number of extra copies required, so excess handling is free to
    // reclaim it (to free capacity for the real fix on /r1).
    int withCopy = new Placement()
        .on("/r1", 0, 1, 2, 3)
        .on("/r2", 4, 5, 4) // index 4 duplicated within the same rack
        .on("/r3", 6, 7, 8)
        .verify(DATA_UNITS + PARITY_UNITS, PARITY_UNITS)
        .getAdditionalReplicasRequired();
    int withoutCopy = new Placement()
        .on("/r1", 0, 1, 2, 3)
        .on("/r2", 4, 5)    // the redundant copy of index 4 removed
        .on("/r3", 6, 7, 8)
        .verify(DATA_UNITS + PARITY_UNITS, PARITY_UNITS)
        .getAdditionalReplicasRequired();
    assertEquals(1, withCopy);
    assertEquals("Removing a copy that protects no domain must not raise the "
        + "requirement", withCopy, withoutCopy);
  }

  @Test
  public void testRedundantCopyThatRelievesADomainIsNeeded() {
    // Index 0 is duplicated from /r1 onto /r2, keeping /r1 within budget (3
    // sole). Removing that copy pushes /r1 to 4 sole, so it must be kept.
    int withCopy = new Placement()
        .on("/r1", 0, 1, 2, 3)
        .on("/r2", 4, 5, 0) // index 0 duplicated onto another rack -> relieves /r1
        .on("/r3", 6, 7, 8)
        .verify(DATA_UNITS + PARITY_UNITS, PARITY_UNITS)
        .getAdditionalReplicasRequired();
    int withoutCopy = new Placement()
        .on("/r1", 0, 1, 2, 3)
        .on("/r2", 4, 5)    // the relieving copy of index 0 removed
        .on("/r3", 6, 7, 8)
        .verify(DATA_UNITS + PARITY_UNITS, PARITY_UNITS)
        .getAdditionalReplicasRequired();
    assertEquals(0, withCopy);
    assertTrue("Removing a copy that relieves an over-concentrated domain must "
        + "raise the requirement", withoutCopy > withCopy);
  }

  @Test
  public void testPartialLastStripe() {
    // A group whose real data width is 2 with 2 parity tolerates 2 losses.
    BlockPlacementStatus safe = new Placement()
        .on("/r1", 0, 1)
        .on("/r2", 2, 3)
        .verify(4, 2);
    assertTrue(safe.isPlacementPolicySatisfied());

    BlockPlacementStatus unsafe = new Placement()
        .on("/r1", 0, 1, 2)
        .on("/r2", 3)
        .verify(4, 2);
    assertFalse(unsafe.isPlacementPolicySatisfied());
    assertEquals(1, unsafe.getAdditionalReplicasRequired());
  }

  @Test
  public void testSingleRackClusterTreatedAsSatisfied() {
    NetworkTopology singleRack = new NetworkTopology();
    BlockPlacementPolicyErasureCoding singleRackPolicy =
        new BlockPlacementPolicyErasureCoding();
    singleRackPolicy.clusterMap = singleRack;

    List<DatanodeInfo> locs = new ArrayList<>();
    List<Byte> indices = new ArrayList<>();
    for (int i = 0; i < DATA_UNITS + PARITY_UNITS; i++) {
      DatanodeInfo dn = DFSTestUtil.getDatanodeDescriptor(
          "1.2.3." + (++nextHost), "/onlyrack");
      singleRack.add((DatanodeDescriptor) dn);
      locs.add(dn);
      indices.add((byte) i);
    }
    byte[] idx = new byte[indices.size()];
    for (int i = 0; i < idx.length; i++) {
      idx[i] = indices.get(i);
    }
    BlockPlacementStatus status = singleRackPolicy.verifyBlockPlacement(
        locs.toArray(new DatanodeInfo[0]), idx, DATA_UNITS + PARITY_UNITS,
        PARITY_UNITS);
    assertTrue(status.isPlacementPolicySatisfied());
  }
}
