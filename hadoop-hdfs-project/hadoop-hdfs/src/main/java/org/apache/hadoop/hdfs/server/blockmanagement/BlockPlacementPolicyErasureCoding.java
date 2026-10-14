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

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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
   * The internal-block index given to a proposed new location (for example a
   * reconstruction target) whose index is not decided yet. Such a location
   * counts as an extra copy that can relieve one over-concentrated domain.
   */
  static final byte PROPOSED_TARGET_INDEX = -1;

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
   *          in {@code locs}, or {@link #PROPOSED_TARGET_INDEX}
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
    if (!clusterMap.hasClusterEverBeenMultiRack()) {
      // With a single failure domain no placement can survive its loss, so
      // neither requirement can be improved; report it as satisfied to avoid
      // endless reconstruction, matching the convention of other policies.
      return new BlockPlacementStatusErasureCoding(
          new BlockPlacementStatusDefault(1, 1, 1), new HashMap<>(), 0,
          maxLossesPerDomain);
    }
    final BlockPlacementStatus rackSpreadStatus =
        verifyRackSpread(domains, totalInternalBlocks);

    Map<Byte, Set<String>> domainsPerIndex = new HashMap<>();
    int reliefCopies = 0;
    final int len = blockIndices == null ? 0
        : Math.min(domains.length, blockIndices.length);
    for (int i = 0; i < len; i++) {
      final byte index = blockIndices[i];
      if (index == PROPOSED_TARGET_INDEX) {
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

  /**
   * For each failure domain, the number of internal blocks whose copies are
   * all within it, given the domains holding each internal block.
   */
  static Map<String, Integer> soleCopiesPerDomain(
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
    Set<String> racks = new HashSet<>(Arrays.asList(domains));
    final int totalRacks = clusterMap.getNumOfNonEmptyRacks();
    final int requiredRacks = Math.min(totalInternalBlocks, totalRacks);
    return new BlockPlacementStatusDefault(racks.size(), requiredRacks,
        totalRacks);
  }
}
