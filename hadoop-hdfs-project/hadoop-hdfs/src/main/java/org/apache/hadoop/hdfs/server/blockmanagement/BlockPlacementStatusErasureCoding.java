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

import java.util.Map;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;

/**
 * Placement status for an erasure-coded block group.
 * <p>
 * This combines two requirements:
 * <ol>
 *   <li>The inherited rack-spreading requirement (the internal blocks are
 *   distributed across as many failure domains as possible), carried by the
 *   {@code rackSpreadStatus} parent.</li>
 *   <li>The per-failure-domain durability requirement: a scheme that tolerates
 *   the loss of up to {@code N} internal blocks keeps a domain safe only while
 *   the number of internal blocks that exist <em>solely</em> within it is at
 *   most {@code N}. Losing that domain then drops at most {@code N} blocks,
 *   still leaving enough to reconstruct the group.</li>
 * </ol>
 * The placement is satisfied only when both hold. Where the durability rule is
 * violated, the number of additional copies required is the count of internal
 * blocks that would need to be duplicated into another domain to bring every
 * domain back within budget.
 */
@InterfaceAudience.Private
@InterfaceStability.Evolving
public class BlockPlacementStatusErasureCoding implements BlockPlacementStatus {

  private final BlockPlacementStatus rackSpreadStatus;
  private final int maxLossesPerDomain;
  private final Map<String, Integer> soleCopiesPerDomain;
  private final int reliefCopiesAvailable;
  private final int shortfall;
  private final String worstDomain;
  private final int worstDomainCount;

  /**
   * @param rackSpreadStatus the inherited rack-spreading status (the group
   *          should be distributed across as many failure domains as possible)
   * @param soleCopiesPerDomain for each failure domain, the number of internal
   *          blocks whose only copies reside within that domain
   * @param reliefCopiesAvailable number of extra copies already proposed (e.g.
   *          reconstruction targets) that can each move one at-risk internal
   *          block into another domain
   * @param maxLossesPerDomain the number of internal blocks the scheme can lose,
   *          which is the most a single failure domain may hold uniquely
   */
  public BlockPlacementStatusErasureCoding(
      BlockPlacementStatus rackSpreadStatus,
      Map<String, Integer> soleCopiesPerDomain, int reliefCopiesAvailable,
      int maxLossesPerDomain) {
    this.rackSpreadStatus = rackSpreadStatus;
    this.soleCopiesPerDomain = soleCopiesPerDomain;
    this.reliefCopiesAvailable = reliefCopiesAvailable;
    this.maxLossesPerDomain = maxLossesPerDomain;

    int excess = 0;
    String worst = null;
    int worstCount = 0;
    for (Map.Entry<String, Integer> e : soleCopiesPerDomain.entrySet()) {
      int count = e.getValue();
      if (count > worstCount) {
        worstCount = count;
        worst = e.getKey();
      }
      if (count > maxLossesPerDomain) {
        excess += count - maxLossesPerDomain;
      }
    }
    this.worstDomain = worst;
    this.worstDomainCount = worstCount;
    // Each relief copy can move one at-risk internal block into another domain,
    // reducing the outstanding shortfall by one.
    this.shortfall = Math.max(0, excess - reliefCopiesAvailable);
  }

  private boolean isPerDomainSafe() {
    return shortfall == 0;
  }

  @Override
  public boolean isPlacementPolicySatisfied() {
    return rackSpreadStatus.isPlacementPolicySatisfied() && isPerDomainSafe();
  }

  @Override
  public String getErrorDescription() {
    if (isPlacementPolicySatisfied()) {
      return null;
    }
    StringBuilder sb = new StringBuilder();
    if (!rackSpreadStatus.isPlacementPolicySatisfied()) {
      sb.append(rackSpreadStatus.getErrorDescription());
    }
    if (!isPerDomainSafe()) {
      if (sb.length() != 0) {
        sb.append(' ');
      }
      sb.append("The erasure-coded block group is not safely placed: failure ")
          .append("domain ").append(worstDomain).append(" holds ")
          .append(worstDomainCount).append(" internal block(s) that exist ")
          .append("nowhere else, but the scheme can only lose ")
          .append(maxLossesPerDomain).append(" internal block(s) per domain. ")
          .append(shortfall).append(" additional copy(ies) are needed on other ")
          .append("failure domains to make the group safe.");
    }
    return sb.toString();
  }

  @Override
  public int getAdditionalReplicasRequired() {
    return Math.max(rackSpreadStatus.getAdditionalReplicasRequired(), shortfall);
  }

  /**
   * The number of internal blocks whose only copies are within {@code domain}.
   */
  public int getSoleCopies(String domain) {
    return soleCopiesPerDomain.getOrDefault(domain, 0);
  }

  /**
   * The number of additional failure domains the group still has to occupy to
   * satisfy the rack-spreading requirement.
   */
  public int getMissingRacks() {
    return rackSpreadStatus.getAdditionalReplicasRequired();
  }

  /**
   * The number of internal blocks that still have to be copied into another
   * domain before every domain is within its loss budget.
   */
  public int getDurabilityShortfall() {
    return shortfall;
  }

  /**
   * Whether this placement is strictly less safe than {@code other} in either
   * requirement: it occupies fewer of the domains it should, or more internal
   * blocks are at risk of a single-domain loss. The two requirements are
   * compared separately so that a large deficit in one cannot mask a change in
   * the other.
   */
  public boolean isLessSafeThan(BlockPlacementStatusErasureCoding other) {
    return getMissingRacks() > other.getMissingRacks()
        || getDurabilityShortfall() > other.getDurabilityShortfall();
  }
}
