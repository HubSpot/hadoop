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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import org.apache.hadoop.hdfs.DFSTestUtil;
import org.apache.hadoop.hdfs.protocol.Block;
import org.apache.hadoop.hdfs.protocol.DatanodeInfo;
import org.apache.hadoop.hdfs.protocol.ErasureCodingPolicy;
import org.apache.hadoop.hdfs.protocol.SystemErasureCodingPolicies;
import org.apache.hadoop.hdfs.server.blockmanagement.DatanodeDescriptor.BlockTargetPair;
import org.apache.hadoop.net.NetworkTopology;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests how {@link ErasureCodingWork} turns chosen targets into copies when a
 * block group has every internal block live but is unsafely placed.
 */
public class TestErasureCodingWorkPlacement {

  // RS-6-3: 9 internal blocks, at most 3 may be lost.
  private static final ErasureCodingPolicy RS_6_3 =
      SystemErasureCodingPolicies.getByID(
          SystemErasureCodingPolicies.RS_6_3_POLICY_ID);
  private static final long BLOCK_ID = -9223372036854775792L;

  private NetworkTopology topology;
  private BlockPlacementPolicyErasureCoding policy;
  private BlockInfoStriped block;
  private int nextHost;

  /** Live storages and the internal-block index each holds. */
  private final List<DatanodeStorageInfo> live = new ArrayList<>();
  private final List<Byte> liveIndices = new ArrayList<>();
  /** Sources handed to the work (live plus leaving-service). */
  private final List<DatanodeDescriptor> sources = new ArrayList<>();
  private final List<Byte> sourceIndices = new ArrayList<>();

  @Before
  public void setup() {
    topology = new NetworkTopology();
    policy = new BlockPlacementPolicyErasureCoding();
    policy.clusterMap = topology;
    block = new BlockInfoStriped(new Block(BLOCK_ID,
        (long) RS_6_3.getCellSize() * RS_6_3.getNumDataUnits(), 1), RS_6_3);
    // A block with no owning file counts as deleted and is never reconstructed.
    block.setBlockCollectionId(1001L);
    nextHost = 0;
    live.clear();
    liveIndices.clear();
    sources.clear();
    sourceIndices.clear();
  }

  private DatanodeStorageInfo newStorage(String rack) {
    nextHost++;
    DatanodeStorageInfo storage = DFSTestUtil.createDatanodeStorageInfo(
        "s" + nextHost, "10.0.0." + nextHost, rack, "host" + nextHost);
    topology.add(storage.getDatanodeDescriptor());
    return storage;
  }

  private void placeLive(String rack, int... indices) {
    for (int index : indices) {
      DatanodeStorageInfo storage = newStorage(rack);
      storage.addBlock(block, new Block(BLOCK_ID + index, 0, 1));
      live.add(storage);
      liveIndices.add((byte) index);
      sources.add(storage.getDatanodeDescriptor());
      sourceIndices.add((byte) index);
    }
  }

  private void placeDecommissioning(String rack, int index) {
    DatanodeStorageInfo storage = newStorage(rack);
    storage.addBlock(block, new Block(BLOCK_ID + index, 0, 1));
    storage.getDatanodeDescriptor().startDecommission();
    sources.add(storage.getDatanodeDescriptor());
    sourceIndices.add((byte) index);
  }

  private DatanodeStorageInfo[] targets(String... racks) {
    DatanodeStorageInfo[] targets = new DatanodeStorageInfo[racks.length];
    for (int i = 0; i < racks.length; i++) {
      targets[i] = newStorage(racks[i]);
    }
    return targets;
  }

  private ErasureCodingWork newWork(BlockPlacementPolicy placementPolicy,
      DatanodeStorageInfo[] chosen) {
    BlockCollection bc = mock(BlockCollection.class);
    when(bc.getName()).thenReturn("/file");
    when(bc.getStoragePolicyID()).thenReturn(BlockStoragePolicySuite
        .createDefaultSuite().getDefaultPolicy().getId());
    byte[] indices = new byte[sourceIndices.size()];
    for (int i = 0; i < indices.length; i++) {
      indices[i] = sourceIndices.get(i);
    }
    List<DatanodeDescriptor> containing = new ArrayList<>(sources);
    ErasureCodingWork work = new ErasureCodingWork("bp", block, bc,
        sources.toArray(new DatanodeDescriptor[0]), containing,
        new ArrayList<>(live), chosen.length, 0, indices, new byte[0],
        new byte[0]);
    when(placementPolicy.chooseTarget(anyString(), anyInt(), any(),
        any(), anyBoolean(), any(), anyLong(), any(), any()))
        .thenReturn(chosen);
    work.chooseTargets(placementPolicy,
        BlockStoragePolicySuite.createDefaultSuite(), new HashSet<>());
    work.setNotEnoughRack();
    return work;
  }

  /** The copies scheduled on every source, as (index, target) pairs. */
  private List<Object[]> scheduledCopies() {
    List<Object[]> copies = new ArrayList<>();
    for (DatanodeDescriptor source : new HashSet<>(sources)) {
      List<BlockTargetPair> pairs = source.getECReplicatedCommand(100);
      if (pairs == null) {
        continue;
      }
      for (BlockTargetPair pair : pairs) {
        byte index = (byte) (pair.block.getBlockId() - BLOCK_ID);
        for (DatanodeStorageInfo target : pair.targets) {
          copies.add(new Object[] {index, target});
        }
      }
    }
    return copies;
  }

  private BlockPlacementStatus placementAfter(List<Object[]> copies) {
    List<DatanodeInfo> locs = new ArrayList<>();
    List<Byte> indices = new ArrayList<>();
    for (int i = 0; i < live.size(); i++) {
      locs.add(live.get(i).getDatanodeDescriptor());
      indices.add(liveIndices.get(i));
    }
    for (Object[] copy : copies) {
      locs.add(((DatanodeStorageInfo) copy[1]).getDatanodeDescriptor());
      indices.add((Byte) copy[0]);
    }
    byte[] idx = new byte[indices.size()];
    for (int i = 0; i < idx.length; i++) {
      idx[i] = indices.get(i);
    }
    return policy.verifyBlockPlacement(locs.toArray(new DatanodeInfo[0]), idx,
        block.getRealTotalBlockNum(), block.getParityBlockNum());
  }

  /**
   * The production case: the only replica in /a was on a decommissioning node
   * (its index is already live in /b), leaving /b holding 7 sole copies and
   * /a holding none. Every chosen target must receive a copy that fixes the
   * placement, and together they must make it safe.
   */
  @Test
  public void testEveryTargetRelievesTheOverBudgetDomain() {
    placeLive("/c", 0, 1);
    placeLive("/b", 2, 3, 4, 5, 6, 7, 8);
    placeDecommissioning("/a", 2);

    BlockPlacementStatus before = placementAfter(Collections.emptyList());
    assertFalse(before.isPlacementPolicySatisfied());
    // 4 copies out of /b; occupying /a is covered by those copies.
    assertEquals(4, before.getAdditionalReplicasRequired());

    DatanodeStorageInfo[] chosen = targets("/c", "/a", "/a", "/c");
    ErasureCodingWork work = newWork(
        mock(BlockPlacementPolicyErasureCoding.class), chosen);
    work.addTaskToDatanode(new NumberReplicas());

    List<Object[]> copies = scheduledCopies();
    assertEquals("every chosen target should get a copy", 4, copies.size());
    assertEquals(4, work.getTargets().length);
    for (Object[] copy : copies) {
      byte index = (Byte) copy[0];
      assertTrue("copy " + index + " should come out of /b",
          index >= 2 && index <= 8);
    }
    assertEquals(4, new HashSet<>(indicesOf(copies)).size());
    assertTrue(placementAfter(copies).isPlacementPolicySatisfied());
  }

  /**
   * /a is full (3 sole copies), /b is over budget by 3, /c holds nothing. The
   * relieving copies must all go to /c, which has room for exactly three moved
   * internal blocks, even though /a targets are offered first. Copies into /a
   * could never become moves and would stay as permanent over-replication.
   */
  @Test
  public void testRelievingCopiesPreferDomainsWithRoom() {
    placeLive("/a", 0, 1, 2);
    placeLive("/b", 3, 4, 5, 6, 7, 8);

    DatanodeStorageInfo[] chosen = targets("/a", "/a", "/c", "/c", "/c");
    ErasureCodingWork work = newWork(
        mock(BlockPlacementPolicyErasureCoding.class), chosen);
    work.addTaskToDatanode(new NumberReplicas());

    List<Object[]> copies = scheduledCopies();
    assertEquals(3, copies.size());
    for (Object[] copy : copies) {
      assertEquals("/c", ((DatanodeStorageInfo) copy[1])
          .getDatanodeDescriptor().getNetworkLocation());
      byte index = (Byte) copy[0];
      assertTrue("copy " + index + " should come out of /b", index >= 3);
    }
    assertTrue(placementAfter(copies).isPlacementPolicySatisfied());
  }

  /**
   * A target that cannot improve placement (its domain is already occupied and
   * no other domain is over budget once earlier copies are counted) is left
   * unused, so pending reconstruction does not wait for a copy that was never
   * requested.
   */
  @Test
  public void testUselessTargetsAreTrimmed() {
    placeLive("/a", 0, 1, 2);
    placeLive("/b", 3, 4, 5, 6);
    placeLive("/c", 7, 8);

    DatanodeStorageInfo[] chosen = targets("/c", "/c", "/a");
    ErasureCodingWork work = newWork(
        mock(BlockPlacementPolicyErasureCoding.class), chosen);
    work.addTaskToDatanode(new NumberReplicas());

    List<Object[]> copies = scheduledCopies();
    assertEquals(1, copies.size());
    assertEquals(1, work.getTargets().length);
    assertTrue(placementAfter(copies).isPlacementPolicySatisfied());
  }

  private static List<Byte> indicesOf(List<Object[]> copies) {
    List<Byte> indices = new ArrayList<>();
    for (Object[] copy : copies) {
      indices.add((Byte) copy[0]);
    }
    return indices;
  }
}
