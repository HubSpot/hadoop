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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

/**
 * Unit tests for {@link BlockPlacementStatusErasureCoding}, which reports
 * whether an erasure-coded block group keeps every failure domain within its
 * loss budget.
 */
public class TestBlockPlacementStatusErasureCoding {

  private static Map<String, Integer> soleCopies(Object... rackThenCount) {
    Map<String, Integer> map = new HashMap<>();
    for (int i = 0; i < rackThenCount.length; i += 2) {
      map.put((String) rackThenCount[i], (Integer) rackThenCount[i + 1]);
    }
    return map;
  }

  /** A rack-spread status that is already satisfied, to isolate the per-domain
   * durability check in these tests. */
  private static BlockPlacementStatus satisfiedRackSpread() {
    return new BlockPlacementStatusDefault(1, 1, 1);
  }

  @Test
  public void testSafeWhenEveryDomainWithinBudget() {
    // RS-6-3: k=3. Three domains each holding 3 sole-copy internal blocks.
    BlockPlacementStatus status = new BlockPlacementStatusErasureCoding(
        satisfiedRackSpread(), soleCopies("/r1", 3, "/r2", 3, "/r3", 3), 0, 3);
    assertTrue(status.isPlacementPolicySatisfied());
    assertEquals(0, status.getAdditionalReplicasRequired());
    assertNull(status.getErrorDescription());
  }

  @Test
  public void testUnsafeWhenADomainExceedsBudget() {
    // RS-6-3 on two domains split 5/4: losing the 5-block domain leaves only 4
    // internal blocks, below the 6 needed to reconstruct.
    BlockPlacementStatus status = new BlockPlacementStatusErasureCoding(
        satisfiedRackSpread(), soleCopies("/r1", 5, "/r2", 4), 0, 3);
    assertFalse(status.isPlacementPolicySatisfied());
    // (5-3) + (4-3) = 3 copies must move to other domains.
    assertEquals(3, status.getAdditionalReplicasRequired());
    assertNotNull(status.getErrorDescription());
  }

  @Test
  public void testReliefCopiesReduceShortfall() {
    // The same 5/4 split, but with three proposed extra copies that can each
    // move one at-risk internal block to another domain.
    BlockPlacementStatus status = new BlockPlacementStatusErasureCoding(
        satisfiedRackSpread(), soleCopies("/r1", 5, "/r2", 4), 3, 3);
    assertTrue(status.isPlacementPolicySatisfied());
    assertEquals(0, status.getAdditionalReplicasRequired());
  }

  @Test
  public void testPartialReliefStillShortByRemainder() {
    BlockPlacementStatus status = new BlockPlacementStatusErasureCoding(
        satisfiedRackSpread(), soleCopies("/r1", 5, "/r2", 4), 1, 3);
    assertFalse(status.isPlacementPolicySatisfied());
    assertEquals(2, status.getAdditionalReplicasRequired());
  }

  @Test
  public void testOverReplicatedGroupIsSafe() {
    // After duplicating three internal blocks across both domains, each domain
    // holds only three sole-copy blocks.
    BlockPlacementStatus status = new BlockPlacementStatusErasureCoding(
        satisfiedRackSpread(), soleCopies("/r1", 3, "/r2", 3), 0, 3);
    assertTrue(status.isPlacementPolicySatisfied());
    assertEquals(0, status.getAdditionalReplicasRequired());
  }

  @Test
  public void testEmptyPlacementIsSatisfied() {
    BlockPlacementStatus status = new BlockPlacementStatusErasureCoding(
        satisfiedRackSpread(), new HashMap<>(), 0, 3);
    assertTrue(status.isPlacementPolicySatisfied());
    assertEquals(0, status.getAdditionalReplicasRequired());
  }
}
