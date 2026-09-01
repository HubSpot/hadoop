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

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.server.namenode.FSNamesystem;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * HubSpot: pure-unit tests for the adaptive pacing logic of
 * {@link DatanodeAdminAdaptiveBackoffMonitor}. These exercise the load-to-limit
 * mapping and the {@code run()} wiring directly, without a MiniDFSCluster.
 */
public class TestDatanodeAdminAdaptiveBackoffMonitor {

  private static final int MIN = 100;
  private static final int MAX = 10000;
  private static final int HEALTHY_Q = 100;
  private static final int BUSY_Q = 1100;

  private Configuration baseConf() {
    Configuration conf = new Configuration();
    conf.setBoolean(
        DFSConfigKeys.DFS_NAMENODE_DECOMMISSION_BACKOFF_MONITOR_ADAPTIVE_ENABLED, true);
    conf.setInt(
        DFSConfigKeys.DFS_NAMENODE_DECOMMISSION_BACKOFF_MONITOR_MIN_PENDING_LIMIT, MIN);
    conf.setInt(
        DFSConfigKeys.DFS_NAMENODE_DECOMMISSION_BACKOFF_MONITOR_MAX_PENDING_LIMIT, MAX);
    conf.setInt(
        DFSConfigKeys.DFS_NAMENODE_DECOMMISSION_BACKOFF_MONITOR_HEALTHY_RPC_QUEUE_LENGTH,
        HEALTHY_Q);
    conf.setInt(
        DFSConfigKeys.DFS_NAMENODE_DECOMMISSION_BACKOFF_MONITOR_BUSY_RPC_QUEUE_LENGTH,
        BUSY_Q);
    return conf;
  }

  private DatanodeAdminAdaptiveBackoffMonitor newMonitor(Configuration conf,
      FSNamesystem fsn) {
    DatanodeAdminAdaptiveBackoffMonitor monitor =
        new DatanodeAdminAdaptiveBackoffMonitor();
    monitor.setBlockManager(mock(BlockManager.class));
    if (fsn != null) {
      monitor.setNameSystem(fsn);
    }
    monitor.setConf(conf);
    return monitor;
  }

  @Test
  public void testHealthyReturnsMax() {
    DatanodeAdminAdaptiveBackoffMonitor m = newMonitor(baseConf(), null);
    assertEquals(MAX, m.computeEffectivePendingLimit(0, -1, -1));
    assertEquals(MAX, m.computeEffectivePendingLimit(HEALTHY_Q, -1, -1));
  }

  @Test
  public void testBusyReturnsMin() {
    DatanodeAdminAdaptiveBackoffMonitor m = newMonitor(baseConf(), null);
    assertEquals(MIN, m.computeEffectivePendingLimit(BUSY_Q, -1, -1));
    assertEquals(MIN, m.computeEffectivePendingLimit(BUSY_Q * 10L, -1, -1));
  }

  @Test
  public void testInterpolationIsMonotonicallyDecreasing() {
    DatanodeAdminAdaptiveBackoffMonitor m = newMonitor(baseConf(), null);
    int prev = Integer.MAX_VALUE;
    for (long q = HEALTHY_Q; q <= BUSY_Q; q += 100) {
      int effective = m.computeEffectivePendingLimit(q, -1, -1);
      assertTrue("effective should stay within [min, max]",
          effective >= MIN && effective <= MAX);
      assertTrue("effective limit should decrease as the queue grows (q=" + q + ")",
          effective <= prev);
      prev = effective;
    }
    // Midpoint of the deadband should land strictly between min and max.
    int mid = m.computeEffectivePendingLimit((HEALTHY_Q + BUSY_Q) / 2, -1, -1);
    assertTrue(mid > MIN && mid < MAX);
  }

  @Test
  public void testProcessingTimeGateForcesMin() {
    Configuration conf = baseConf();
    conf.setLong(
        DFSConfigKeys.DFS_NAMENODE_DECOMMISSION_BACKOFF_MONITOR_BUSY_RPC_PROCESSING_TIME_MS,
        50);
    DatanodeAdminAdaptiveBackoffMonitor m = newMonitor(conf, null);
    // Healthy queue would give MAX, but the processing-time gate trips.
    assertEquals(MIN, m.computeEffectivePendingLimit(0, 60, -1));
    // Below the threshold it has no effect.
    assertEquals(MAX, m.computeEffectivePendingLimit(0, 40, -1));
  }

  @Test
  public void testLowRedundancyCapForcesMin() {
    Configuration conf = baseConf();
    conf.setLong(
        DFSConfigKeys.DFS_NAMENODE_DECOMMISSION_BACKOFF_MONITOR_MAX_LOW_REDUNDANCY_BLOCKS,
        1000);
    DatanodeAdminAdaptiveBackoffMonitor m = newMonitor(conf, null);
    assertEquals(MIN, m.computeEffectivePendingLimit(0, -1, 2000));
    assertEquals(MAX, m.computeEffectivePendingLimit(0, -1, 500));
  }

  @Test
  public void testBrokenThresholdsDisableAdaptation() {
    Configuration conf = baseConf();
    // busy must be strictly greater than healthy; make it invalid.
    conf.setInt(
        DFSConfigKeys.DFS_NAMENODE_DECOMMISSION_BACKOFF_MONITOR_BUSY_RPC_QUEUE_LENGTH,
        HEALTHY_Q);
    DatanodeAdminAdaptiveBackoffMonitor m = newMonitor(conf, null);
    assertFalse("adaptive pacing should be disabled on invalid thresholds",
        m.isAdaptiveEnabled());
  }

  @Test
  public void testMinLimitFloorIsClampedPositive() {
    Configuration conf = baseConf();
    conf.setInt(
        DFSConfigKeys.DFS_NAMENODE_DECOMMISSION_BACKOFF_MONITOR_MIN_PENDING_LIMIT, 0);
    DatanodeAdminAdaptiveBackoffMonitor m = newMonitor(conf, null);
    assertEquals(
        DFSConfigKeys.DFS_NAMENODE_DECOMMISSION_BACKOFF_MONITOR_MIN_PENDING_LIMIT_DEFAULT,
        m.getMinPendingLimit());
  }

  @Test
  public void testMaxLimitInheritsConfiguredPendingLimitWhenUnset() {
    Configuration conf = baseConf();
    // Do not set max.pending.limit; tune the parent's pending.limit above the
    // stock 10000. The ceiling must inherit that tuned value, not a constant,
    // so enabling adaptive pacing never lowers peak throughput.
    conf.unset(DFSConfigKeys.DFS_NAMENODE_DECOMMISSION_BACKOFF_MONITOR_MAX_PENDING_LIMIT);
    conf.setInt(
        DFSConfigKeys.DFS_NAMENODE_DECOMMISSION_BACKOFF_MONITOR_PENDING_LIMIT, 50000);
    DatanodeAdminAdaptiveBackoffMonitor m = newMonitor(conf, null);
    assertEquals(50000, m.getMaxPendingLimit());
  }

  @Test
  public void testExplicitMaxLimitOverridesPendingLimit() {
    Configuration conf = baseConf();
    conf.setInt(
        DFSConfigKeys.DFS_NAMENODE_DECOMMISSION_BACKOFF_MONITOR_MAX_PENDING_LIMIT, 8000);
    conf.setInt(
        DFSConfigKeys.DFS_NAMENODE_DECOMMISSION_BACKOFF_MONITOR_PENDING_LIMIT, 50000);
    DatanodeAdminAdaptiveBackoffMonitor m = newMonitor(conf, null);
    assertEquals(8000, m.getMaxPendingLimit());
  }

  @Test
  public void testRunAppliesLimitWhenEnabled() {
    Configuration conf = baseConf();
    conf.setInt(
        DFSConfigKeys.DFS_NAMENODE_DECOMMISSION_BACKOFF_MONITOR_MAX_PENDING_LIMIT, 9000);
    conf.setInt(
        DFSConfigKeys.DFS_NAMENODE_DECOMMISSION_BACKOFF_MONITOR_PENDING_LIMIT, 7777);
    FSNamesystem fsn = mock(FSNamesystem.class);
    when(fsn.isRunning()).thenReturn(false); // short-circuit super.run()
    when(fsn.getRpcCallQueueLength()).thenReturn(0L); // healthy
    DatanodeAdminAdaptiveBackoffMonitor m = newMonitor(conf, fsn);
    m.run();
    assertEquals(9000, m.getPendingRepLimit());
  }

  @Test
  public void testRunLeavesLimitWhenDisabled() {
    Configuration conf = baseConf();
    conf.setBoolean(
        DFSConfigKeys.DFS_NAMENODE_DECOMMISSION_BACKOFF_MONITOR_ADAPTIVE_ENABLED, false);
    conf.setInt(
        DFSConfigKeys.DFS_NAMENODE_DECOMMISSION_BACKOFF_MONITOR_PENDING_LIMIT, 7777);
    FSNamesystem fsn = mock(FSNamesystem.class);
    when(fsn.isRunning()).thenReturn(false);
    DatanodeAdminAdaptiveBackoffMonitor m = newMonitor(conf, fsn);
    m.run();
    assertEquals(7777, m.getPendingRepLimit());
  }

  @Test
  public void testRunFailsOpenWhenRpcServerUnavailable() {
    Configuration conf = baseConf();
    conf.setInt(
        DFSConfigKeys.DFS_NAMENODE_DECOMMISSION_BACKOFF_MONITOR_PENDING_LIMIT, 7777);
    FSNamesystem fsn = mock(FSNamesystem.class);
    when(fsn.isRunning()).thenReturn(false);
    when(fsn.getRpcCallQueueLength()).thenReturn(-1L); // not wired yet
    DatanodeAdminAdaptiveBackoffMonitor m = newMonitor(conf, fsn);
    m.run();
    assertEquals(7777, m.getPendingRepLimit());
  }
}
