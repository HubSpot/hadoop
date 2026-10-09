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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.hdfs.protocol.DatanodeInfo;

/**
 * A block placement policy for erasure-coded (striped) block groups.
 * <p>
 * An erasure coding scheme is a maximum-distance-separable code: a block group
 * with {@code d} internal blocks that tolerates the loss of up to {@code N} of
 * them can be reconstructed from any {@code d - N} survivors. Data and parity
 * internal blocks are interchangeable for this purpose, so this policy tracks
 * neither separately and applies a single durability rule to all of them:
 * after any one failure domain is lost, enough internal blocks must remain to
 * reconstruct the group.
 * <p>
 * Expressed per failure domain, the rule is that no domain may hold more than
 * {@code N} internal blocks that exist <em>only</em> within it, where {@code N}
 * is the number of internal blocks the scheme can lose (its parity count). When
 * a group cannot meet that rule with one copy of each internal block — for
 * example when there are too few domains — the policy reports that additional
 * copies are required, so that the redundancy monitor duplicates at-risk
 * internal blocks into other domains. In other words, when placement would
 * otherwise be unsafe, this policy prefers over-replication to
 * under-replication.
 * <p>
 * This is the placement policy for all erasure-coded blocks; it is not
 * configurable, because the block manager's reconstruction and excess
 * handling for striped blocks depend on its per-domain rule.
 * <p>
 * Target selection is inherited from {@link
 * BlockPlacementPolicyRackFaultTolerant}, which spreads internal blocks across
 * as many domains as possible; the durability guarantee itself is enforced by
 * the index-aware {@link #verifyBlockPlacement} below together with the
 * durability-aware excess-block handling in the block manager.
 */
@InterfaceAudience.Private
public class BlockPlacementPolicyErasureCoding
    extends BlockPlacementPolicyRackFaultTolerant {

  /**
   * Verify the placement of an erasure-coded block group using the internal
   * block index each location holds.
   * <p>
   * This enforces both a rack-spreading requirement (the group occupies as many
   * failure domains as it can) and a per-failure-domain durability requirement.
   * For the latter,
   * for every internal-block index the set of failure domains that hold a copy
   * is collected. An index counts against a domain only when that domain holds
   * the sole copy of it; an index that has been duplicated into a second domain
   * can no longer be lost by the failure of either one and so counts against
   * neither. The durability requirement is met when no domain holds more than
   * {@code maxLossesPerDomain} such sole-copy internal blocks.
   *
   * @param locs the locations holding internal blocks of the group
   * @param blockIndices the internal-block index at the corresponding position
   *          in {@code locs}; {@code -1} marks a candidate copy (e.g. a proposed
   *          reconstruction target) that can relieve an over-concentrated domain
   * @param totalInternalBlocks the number of internal blocks in the group,
   *          used for the inherited rack-spreading check
   * @param maxLossesPerDomain the number of internal blocks the scheme can lose,
   *          i.e. the parity count, which is the most a single failure domain
   *          may hold uniquely
   */
  public BlockPlacementStatusErasureCoding verifyBlockPlacement(
      DatanodeInfo[] locs, byte[] blockIndices, int totalInternalBlocks,
      int maxLossesPerDomain) {
    if (locs == null) {
      locs = DatanodeDescriptor.EMPTY_ARRAY;
    }
    String[] domains = new String[locs.length];
    for (int i = 0; i < locs.length; i++) {
      domains[i] = locs[i].getNetworkLocation();
    }
    return verifyPlacement(domains, blockIndices, totalInternalBlocks,
        maxLossesPerDomain);
  }

  /**
   * {@link #verifyBlockPlacement(DatanodeInfo[], byte[], int, int)} for a
   * placement given as the failure domain of each location.
   */
  BlockPlacementStatusErasureCoding verifyPlacement(String[] domains,
      byte[] blockIndices, int totalInternalBlocks, int maxLossesPerDomain) {
    final BlockPlacementStatus rackSpreadStatus =
        verifyRackSpread(domains, totalInternalBlocks);

    if (!clusterMap.hasClusterEverBeenMultiRack()) {
      // With a single failure domain no placement can survive its loss, so the
      // per-domain rule cannot be improved; defer entirely to the rack check to
      // avoid endless reconstruction, matching the convention of other policies.
      return new BlockPlacementStatusErasureCoding(
          rackSpreadStatus, new HashMap<>(), 0, maxLossesPerDomain);
    }

    Map<Byte, Set<String>> domainsPerIndex = new HashMap<>();
    int reliefCopies = 0;
    final int len = blockIndices == null ? 0
        : Math.min(domains.length, blockIndices.length);
    for (int i = 0; i < len; i++) {
      final byte index = blockIndices[i];
      if (index < 0) {
        reliefCopies++;
        continue;
      }
      domainsPerIndex
          .computeIfAbsent(index, k -> new HashSet<>())
          .add(domains[i]);
    }

    return new BlockPlacementStatusErasureCoding(rackSpreadStatus,
        soleCopiesPerDomain(domainsPerIndex), reliefCopies,
        maxLossesPerDomain);
  }

  private static Map<String, Integer> soleCopiesPerDomain(
      Map<Byte, Set<String>> domainsPerIndex) {
    Map<String, Integer> soleCopies = new HashMap<>();
    for (Set<String> domains : domainsPerIndex.values()) {
      if (domains.size() == 1) {
        soleCopies.merge(domains.iterator().next(), 1, Integer::sum);
      }
    }
    return soleCopies;
  }

  /**
   * The rack-spreading requirement: the group should occupy as many failure
   * domains as it can, i.e. {@code min(totalInternalBlocks, racks)}. The
   * additional replicas reported are the number of domains still missing, so a
   * group that occupies 2 of 3 racks needs exactly one more copy, not one per
   * internal block it would take to fill every rack.
   */
  private BlockPlacementStatus verifyRackSpread(String[] domains,
      int totalInternalBlocks) {
    if (!clusterMap.hasClusterEverBeenMultiRack()) {
      // With a single failure domain the requirement is trivially met.
      return new BlockPlacementStatusDefault(1, 1, 1);
    }
    Set<String> racks = new HashSet<>(Arrays.asList(domains));
    final int totalRacks = clusterMap.getNumOfNonEmptyRacks();
    final int requiredRacks = Math.min(totalInternalBlocks, totalRacks);
    return new BlockPlacementStatusDefault(racks.size(), requiredRacks,
        totalRacks);
  }

  /**
   * The most copies of one internal block that are searched exhaustively when
   * choosing which to reclaim.
   */
  static final int MAX_COPIES_SEARCHED = 8;

  /**
   * Choose which redundant copies of one internal block to reclaim: the
   * largest set whose removal leaves the placement no less safe than it is
   * now (see {@link BlockPlacementStatusErasureCoding#isLessSafeThan}), always
   * keeping at least one copy. Among equally large sets, the one keeping the
   * copies with the highest {@code keepPreference} is chosen.
   * <p>
   * Removing a copy never makes the placement safer, so the safe removals form
   * a downward-closed family and searching keep-sets from smallest up finds the
   * largest safe removal.
   *
   * @param domains the failure domain of every live copy in the group
   * @param indices the internal-block index of every live copy in the group
   * @param copies positions (into {@code domains}/{@code indices}) of the
   *          copies of a single internal block
   * @param keepPreference per entry of {@code copies}, how much keeping that
   *          copy is preferred
   * @return positions to reclaim (possibly empty), or null if there are more
   *         than {@link #MAX_COPIES_SEARCHED} copies to search
   */
  int[] chooseCopiesToReclaim(String[] domains, byte[] indices, int[] copies,
      long[] keepPreference, int totalInternalBlocks, int maxLossesPerDomain) {
    final int n = copies.length;
    if (n < 2) {
      return new int[0];
    }
    if (n > MAX_COPIES_SEARCHED) {
      return null;
    }
    final BlockPlacementStatusErasureCoding baseline = verifyPlacement(
        domains, indices, totalInternalBlocks, maxLossesPerDomain);
    final int all = (1 << n) - 1;
    for (int keep = 1; keep < n; keep++) {
      int bestMask = -1;
      long bestScore = Long.MIN_VALUE;
      for (int keepMask = 1; keepMask <= all; keepMask++) {
        if (Integer.bitCount(keepMask) != keep) {
          continue;
        }
        Set<Integer> removed = new HashSet<>();
        long score = 0;
        for (int b = 0; b < n; b++) {
          if ((keepMask & (1 << b)) == 0) {
            removed.add(copies[b]);
          } else {
            score += keepPreference[b];
          }
        }
        if (without(domains, indices, removed, totalInternalBlocks,
            maxLossesPerDomain).isLessSafeThan(baseline)) {
          continue;
        }
        if (score > bestScore) {
          bestScore = score;
          bestMask = keepMask;
        }
      }
      if (bestMask >= 0) {
        int[] reclaim = new int[n - keep];
        int r = 0;
        for (int b = 0; b < n; b++) {
          if ((bestMask & (1 << b)) == 0) {
            reclaim[r++] = copies[b];
          }
        }
        return reclaim;
      }
    }
    return new int[0];
  }

  private BlockPlacementStatusErasureCoding without(String[] domains,
      byte[] indices, Set<Integer> removed, int totalInternalBlocks,
      int maxLossesPerDomain) {
    String[] d = new String[domains.length - removed.size()];
    byte[] x = new byte[d.length];
    for (int i = 0, j = 0; i < domains.length; i++) {
      if (!removed.contains(i)) {
        d[j] = domains[i];
        x[j++] = indices[i];
      }
    }
    return verifyPlacement(d, x, totalInternalBlocks, maxLossesPerDomain);
  }

  /**
   * The failure domains that cannot take another internal block as its only
   * copy: they already hold {@code maxLossesPerDomain} or more internal blocks
   * that exist nowhere else. A copy placed there to relieve another domain can
   * never become the sole copy, so the original can never be reclaimed.
   */
  Set<String> domainsWithoutSpareCapacity(String[] domains, byte[] indices,
      int totalInternalBlocks, int maxLossesPerDomain) {
    BlockPlacementStatusErasureCoding status = verifyPlacement(domains,
        indices, totalInternalBlocks, maxLossesPerDomain);
    Set<String> full = new HashSet<>();
    for (String domain : domains) {
      if (status.getSoleCopies(domain) >= maxLossesPerDomain) {
        full.add(domain);
      }
    }
    return full;
  }

  /**
   * A copy of an internal block into a failure domain that lets more existing
   * copies of it be reclaimed than the one copy added, i.e. a move.
   */
  static final class Consolidation {
    private final byte index;
    private final String domain;
    private final int netReclaimed;

    Consolidation(byte index, String domain, int netReclaimed) {
      this.index = index;
      this.domain = domain;
      this.netReclaimed = netReclaimed;
    }

    /** The internal block to copy. */
    byte getIndex() {
      return index;
    }

    /** The failure domain to copy it into. */
    String getDomain() {
      return domain;
    }

    /**
     * Copies of this internal block reclaimed afterwards, net of the one
     * added. A lower bound: the new copy can also let copies of other internal
     * blocks be reclaimed, for instance by keeping its domain occupied.
     */
    int getNetReclaimed() {
      return netReclaimed;
    }

    @Override
    public String toString() {
      return "copy internal block " + index + " to " + domain + " (net "
          + netReclaimed + " fewer)";
    }
  }

  /**
   * Find a copy that reduces the group's over-replication. Applies only to a
   * safely placed group whose redundant copies are all protective (nothing can
   * be reclaimed as it stands): typically two domains at their loss budget
   * share a duplicated internal block while another domain has room. Copying
   * that internal block into the roomy domain lets the excess handling then
   * reclaim both shared copies, making it a move.
   * <p>
   * The excess handling reclaims copies with {@link #chooseCopiesToReclaim}
   * against the placement as it is when the copy lands, so the group is never
   * less safe than it is now at any point.
   *
   * @param domains the failure domain of every live copy in the group
   * @param indices the internal-block index of every live copy in the group
   * @param candidateDomains failure domains that could receive a copy
   * @return the copy that reclaims the most, or null if none reclaims any
   */
  Consolidation planConsolidation(String[] domains, byte[] indices,
      Collection<String> candidateDomains, int totalInternalBlocks,
      int maxLossesPerDomain) {
    final BlockPlacementStatusErasureCoding current = verifyPlacement(
        domains, indices, totalInternalBlocks, maxLossesPerDomain);
    if (!current.isPlacementPolicySatisfied()) {
      // Relieving the placement takes precedence and is done elsewhere.
      return null;
    }
    Map<Byte, List<Integer>> copiesPerIndex = new TreeMap<>();
    for (int i = 0; i < indices.length; i++) {
      if (indices[i] >= 0) {
        copiesPerIndex.computeIfAbsent(indices[i], k -> new ArrayList<>())
            .add(i);
      }
    }
    // Only consolidate once nothing can be reclaimed outright.
    for (List<Integer> copies : copiesPerIndex.values()) {
      int[] reclaim = chooseCopiesToReclaim(domains, indices, toArray(copies),
          new long[copies.size()], totalInternalBlocks, maxLossesPerDomain);
      if (reclaim == null || reclaim.length > 0) {
        return null;
      }
    }

    List<String> targets = new ArrayList<>(new TreeSet<>(candidateDomains));
    Consolidation best = null;
    int bestTargetLoad = Integer.MAX_VALUE;
    for (Map.Entry<Byte, List<Integer>> e : copiesPerIndex.entrySet()) {
      final List<Integer> copies = e.getValue();
      if (copies.size() < 2) {
        continue;
      }
      Set<String> holding = new HashSet<>();
      for (int c : copies) {
        holding.add(domains[c]);
      }
      for (String target : targets) {
        if (holding.contains(target)) {
          continue;
        }
        String[] d = Arrays.copyOf(domains, domains.length + 1);
        byte[] x = Arrays.copyOf(indices, indices.length + 1);
        d[d.length - 1] = target;
        x[x.length - 1] = e.getKey();
        int[] withNew = new int[copies.size() + 1];
        long[] preference = new long[withNew.length];
        for (int c = 0; c < copies.size(); c++) {
          withNew[c] = copies.get(c);
        }
        withNew[copies.size()] = d.length - 1;
        // Keep the new copy wherever it is as good as an existing one.
        preference[copies.size()] = 1;
        int[] reclaim = chooseCopiesToReclaim(d, x, withNew, preference,
            totalInternalBlocks, maxLossesPerDomain);
        if (reclaim == null || reclaim.length < 2) {
          continue;
        }
        boolean newCopyReclaimed = false;
        for (int r : reclaim) {
          newCopyReclaimed |= r == d.length - 1;
        }
        if (newCopyReclaimed) {
          continue;
        }
        final int net = reclaim.length - 1;
        final int load = current.getSoleCopies(target);
        if (best == null || net > best.getNetReclaimed()
            || (net == best.getNetReclaimed() && load < bestTargetLoad)) {
          best = new Consolidation(e.getKey(), target, net);
          bestTargetLoad = load;
        }
      }
    }
    return best;
  }

  private static int[] toArray(List<Integer> list) {
    int[] a = new int[list.size()];
    for (int i = 0; i < a.length; i++) {
      a[i] = list.get(i);
    }
    return a;
  }
}
