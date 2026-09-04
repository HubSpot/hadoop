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
 * HubSpot: pure-unit tests for the feedback controller of
 * {@link DatanodeAdminAdaptiveBackoffMonitor}. These exercise the integral
 * control step, the EWMA smoothing, and the {@code run()} wiring directly,
 * without a MiniDFSCluster.
 */
public class TestDatanodeAdminAdaptiveBackoffMonitor {

  private static final int MIN = 100;
  private static final int MAX = 10000;
  private static final long HEALTHY_MS = 1;
  private static final long BUSY_MS = 50;
  private static final int UP = 500;
  private static final int DOWN = 2000;

  private Configuration baseConf() {
    Configuration conf = new Configuration();
    conf.setBoolean(
        DFSConfigKeys.DFS_NAMENODE_DECOMMISSION_BACKOFF_MONITOR_ADAPTIVE_ENABLED, true);
    conf.setInt(
        DFSConfigKeys.DFS_NAMENODE_DECOMMISSION_BACKOFF_MONITOR_MIN_PENDING_LIMIT, MIN);
    conf.setInt(
        DFSConfigKeys.DFS_NAMENODE_DECOMMISSION_BACKOFF_MONITOR_MAX_PENDING_LIMIT, MAX);
    conf.setLong(
        DFSConfigKeys.DFS_NAMENODE_DECOMMISSION_BACKOFF_MONITOR_HEALTHY_RPC_QUEUE_TIME_MS,
        HEALTHY_MS);
    conf.setLong(
        DFSConfigKeys.DFS_NAMENODE_DECOMMISSION_BACKOFF_MONITOR_BUSY_RPC_QUEUE_TIME_MS,
        BUSY_MS);
    conf.setInt(
        DFSConfigKeys.DFS_NAMENODE_DECOMMISSION_BACKOFF_MONITOR_RAMP_UP_STEP, UP);
    conf.setInt(
        DFSConfigKeys.DFS_NAMENODE_DECOMMISSION_BACKOFF_MONITOR_RAMP_DOWN_STEP, DOWN);
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

  // ---- controller step (pure) ----

  @Test
  public void testRampsUpWhenHealthy() {
    DatanodeAdminAdaptiveBackoffMonitor m = newMonitor(baseConf(), null);
    assertEquals(600, m.nextControllerLimit(100, 0, false));
    assertEquals(600, m.nextControllerLimit(100, HEALTHY_MS, false)); // boundary
    // clamps at the ceiling
    assertEquals(MAX, m.nextControllerLimit(9800, 0, false));
  }

  @Test
  public void testRampsDownWhenBusy() {
    DatanodeAdminAdaptiveBackoffMonitor m = newMonitor(baseConf(), null);
    assertEquals(8000, m.nextControllerLimit(10000, 100, false));
    assertEquals(8000, m.nextControllerLimit(10000, BUSY_MS, false)); // boundary
    // clamps at the floor
    assertEquals(MIN, m.nextControllerLimit(500, 100, false));
  }

  @Test
  public void testHoldsInsideDeadband() {
    DatanodeAdminAdaptiveBackoffMonitor m = newMonitor(baseConf(), null);
    // strictly between healthy (1) and busy (50): hold
    assertEquals(5000, m.nextControllerLimit(5000, 25, false));
  }

  @Test
  public void testForceMinOverridesHealthySignal() {
    DatanodeAdminAdaptiveBackoffMonitor m = newMonitor(baseConf(), null);
    assertEquals(MIN, m.nextControllerLimit(10000, 0 /* healthy */, true));
  }

  @Test
  public void testConvergesUpThenHolds() {
    DatanodeAdminAdaptiveBackoffMonitor m = newMonitor(baseConf(), null);
    int limit = MIN;
    for (int i = 0; i < 100 && limit < MAX; i++) {
      limit = m.nextControllerLimit(limit, 0, false);
    }
    assertEquals(MAX, limit);
    // once at the ceiling a healthy signal keeps it pinned (no overshoot)
    assertEquals(MAX, m.nextControllerLimit(limit, 0, false));
  }

  // ---- EWMA smoothing (pure) ----

  @Test
  public void testSmoothingDisabledByDefaultReturnsSample() {
    DatanodeAdminAdaptiveBackoffMonitor m = newMonitor(baseConf(), null);
    assertEquals(40.0, m.smoothSignal(-1.0, 40), 0.0001); // first sample
    assertEquals(40.0, m.smoothSignal(30.0, 40), 0.0001); // alpha == 1.0
  }

  @Test
  public void testSmoothingWithWindow() {
    Configuration conf = baseConf();
    // interval defaults to 30s; a 30s window => alpha = 30000/(30000+30000) = 0.5
    conf.setInt(DFSConfigKeys.DFS_NAMENODE_DECOMMISSION_INTERVAL_KEY, 30);
    conf.setLong(
        DFSConfigKeys.DFS_NAMENODE_DECOMMISSION_BACKOFF_MONITOR_SIGNAL_EMA_WINDOW_MS,
        30_000);
    DatanodeAdminAdaptiveBackoffMonitor m = newMonitor(conf, null);
    assertEquals(100.0, m.smoothSignal(-1.0, 100), 0.0001); // seed
    assertEquals(50.0, m.smoothSignal(100.0, 0), 0.0001);   // 0.5*0 + 0.5*100
  }

  // ---- config validation ----

  @Test
  public void testBrokenThresholdsDisableAdaptation() {
    Configuration conf = baseConf();
    // busy must be strictly greater than healthy; make it invalid.
    conf.setLong(
        DFSConfigKeys.DFS_NAMENODE_DECOMMISSION_BACKOFF_MONITOR_BUSY_RPC_QUEUE_TIME_MS,
        HEALTHY_MS);
    DatanodeAdminAdaptiveBackoffMonitor m = newMonitor(conf, null);
    assertFalse(m.isAdaptiveEnabled());
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

  // ---- run() wiring ----

  @Test
  public void testRunSeedsAndRampsUpWhenHealthy() {
    Configuration conf = baseConf();
    conf.setInt(
        DFSConfigKeys.DFS_NAMENODE_DECOMMISSION_BACKOFF_MONITOR_PENDING_LIMIT, 5000);
    FSNamesystem fsn = mock(FSNamesystem.class);
    when(fsn.isRunning()).thenReturn(false); // short-circuit super.run()
    when(fsn.getAvgRpcQueueTimeMs()).thenReturn(0L); // healthy
    DatanodeAdminAdaptiveBackoffMonitor m = newMonitor(conf, fsn);
    m.run();
    // seeds the controller from the current limit (5000), then ramps up once
    assertEquals(5500, m.getPendingRepLimit());
  }

  @Test
  public void testRunRampsDownWhenBusy() {
    Configuration conf = baseConf();
    conf.setInt(
        DFSConfigKeys.DFS_NAMENODE_DECOMMISSION_BACKOFF_MONITOR_PENDING_LIMIT, 5000);
    FSNamesystem fsn = mock(FSNamesystem.class);
    when(fsn.isRunning()).thenReturn(false);
    when(fsn.getAvgRpcQueueTimeMs()).thenReturn(100L); // busy
    DatanodeAdminAdaptiveBackoffMonitor m = newMonitor(conf, fsn);
    m.run();
    assertEquals(3000, m.getPendingRepLimit());
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
  public void testRunFailsOpenWhenQueueTimeUnavailable() {
    Configuration conf = baseConf();
    conf.setInt(
        DFSConfigKeys.DFS_NAMENODE_DECOMMISSION_BACKOFF_MONITOR_PENDING_LIMIT, 7777);
    FSNamesystem fsn = mock(FSNamesystem.class);
    when(fsn.isRunning()).thenReturn(false);
    when(fsn.getAvgRpcQueueTimeMs()).thenReturn(-1L); // metrics not available yet
    DatanodeAdminAdaptiveBackoffMonitor m = newMonitor(conf, fsn);
    m.run();
    assertEquals(7777, m.getPendingRepLimit());
  }
}
