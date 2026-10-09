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
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.DFSTestUtil;
import org.apache.hadoop.hdfs.HdfsConfiguration;
import org.apache.hadoop.hdfs.protocol.Block;
import org.apache.hadoop.hdfs.protocol.DatanodeInfo;
import org.apache.hadoop.hdfs.protocol.ErasureCodingPolicy;
import org.apache.hadoop.hdfs.protocol.SystemErasureCodingPolicies;
import org.apache.hadoop.hdfs.server.common.HdfsServerConstants;
import org.apache.hadoop.hdfs.server.namenode.FSNamesystem;
import org.apache.hadoop.hdfs.server.namenode.INodeFile;
import org.apache.hadoop.hdfs.server.namenode.INodeId;
import org.apache.hadoop.hdfs.server.namenode.TestINodeFile;
import org.apache.hadoop.hdfs.server.namenode.ha.HAContext;
import org.apache.hadoop.hdfs.server.namenode.ha.HAState;
import org.apache.hadoop.net.NetworkTopology;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * Tests that reclaiming redundant internal blocks under {@link
 * BlockPlacementPolicyErasureCoding} never makes a block group less durable,
 * and that it reclaims every copy that does not protect the group.
 * <p>
 * The excess-redundancy path is driven through {@link
 * BlockManager#processMisReplicatedBlock}, the same path a NameNode runs over
 * every block on startup or failover.
 */
public class TestErasureCodingExcessRedundancy {

  private static final long BLOCK_SIZE = 64 * 1024 * 1024;
  private static final long CAPACITY =
      2 * HdfsServerConstants.MIN_BLOCKS_FOR_WRITE * BLOCK_SIZE * 100;

  private FSNamesystem fsn;
  private BlockManager bm;
  private NetworkTopology topology;
  private BlockPlacementPolicyErasureCoding policy;
  /** In-service nodes by rack. */
  private Map<String, List<DatanodeDescriptor>> inService;
  /** Decommissioning nodes by rack. */
  private Map<String, List<DatanodeDescriptor>> decommissioning;
  private Map<Long, INodeFile> blockCollections;
  private long nextBlockGroupId;
  private long nextInodeId;
  private int nextHost;

  private void newCluster(int numRacks, int inServicePerRack,
      int decommissioningPerRack) throws IOException {
    Configuration conf = new HdfsConfiguration();
    conf.set(DFSConfigKeys.NET_TOPOLOGY_SCRIPT_FILE_NAME_KEY,
        "need to set a dummy value here so it assumes a multi-rack cluster");
    fsn = Mockito.mock(FSNamesystem.class);
    Mockito.doReturn(true).when(fsn).hasWriteLock();
    Mockito.doReturn(true).when(fsn).hasReadLock();
    Mockito.doReturn(true).when(fsn).isRunning();
    HAContext haContext = Mockito.mock(HAContext.class);
    HAState haState = Mockito.mock(HAState.class);
    Mockito.when(haContext.getState()).thenReturn(haState);
    Mockito.when(haState.shouldPopulateReplQueues()).thenReturn(true);
    Mockito.when(fsn.getHAContext()).thenReturn(haContext);
    final Map<Long, INodeFile> inodes = new HashMap<>();
    blockCollections = inodes;
    Mockito.doAnswer(inv -> inodes.get((Long) inv.getArgument(0)))
        .when(fsn).getBlockCollection(Mockito.anyLong());
    bm = new BlockManager(fsn, false, conf);
    bm.setInitializedReplQueues(true);
    topology = bm.getDatanodeManager().getNetworkTopology();
    policy = bm.getStriptedBlockPlacementPolicy();

    inService = new TreeMap<>();
    decommissioning = new TreeMap<>();
    for (int r = 0; r < numRacks; r++) {
      String rack = "/rack" + r;
      inService.put(rack, new ArrayList<>());
      decommissioning.put(rack, new ArrayList<>());
      for (int i = 0; i < inServicePerRack; i++) {
        inService.get(rack).add(addNode(rack));
      }
      for (int i = 0; i < decommissioningPerRack; i++) {
        DatanodeDescriptor dn = addNode(rack);
        dn.startDecommission();
        decommissioning.get(rack).add(dn);
      }
    }
    nextBlockGroupId = Long.MIN_VALUE;
    nextInodeId = INodeId.ROOT_INODE_ID + 1;
  }

  private DatanodeDescriptor addNode(String rack) {
    nextHost++;
    String ip = (nextHost >> 16 & 255) + "." + (nextHost >> 8 & 255) + "."
        + (nextHost & 255) + ".1";
    DatanodeStorageInfo storage = DFSTestUtil.createDatanodeStorageInfo(
        "s" + nextHost, ip, rack, "host" + nextHost);
    DatanodeDescriptor dn = storage.getDatanodeDescriptor();
    topology.add(dn);
    dn.getStorageInfos()[0].setUtilizationForTesting(CAPACITY, 0L, CAPACITY,
        0L);
    dn.updateHeartbeat(BlockManagerTestUtil.getStorageReportsForDatanode(dn),
        0L, 0L, 0, 0, null);
    bm.getDatanodeManager().checkIfClusterIsNowMultiRack(dn);
    dn.getStorageInfos()[0].setBlockContentsStale(false);
    return dn;
  }

  private static void setRemaining(DatanodeDescriptor dn, long remaining) {
    dn.getStorageInfos()[0].setUtilizationForTesting(CAPACITY,
        CAPACITY - remaining, remaining, 0L);
  }

  /** One replica of an internal block. */
  private static final class Copy {
    final DatanodeDescriptor node;
    final int index;

    Copy(DatanodeDescriptor node, int index) {
      this.node = node;
      this.index = index;
    }

    String rack() {
      return node.getNetworkLocation();
    }

    @Override
    public String toString() {
      return index + "@" + rack() + (node.isInService() ? "" : "(decom)");
    }
  }

  private BlockInfoStriped newGroup(ErasureCodingPolicy ecPolicy,
      int realDataBlocks, List<Copy> copies) {
    long groupId = nextBlockGroupId;
    nextBlockGroupId += HdfsServerConstants.MAX_BLOCKS_IN_GROUP;
    long numBytes = (long) ecPolicy.getCellSize() * realDataBlocks;
    BlockInfoStriped sblk =
        new BlockInfoStriped(new Block(groupId, numBytes, 1), ecPolicy);
    for (Copy copy : copies) {
      copy.node.getStorageInfos()[0].addBlock(sblk,
          new Block(groupId + copy.index, 0, 1));
    }
    long inodeId = nextInodeId++;
    INodeFile bc = TestINodeFile.createINodeFile(inodeId);
    bm.blocksMap.addBlockCollection(sblk, bc);
    sblk.setBlockCollectionId(inodeId);
    blockCollections.put(inodeId, bc);
    assertTrue(bm.getBlockCollection(sblk) == bc);
    return sblk;
  }

  /** The replicas excess handling may consider: in service, not excess. */
  private List<Copy> liveCopies(BlockInfoStriped sblk) {
    List<Copy> live = new ArrayList<>();
    for (DatanodeStorageInfo storage : bm.blocksMap.getStorages(sblk)) {
      DatanodeDescriptor dn = storage.getDatanodeDescriptor();
      if (dn.isInService() && !bm.isExcess(dn, sblk)) {
        live.add(new Copy(dn, sblk.getStorageBlockIndex(storage)));
      }
    }
    return live;
  }

  private BlockPlacementStatusErasureCoding status(BlockInfoStriped sblk,
      List<Copy> copies) {
    DatanodeInfo[] locs = new DatanodeInfo[copies.size()];
    byte[] indices = new byte[copies.size()];
    for (int i = 0; i < copies.size(); i++) {
      locs[i] = copies.get(i).node;
      indices[i] = (byte) copies.get(i).index;
    }
    return policy.verifyBlockPlacement(locs, indices,
        sblk.getRealTotalBlockNum(), sblk.getParityBlockNum());
  }

  private static Set<Integer> indices(List<Copy> copies) {
    Set<Integer> set = new HashSet<>();
    for (Copy copy : copies) {
      set.add(copy.index);
    }
    return set;
  }

  /**
   * Independent of the placement policy: the number of distinct internal
   * blocks that survive the loss of {@code rack}.
   */
  private static int survivorsOfRackLoss(List<Copy> copies, String rack) {
    Set<Integer> survivors = new HashSet<>();
    for (Copy copy : copies) {
      if (!copy.rack().equals(rack)) {
        survivors.add(copy.index);
      }
    }
    return survivors.size();
  }

  private void reclaim(BlockInfoStriped sblk) {
    bm.processMisReplicatedBlock(sblk);
  }

  /**
   * Run excess handling on {@code sblk} and check every durability and
   * completeness invariant against its state beforehand.
   */
  private List<Copy> reclaimAndVerify(BlockInfoStriped sblk, String context) {
    final List<Copy> before = liveCopies(sblk);
    final List<Copy> allBefore = new ArrayList<>();
    for (DatanodeStorageInfo s : bm.blocksMap.getStorages(sblk)) {
      allBefore.add(new Copy(s.getDatanodeDescriptor(),
          sblk.getStorageBlockIndex(s)));
    }

    reclaim(sblk);
    final List<Copy> after = liveCopies(sblk);
    final String ctx = context + " before=" + before + " after=" + after;

    // Nothing other than an in-service copy may ever be marked excess.
    for (Copy copy : allBefore) {
      if (!copy.node.isInService()) {
        assertFalse("a leaving-service copy was marked excess: " + ctx,
            bm.isExcess(copy.node, sblk));
      }
    }
    // Only copies that were live beforehand can remain, and nothing is added.
    assertTrue(ctx, after.size() <= before.size());

    // 1. No internal block loses its last live copy.
    assertEquals("an internal block lost its last live copy: " + ctx,
        indices(before), indices(after));

    // 2. Never less safe by the policy's own measure.
    BlockPlacementStatusErasureCoding sBefore = status(sblk, before);
    BlockPlacementStatusErasureCoding sAfter = status(sblk, after);
    assertFalse("reclaiming made placement less safe: " + ctx,
        sAfter.isLessSafeThan(sBefore));
    if (sBefore.isPlacementPolicySatisfied()) {
      assertTrue("a satisfied group became unsatisfied: " + ctx,
          sAfter.isPlacementPolicySatisfied());
    }

    // 3. Policy-independent check: for every rack, if the group could be
    //    reconstructed after losing that rack beforehand, it still can.
    final int needed = sblk.getRealDataBlockNum();
    for (String rack : inService.keySet()) {
      if (survivorsOfRackLoss(before, rack) >= needed) {
        assertTrue("group no longer survives loss of " + rack + ": " + ctx,
            survivorsOfRackLoss(after, rack) >= needed);
      }
    }

    // 4. Nothing reclaimable is left behind: every remaining duplicate copy
    //    protects the group.
    for (int i = 0; i < after.size(); i++) {
      final int index = after.get(i).index;
      int copiesOfIndex = 0;
      for (Copy c : after) {
        if (c.index == index) {
          copiesOfIndex++;
        }
      }
      if (copiesOfIndex < 2) {
        continue;
      }
      List<Copy> without = new ArrayList<>(after);
      without.remove(i);
      assertTrue("a reclaimable copy " + after.get(i) + " was left: " + ctx,
          status(sblk, without).isLessSafeThan(sAfter));
    }

    // 5. Idempotent: a second pass reclaims nothing more.
    reclaim(sblk);
    assertEquals("a second pass reclaimed more: " + ctx, after.size(),
        liveCopies(sblk).size());
    return after;
  }

  private List<Copy> place(String rack, int... indices) {
    List<Copy> copies = new ArrayList<>();
    List<DatanodeDescriptor> nodes = inService.get(rack);
    for (int index : indices) {
      // Use a node not already holding a copy in this call's racks.
      copies.add(new Copy(nodes.get(index % nodes.size()), index));
    }
    return copies;
  }

  @Before
  public void setup() throws IOException {
    nextHost = 0;
    newCluster(3, 16, 2);
  }

  private static final ErasureCodingPolicy RS_6_3 =
      SystemErasureCodingPolicies.getByID(
          SystemErasureCodingPolicies.RS_6_3_POLICY_ID);

  /**
   * The production layout after decommission relief: /rack1 originally held
   * the sole copies of indices 2-8 and relief copies of 2,3 went to /rack0 and
   * 4,5 to /rack2. The relief copies sit on the fullest nodes, so a victim
   * chosen by free space alone would always be a relief copy. The originals
   * of 2, 3 and one of 4/5 must be reclaimed; the other copy of 4/5 protects
   * /rack2 and must stay.
   */
  @Test
  public void testReclaimsOriginalsWhenReliefCopiesAreOnFullerNodes() {
    List<Copy> copies = new ArrayList<>();
    copies.addAll(place("/rack2", 0, 1));
    copies.addAll(place("/rack1", 2, 3, 4, 5, 6, 7, 8));
    List<Copy> relief = new ArrayList<>();
    relief.add(new Copy(inService.get("/rack0").get(10), 2));
    relief.add(new Copy(inService.get("/rack0").get(11), 3));
    relief.add(new Copy(inService.get("/rack2").get(10), 4));
    relief.add(new Copy(inService.get("/rack2").get(11), 5));
    copies.addAll(relief);
    for (Copy c : relief) {
      setRemaining(c.node, CAPACITY / 100);
    }
    BlockInfoStriped sblk = newGroup(RS_6_3, 6, copies);

    List<Copy> after = reclaimAndVerify(sblk, "production");
    assertEquals("13 copies should reclaim to 10", 10, after.size());
    for (Copy c : relief.subList(0, 2)) {
      assertFalse("relief copy " + c + " was reclaimed",
          bm.isExcess(c.node, sblk));
    }
    Map<String, Integer> perRack = new HashMap<>();
    for (Copy c : after) {
      perRack.merge(c.rack(), 1, Integer::sum);
    }
    assertEquals(Integer.valueOf(2), perRack.get("/rack0"));
    assertEquals(Integer.valueOf(4), perRack.get("/rack1"));
    assertEquals(Integer.valueOf(4), perRack.get("/rack2"));
    assertTrue(status(sblk, after).isPlacementPolicySatisfied());
  }

  /** The same layout with relief copies on the emptiest nodes. */
  @Test
  public void testReclaimsOriginalsWhenReliefCopiesAreOnEmptierNodes() {
    List<Copy> copies = new ArrayList<>();
    copies.addAll(place("/rack2", 0, 1));
    copies.addAll(place("/rack1", 2, 3, 4, 5, 6, 7, 8));
    for (Copy c : new ArrayList<>(copies)) {
      setRemaining(c.node, CAPACITY / 100);
    }
    copies.add(new Copy(inService.get("/rack0").get(10), 2));
    copies.add(new Copy(inService.get("/rack0").get(11), 3));
    copies.add(new Copy(inService.get("/rack2").get(10), 4));
    copies.add(new Copy(inService.get("/rack2").get(11), 5));
    BlockInfoStriped sblk = newGroup(RS_6_3, 6, copies);

    assertEquals(10, reclaimAndVerify(sblk, "emptier").size());
  }

  /**
   * The layout reported from production: 12 copies split 3/6/3 with 3
   * duplicated internal blocks. Any such layout that is safely placed has a
   * reclaimable copy.
   */
  @Test
  public void testReported363LayoutIsReclaimed() {
    List<Copy> copies = new ArrayList<>();
    copies.addAll(place("/rack0", 0, 1, 6));
    copies.addAll(place("/rack1", 2, 3, 4, 5, 6, 7));
    copies.addAll(place("/rack2", 7, 8, 2));
    BlockInfoStriped sblk = newGroup(RS_6_3, 6, copies);
    assertTrue(status(sblk, liveCopies(sblk)).isPlacementPolicySatisfied());
    assertTrue(reclaimAndVerify(sblk, "3/6/3").size() < 12);
  }

  /** Two copies in the same rack protect nothing; exactly one is reclaimed. */
  @Test
  public void testSameRackDuplicateReclaimedButNeverTheLastCopy() {
    List<Copy> copies = new ArrayList<>();
    copies.addAll(place("/rack0", 0, 1, 2));
    copies.addAll(place("/rack1", 3, 4, 5));
    copies.addAll(place("/rack2", 6, 7, 8));
    copies.add(new Copy(inService.get("/rack1").get(12), 4));
    copies.add(new Copy(inService.get("/rack1").get(13), 4));
    BlockInfoStriped sblk = newGroup(RS_6_3, 6, copies);
    List<Copy> after = reclaimAndVerify(sblk, "same-rack");
    assertEquals(9, after.size());
  }

  /**
   * An index whose two copies each relieve a different over-budget rack must
   * keep both, and the group must not be touched.
   */
  @Test
  public void testFullyProtectiveLayoutIsLeftAlone() {
    // RS-6-3 on 2 racks of 6 replicas: 3,4,5 on both, so each rack holds only
    // 3 sole copies. Removing any duplicate pushes a rack to 4.
    newClusterUnchecked(2);
    List<Copy> copies = new ArrayList<>();
    copies.addAll(place("/rack0", 0, 1, 2, 3, 4, 5));
    copies.addAll(place("/rack1", 3, 4, 5, 6, 7, 8));
    BlockInfoStriped sblk = newGroup(RS_6_3, 6, copies);
    assertEquals(12, reclaimAndVerify(sblk, "protective").size());
  }

  /**
   * Copies on decommissioning nodes neither count as protection nor get
   * reclaimed; reclaiming must keep the in-service copies the group relies on.
   */
  @Test
  public void testDecommissioningCopiesDoNotCountAsProtection() {
    List<Copy> copies = new ArrayList<>();
    copies.addAll(place("/rack0", 0, 1, 2));
    copies.addAll(place("/rack1", 3, 4, 5));
    copies.addAll(place("/rack2", 6, 7, 8));
    // Index 0 also on /rack1 (in service) and on a decommissioning /rack2 node.
    copies.add(new Copy(inService.get("/rack1").get(12), 0));
    copies.add(new Copy(decommissioning.get("/rack2").get(0), 0));
    BlockInfoStriped sblk = newGroup(RS_6_3, 6, copies);
    List<Copy> after = reclaimAndVerify(sblk, "decommissioning");
    assertEquals(9, after.size());
  }

  /**
   * Index 5 has a copy in every rack. Removing any single copy is safe, but
   * after one is removed the remaining two each protect a rack at its budget.
   * Safety must be re-checked after every removal, not decided once up front.
   */
  @Test
  public void testSafetyIsRecheckedAfterEachRemoval() {
    List<Copy> copies = new ArrayList<>();
    copies.addAll(place("/rack0", 0, 1, 2));
    copies.addAll(place("/rack1", 3, 4));
    copies.addAll(place("/rack2", 6, 7, 8));
    Copy on0 = new Copy(inService.get("/rack0").get(12), 5);
    Copy on1 = new Copy(inService.get("/rack1").get(12), 5);
    Copy on2 = new Copy(inService.get("/rack2").get(12), 5);
    copies.add(on0);
    copies.add(on1);
    copies.add(on2);
    // Free-space order makes the policy prefer on1, then on2, then on0.
    setRemaining(on1.node, CAPACITY / 1000);
    setRemaining(on2.node, CAPACITY / 100);
    setRemaining(on0.node, CAPACITY / 10);
    BlockInfoStriped sblk = newGroup(RS_6_3, 6, copies);

    List<Copy> after = reclaimAndVerify(sblk, "recheck");
    assertEquals(10, after.size());
    assertTrue(bm.isExcess(on1.node, sblk));
    assertFalse(bm.isExcess(on0.node, sblk));
    assertFalse(bm.isExcess(on2.node, sblk));
    assertTrue(status(sblk, after).isPlacementPolicySatisfied());
  }

  /** A partial last block group (fewer data blocks than the schema). */
  @Test
  public void testPartialBlockGroup() {
    // One data block plus 3 parity: indices 0, 6, 7, 8.
    List<Copy> copies = new ArrayList<>();
    copies.addAll(place("/rack0", 0, 6));
    copies.addAll(place("/rack1", 7, 0));
    copies.addAll(place("/rack2", 8, 6));
    BlockInfoStriped sblk = newGroup(RS_6_3, 1, copies);
    reclaimAndVerify(sblk, "partial");
  }

  private void newClusterUnchecked(int racks) {
    try {
      newCluster(racks, 16, 2);
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  /**
   * Randomized: many layouts across cluster shapes, EC schemas, partial
   * groups, duplicated copies (same and cross rack), decommissioning copies
   * and free-space orderings. Every reclaim must preserve every durability
   * invariant and leave nothing reclaimable.
   */
  @Test(timeout = 600000)
  public void testRandomizedLayoutsNeverLoseDurability() throws IOException {
    final long seed = Long.getLong("ec.excess.seed", System.nanoTime());
    final int trialsPerShape = Integer.getInteger("ec.excess.trials", 750);
    final Random random = new Random(seed);
    final List<ErasureCodingPolicy> schemas =
        new ArrayList<>(SystemErasureCodingPolicies.getPolicies());
    int groupsReclaimed = 0;
    int copiesReclaimed = 0;

    for (int numRacks = 2; numRacks <= 5; numRacks++) {
      newCluster(numRacks, 45, 4);
      List<String> racks = new ArrayList<>(inService.keySet());
      for (int trial = 0; trial < trialsPerShape; trial++) {
        ErasureCodingPolicy ec = schemas.get(random.nextInt(schemas.size()));
        int data = ec.getNumDataUnits();
        int realData = random.nextInt(4) == 0 ? 1 + random.nextInt(data) : data;
        List<Integer> groupIndices = new ArrayList<>();
        for (int i = 0; i < realData; i++) {
          groupIndices.add(i);
        }
        for (int i = data; i < data + ec.getNumParityUnits(); i++) {
          groupIndices.add(i);
        }

        // Bias toward over-concentration on one rack, the case that needs
        // protective copies.
        String hot = racks.get(random.nextInt(racks.size()));
        double hotBias = random.nextDouble();
        Map<String, List<DatanodeDescriptor>> free = new HashMap<>();
        Map<String, List<DatanodeDescriptor>> freeDecom = new HashMap<>();
        for (String rack : racks) {
          List<DatanodeDescriptor> nodes = new ArrayList<>(inService.get(rack));
          Collections.shuffle(nodes, random);
          free.put(rack, nodes);
          List<DatanodeDescriptor> dnodes =
              new ArrayList<>(decommissioning.get(rack));
          Collections.shuffle(dnodes, random);
          freeDecom.put(rack, dnodes);
          for (DatanodeDescriptor dn : inService.get(rack)) {
            setRemaining(dn, 1 + (long) (random.nextDouble() * (CAPACITY - 1)));
          }
        }
        List<Copy> copies = new ArrayList<>();
        for (int index : groupIndices) {
          int numCopies = 1 + (random.nextInt(3) == 0 ? random.nextInt(3) : 0);
          for (int c = 0; c < numCopies; c++) {
            String rack = (c == 0 && random.nextDouble() < hotBias) ? hot
                : racks.get(random.nextInt(racks.size()));
            List<DatanodeDescriptor> pool = free.get(rack);
            if (!pool.isEmpty()) {
              copies.add(new Copy(pool.remove(pool.size() - 1), index));
            }
          }
          if (random.nextInt(10) == 0) {
            String rack = racks.get(random.nextInt(racks.size()));
            List<DatanodeDescriptor> pool = freeDecom.get(rack);
            if (!pool.isEmpty()) {
              copies.add(new Copy(pool.remove(pool.size() - 1), index));
            }
          }
        }
        BlockInfoStriped sblk = newGroup(ec, realData, copies);
        int before = liveCopies(sblk).size();
        String context = "seed=" + seed + " racks=" + numRacks + " trial="
            + trial + " ec=" + ec.getName() + " realData=" + realData;
        int after;
        try {
          after = reclaimAndVerify(sblk, context).size();
        } catch (IllegalStateException | IllegalArgumentException e) {
          throw new AssertionError(context + " layout=" + copies, e);
        }
        if (after < before) {
          groupsReclaimed++;
          copiesReclaimed += before - after;
        }
      }
    }
    // Make sure the generator actually exercised reclaiming.
    if (groupsReclaimed == 0) {
      fail("no layout exercised reclaiming; seed=" + seed);
    }
    System.out.println("TestErasureCodingExcessRedundancy seed=" + seed
        + " groupsReclaimed=" + groupsReclaimed + " copiesReclaimed="
        + copiesReclaimed);
  }
}
