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
    final BlockPlacementStatus rackSpreadStatus =
        verifyRackSpread(locs, totalInternalBlocks);

    if (!clusterMap.hasClusterEverBeenMultiRack()) {
      // With a single failure domain no placement can survive its loss, so the
      // per-domain rule cannot be improved; defer entirely to the rack check to
      // avoid endless reconstruction, matching the convention of other policies.
      return new BlockPlacementStatusErasureCoding(
          rackSpreadStatus, new HashMap<>(), 0, maxLossesPerDomain);
    }

    Map<Byte, Set<String>> domainsPerIndex = new HashMap<>();
    int reliefCopies = 0;
    final int len =
        blockIndices == null ? 0 : Math.min(locs.length, blockIndices.length);
    for (int i = 0; i < len; i++) {
      final byte index = blockIndices[i];
      final String domain = locs[i].getNetworkLocation();
      if (index < 0) {
        reliefCopies++;
        continue;
      }
      domainsPerIndex
          .computeIfAbsent(index, k -> new HashSet<>())
          .add(domain);
    }

    Map<String, Integer> soleCopiesPerDomain = new HashMap<>();
    for (Set<String> domains : domainsPerIndex.values()) {
      if (domains.size() == 1) {
        final String domain = domains.iterator().next();
        soleCopiesPerDomain.merge(domain, 1, Integer::sum);
      }
    }

    return new BlockPlacementStatusErasureCoding(rackSpreadStatus,
        soleCopiesPerDomain, reliefCopies, maxLossesPerDomain);
  }

  /**
   * The rack-spreading requirement: the group should occupy as many failure
   * domains as it can, i.e. {@code min(totalInternalBlocks, racks)}. The
   * additional replicas reported are the number of domains still missing, so a
   * group that occupies 2 of 3 racks needs exactly one more copy, not one per
   * internal block it would take to fill every rack.
   */
  private BlockPlacementStatus verifyRackSpread(DatanodeInfo[] locs,
      int totalInternalBlocks) {
    if (!clusterMap.hasClusterEverBeenMultiRack()) {
      // With a single failure domain the requirement is trivially met.
      return new BlockPlacementStatusDefault(1, 1, 1);
    }
    Set<String> racks = new HashSet<>();
    for (DatanodeInfo dn : locs) {
      racks.add(dn.getNetworkLocation());
    }
    final int totalRacks = clusterMap.getNumOfNonEmptyRacks();
    final int requiredRacks = Math.min(totalInternalBlocks, totalRacks);
    return new BlockPlacementStatusDefault(racks.size(), requiredRacks,
        totalRacks);
  }
}
