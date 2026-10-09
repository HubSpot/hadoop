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
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    String[] domains() {
      String[] d = new String[locs.size()];
      for (int i = 0; i < d.length; i++) {
        d[i] = locs.get(i).getNetworkLocation();
      }
      return d;
    }

    byte[] indices() {
      byte[] idx = new byte[indices.size()];
      for (int i = 0; i < idx.length; i++) {
        idx[i] = indices.get(i);
      }
      return idx;
    }

    /** Positions of the copies of internal block {@code index}. */
    int[] copiesOf(int index) {
      List<Integer> positions = new ArrayList<>();
      for (int i = 0; i < indices.size(); i++) {
        if (indices.get(i) == index) {
          positions.add(i);
        }
      }
      int[] p = new int[positions.size()];
      for (int i = 0; i < p.length; i++) {
        p[i] = positions.get(i);
      }
      return p;
    }

    BlockPlacementStatusErasureCoding verify(int totalInternalBlocks,
        int maxLossesPerDomain) {
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
  public void testMissingRackRequiresOneCopyPerMissingRack() {
    // Safe per domain (indices 3-5 are on both racks, leaving 3 sole copies
    // each) but the group occupies only 2 of the cluster's 3 racks. One copy
    // onto /r3 fixes it, so exactly one is required.
    newNode("/r3");
    BlockPlacementStatus status = new Placement()
        .on("/r1", 0, 1, 2, 3, 4, 5)
        .on("/r2", 3, 4, 5, 6, 7, 8)
        .verify(DATA_UNITS + PARITY_UNITS, PARITY_UNITS);
    assertFalse(status.isPlacementPolicySatisfied());
    assertEquals(1, status.getAdditionalReplicasRequired());
  }

  @Test
  public void testRemovingAProtectiveCopyIsLessSafeEvenWhileARackIsMissing() {
    // The decommissioning case: /r1 holds no live copy, /r2 is over budget.
    // Copying an /r2 index onto /r3 relieves /r2. Removing that copy must be
    // seen as less safe even though the group is still missing /r1.
    newNode("/r1");
    BlockPlacementStatusErasureCoding withCopy = new Placement()
        .on("/r3", 0, 1)
        .on("/r2", 2, 3, 4, 5, 6, 7, 8)
        .on("/r3", 2)
        .verify(DATA_UNITS + PARITY_UNITS, PARITY_UNITS);
    BlockPlacementStatusErasureCoding withoutCopy = new Placement()
        .on("/r3", 0, 1)
        .on("/r2", 2, 3, 4, 5, 6, 7, 8)
        .verify(DATA_UNITS + PARITY_UNITS, PARITY_UNITS);
    assertEquals(1, withCopy.getMissingRacks());
    assertEquals(3, withCopy.getDurabilityShortfall());
    assertEquals(4, withoutCopy.getDurabilityShortfall());
    assertTrue(withoutCopy.isLessSafeThan(withCopy));
    assertFalse(withCopy.isLessSafeThan(withoutCopy));
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

  private static final int TOTAL = DATA_UNITS + PARITY_UNITS;
  private static final List<String> RACKS = Arrays.asList("/a", "/b", "/c");

  private int[] reclaim(Placement p, int index, long... keepPreference) {
    int[] copies = p.copiesOf(index);
    long[] pref = keepPreference.length == copies.length ? keepPreference
        : new long[copies.length];
    return policy.chooseCopiesToReclaim(p.domains(), p.indices(), copies,
        pref, TOTAL, PARITY_UNITS);
  }

  /**
   * After a copy of index 7 lands in /a, its two old copies in /b and /c can
   * only be reclaimed together: removing either alone is safe, but the policy
   * must find that removing both is too.
   */
  @Test
  public void testReclaimFindsTheLargestSafeSet() {
    Placement p = new Placement()
        .on("/a", 0, 4, 7)
        .on("/b", 1, 2, 6, 7)
        .on("/c", 3, 5, 8, 7);
    int[] removed = reclaim(p, 7);
    assertEquals(2, removed.length);
    for (int position : removed) {
      assertTrue("the /a copy must be kept",
          !p.domains()[position].equals("/a"));
    }
  }

  @Test
  public void testReclaimKeepsTheMostPreferredAmongEqualChoices() {
    // Index 4 duplicated within /b protects nothing; either copy can go.
    Placement p = new Placement()
        .on("/a", 0, 1, 2)
        .on("/b", 3, 4, 5, 4)
        .on("/c", 6, 7, 8);
    int[] copies = p.copiesOf(4);
    int[] removed = reclaim(p, 4, 100, 5);
    assertEquals(1, removed.length);
    assertEquals("the less preferred copy goes", copies[1], removed[0]);
    removed = reclaim(p, 4, 5, 100);
    assertEquals(copies[0], removed[0]);
  }

  @Test
  public void testReclaimKeepsProtectiveCopies() {
    // fsck group 0: index 7 in /b and /c, both at their budget.
    Placement p = new Placement()
        .on("/a", 0, 4)
        .on("/b", 1, 2, 6, 7)
        .on("/c", 3, 5, 8, 7);
    assertEquals(0, reclaim(p, 7).length);
  }

  @Test
  public void testReclaimNeverRemovesEveryCopy() {
    // In a single-rack cluster every removal is "safe"; one copy must stay.
    NetworkTopology singleRack = new NetworkTopology();
    BlockPlacementPolicyErasureCoding singleRackPolicy =
        new BlockPlacementPolicyErasureCoding();
    singleRackPolicy.clusterMap = singleRack;
    String[] domains = new String[TOTAL + 2];
    byte[] indices = new byte[TOTAL + 2];
    for (int i = 0; i < TOTAL; i++) {
      domains[i] = "/only";
      indices[i] = (byte) i;
    }
    domains[TOTAL] = "/only";
    indices[TOTAL] = 2;
    domains[TOTAL + 1] = "/only";
    indices[TOTAL + 1] = 2;
    int[] removed = singleRackPolicy.chooseCopiesToReclaim(domains, indices,
        new int[] {2, TOTAL, TOTAL + 1}, new long[3], TOTAL, PARITY_UNITS);
    assertEquals(2, removed.length);
  }

  @Test
  public void testReclaimDeclinesToSearchTooManyCopies() {
    Placement p = new Placement().on("/a", 0, 1, 2).on("/b", 3, 4, 5)
        .on("/c", 6, 7, 8);
    for (int i = 0; i < BlockPlacementPolicyErasureCoding.MAX_COPIES_SEARCHED;
         i++) {
      p.on(RACKS.get(i % 3), 0);
    }
    assertEquals(null, reclaim(p, 0));
  }

  /**
   * fsck group 0: index 7 duplicated across /b and /c, both at their budget,
   * while /a holds only two sole copies. Copying 7 into /a lets both old copies
   * go: one fewer copy overall, ending 3/3/3.
   */
  @Test
  public void testConsolidationMovesTheSharedIndexToTheRoomyDomain() {
    Placement p = new Placement()
        .on("/a", 0, 4)
        .on("/b", 1, 2, 6, 7)
        .on("/c", 3, 5, 8, 7);
    BlockPlacementPolicyErasureCoding.Consolidation plan =
        policy.planConsolidation(p.domains(), p.indices(), RACKS, TOTAL,
            PARITY_UNITS);
    assertEquals(7, plan.getIndex());
    assertEquals("/a", plan.getDomain());
    assertEquals(1, plan.getNetReclaimed());
  }

  /**
   * fsck group 30 (11 copies): indices 3 and 6 are each shared by /b and /c,
   * and /a holds a single sole copy. Two consolidations each reclaim one net
   * copy and the group ends at 9.
   */
  @Test
  public void testConsolidationRepeatsUntilNothingIsLeft() {
    Placement p = new Placement()
        .on("/a", 4)
        .on("/b", 1, 3, 5, 6, 8)
        .on("/c", 0, 7, 2, 3, 6);
    int copies = p.indices().length;
    for (int step = 0; step < 5; step++) {
      BlockPlacementPolicyErasureCoding.Consolidation plan =
          policy.planConsolidation(p.domains(), p.indices(), RACKS, TOTAL,
              PARITY_UNITS);
      if (plan == null) {
        break;
      }
      // Apply the plan as the cluster would: add the copy, then reclaim.
      BlockPlacementStatusErasureCoding before = p.verify(TOTAL, PARITY_UNITS);
      p.on(plan.getDomain(), plan.getIndex());
      int[] removed = reclaim(p, plan.getIndex());
      p = without(p, removed);
      assertFalse(p.verify(TOTAL, PARITY_UNITS).isLessSafeThan(before));
      assertEquals(copies - plan.getNetReclaimed(), p.indices().length);
      copies = p.indices().length;
    }
    assertEquals(TOTAL, copies);
    assertTrue(p.verify(TOTAL, PARITY_UNITS).isPlacementPolicySatisfied());
  }

  private Placement without(Placement p, int[] removed) {
    Set<Integer> gone = new HashSet<>();
    for (int r : removed) {
      gone.add(r);
    }
    Placement q = new Placement();
    String[] d = p.domains();
    byte[] x = p.indices();
    for (int i = 0; i < d.length; i++) {
      if (!gone.contains(i)) {
        q.on(d[i], x[i]);
      }
    }
    return q;
  }

  @Test
  public void testNoConsolidationWhenEveryDomainIsFull() {
    // Two racks, RS-6-3: each must keep 3 shared indices; nothing to gain.
    Placement p = new Placement()
        .on("/a", 0, 1, 2, 3, 4, 5)
        .on("/b", 3, 4, 5, 6, 7, 8);
    assertEquals(null, policy.planConsolidation(p.domains(), p.indices(),
        Arrays.asList("/a", "/b"), TOTAL, PARITY_UNITS));
  }

  @Test
  public void testNoConsolidationWhileAnythingIsReclaimable() {
    // fsck group 16: the /b copy of index 1 can simply be reclaimed; that must
    // happen first.
    Placement p = new Placement()
        .on("/a", 6, 7)
        .on("/b", 0, 1, 2, 5, 8)
        .on("/c", 1, 3, 4, 5);
    assertEquals(null, policy.planConsolidation(p.domains(), p.indices(),
        RACKS, TOTAL, PARITY_UNITS));
  }

  @Test
  public void testNoConsolidationWhenUnsafe() {
    Placement p = new Placement()
        .on("/a", 0)
        .on("/b", 1, 2, 3, 4, 5, 6, 7)
        .on("/c", 8, 7);
    assertFalse(p.verify(TOTAL, PARITY_UNITS).isPlacementPolicySatisfied());
    assertEquals(null, policy.planConsolidation(p.domains(), p.indices(),
        RACKS, TOTAL, PARITY_UNITS));
  }

  @Test
  public void testDomainsWithoutSpareCapacity() {
    Placement p = new Placement()
        .on("/a", 0, 4)
        .on("/b", 1, 2, 6, 7)
        .on("/c", 3, 5, 8, 7);
    assertEquals(new HashSet<>(Arrays.asList("/b", "/c")),
        policy.domainsWithoutSpareCapacity(p.domains(), p.indices(), TOTAL,
            PARITY_UNITS));
  }
}
