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

import org.apache.hadoop.classification.VisibleForTesting;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.server.namenode.FSNamesystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HubSpot: an adaptive variant of {@link DatanodeAdminBackoffMonitor}.
 *
 * <p>The upstream backoff monitor paces decommission-driven replication off a
 * fixed pending-replication limit ({@code pendingRepLimit}). This subclass makes
 * that limit dynamic: at the start of each monitor tick it samples NameNode load
 * and scales the effective limit between a configured minimum and maximum, so
 * decommission replication runs aggressively when the NameNode is healthy and
 * backs off when it is busy serving foreground traffic.
 *
 * <p>The primary health signal is the client RPC call-queue length, which
 * reflects real foreground contention and - unlike block-queue counts - is not
 * inflated by the monitor scheduling its own decommission work. An optional
 * average-RPC-processing-time gate and an optional low-redundancy-block ceiling
 * act as secondary hard caps.
 *
 * <p>All behavior is gated by
 * {@code dfs.namenode.decommission.backoff.monitor.adaptive.enabled} (default
 * false). While disabled this class behaves identically to
 * {@link DatanodeAdminBackoffMonitor}. All thresholds are runtime-reconfigurable
 * via {@code hdfs dfsadmin -reconfig}.
 */
public class HubSpotDatanodeAdminBackoffMonitor
    extends DatanodeAdminBackoffMonitor {

  private static final Logger LOG =
      LoggerFactory.getLogger(HubSpotDatanodeAdminBackoffMonitor.class);

  /** Master switch. When false, behaves like the stock backoff monitor. */
  private volatile boolean adaptiveEnabled;
  /** Floor on the effective pending limit (applied when busy). Always &gt; 0. */
  private volatile int minPendingLimit;
  /** Ceiling on the effective pending limit (applied when healthy). */
  private volatile int maxPendingLimit;
  /** RPC call-queue length at/below which we ramp to {@link #maxPendingLimit}. */
  private volatile int healthyRpcQueueLength;
  /** RPC call-queue length at/above which we clamp to {@link #minPendingLimit}. */
  private volatile int busyRpcQueueLength;
  /** Avg RPC processing time (ms) forcing the floor; &lt; 0 disables the gate. */
  private volatile long busyRpcProcessingTimeMs;
  /** Low-redundancy block ceiling forcing the floor; &lt; 0 disables the cap. */
  private volatile long maxLowRedundancyBlocks;

  HubSpotDatanodeAdminBackoffMonitor() {
  }

  @Override
  protected void processConf() {
    super.processConf();

    this.adaptiveEnabled = conf.getBoolean(
        DFSConfigKeys.DFS_NAMENODE_DECOMMISSION_BACKOFF_MONITOR_ADAPTIVE_ENABLED,
        DFSConfigKeys
            .DFS_NAMENODE_DECOMMISSION_BACKOFF_MONITOR_ADAPTIVE_ENABLED_DEFAULT);
    this.minPendingLimit = conf.getInt(
        DFSConfigKeys.DFS_NAMENODE_DECOMMISSION_BACKOFF_MONITOR_MIN_PENDING_LIMIT,
        DFSConfigKeys
            .DFS_NAMENODE_DECOMMISSION_BACKOFF_MONITOR_MIN_PENDING_LIMIT_DEFAULT);
    this.maxPendingLimit = conf.getInt(
        DFSConfigKeys.DFS_NAMENODE_DECOMMISSION_BACKOFF_MONITOR_MAX_PENDING_LIMIT,
        DFSConfigKeys
            .DFS_NAMENODE_DECOMMISSION_BACKOFF_MONITOR_MAX_PENDING_LIMIT_DEFAULT);
    this.healthyRpcQueueLength = conf.getInt(
        DFSConfigKeys
            .DFS_NAMENODE_DECOMMISSION_BACKOFF_MONITOR_HEALTHY_RPC_QUEUE_LENGTH,
        DFSConfigKeys
            .DFS_NAMENODE_DECOMMISSION_BACKOFF_MONITOR_HEALTHY_RPC_QUEUE_LENGTH_DEFAULT);
    this.busyRpcQueueLength = conf.getInt(
        DFSConfigKeys
            .DFS_NAMENODE_DECOMMISSION_BACKOFF_MONITOR_BUSY_RPC_QUEUE_LENGTH,
        DFSConfigKeys
            .DFS_NAMENODE_DECOMMISSION_BACKOFF_MONITOR_BUSY_RPC_QUEUE_LENGTH_DEFAULT);
    this.busyRpcProcessingTimeMs = conf.getLong(
        DFSConfigKeys
            .DFS_NAMENODE_DECOMMISSION_BACKOFF_MONITOR_BUSY_RPC_PROCESSING_TIME_MS,
        DFSConfigKeys
            .DFS_NAMENODE_DECOMMISSION_BACKOFF_MONITOR_BUSY_RPC_PROCESSING_TIME_MS_DEFAULT);
    this.maxLowRedundancyBlocks = conf.getLong(
        DFSConfigKeys
            .DFS_NAMENODE_DECOMMISSION_BACKOFF_MONITOR_MAX_LOW_REDUNDANCY_BLOCKS,
        DFSConfigKeys
            .DFS_NAMENODE_DECOMMISSION_BACKOFF_MONITOR_MAX_LOW_REDUNDANCY_BLOCKS_DEFAULT);

    validateAndFixup();

    LOG.info("Initialized adaptive backoff decommission monitor. enabled={}, "
            + "pendingLimit=[{}, {}], rpcQueueLength healthy<={} busy>={}, "
            + "busyProcessingTimeMs={}, maxLowRedundancyBlocks={}",
        adaptiveEnabled, minPendingLimit, maxPendingLimit, healthyRpcQueueLength,
        busyRpcQueueLength, busyRpcProcessingTimeMs, maxLowRedundancyBlocks);
  }

  /**
   * Clamp any nonsensical configuration back to safe defaults rather than
   * letting it break decommission pacing. Mirrors the defensive style of the
   * parent {@code processConf}.
   */
  private void validateAndFixup() {
    if (minPendingLimit < 1) {
      LOG.error("{} must be greater than zero, was {}. Defaulting to {}.",
          DFSConfigKeys.DFS_NAMENODE_DECOMMISSION_BACKOFF_MONITOR_MIN_PENDING_LIMIT,
          minPendingLimit,
          DFSConfigKeys
              .DFS_NAMENODE_DECOMMISSION_BACKOFF_MONITOR_MIN_PENDING_LIMIT_DEFAULT);
      minPendingLimit = DFSConfigKeys
          .DFS_NAMENODE_DECOMMISSION_BACKOFF_MONITOR_MIN_PENDING_LIMIT_DEFAULT;
    }
    if (maxPendingLimit < minPendingLimit) {
      LOG.error("{} ({}) must be >= {} ({}). Raising it to the minimum.",
          DFSConfigKeys.DFS_NAMENODE_DECOMMISSION_BACKOFF_MONITOR_MAX_PENDING_LIMIT,
          maxPendingLimit,
          DFSConfigKeys.DFS_NAMENODE_DECOMMISSION_BACKOFF_MONITOR_MIN_PENDING_LIMIT,
          minPendingLimit);
      maxPendingLimit = minPendingLimit;
    }
    if (busyRpcQueueLength <= healthyRpcQueueLength) {
      LOG.error("{} ({}) must be greater than {} ({}). Disabling adaptive pacing.",
          DFSConfigKeys.DFS_NAMENODE_DECOMMISSION_BACKOFF_MONITOR_BUSY_RPC_QUEUE_LENGTH,
          busyRpcQueueLength,
          DFSConfigKeys
              .DFS_NAMENODE_DECOMMISSION_BACKOFF_MONITOR_HEALTHY_RPC_QUEUE_LENGTH,
          healthyRpcQueueLength);
      // Leave thresholds as-is but do not adapt on a broken configuration.
      adaptiveEnabled = false;
    }
  }

  @Override
  public void run() {
    if (adaptiveEnabled) {
      try {
        setPendingRepLimit(computeAdaptivePendingLimit());
      } catch (Exception e) {
        // Never let adaptation break the decommission loop; fall back to
        // whatever limit is currently set.
        LOG.warn("Failed to compute adaptive pending replication limit; "
            + "leaving it at {}.", getPendingRepLimit(), e);
      }
    }
    super.run();
  }

  /**
   * Sample NameNode load and map it onto an effective pending limit for this
   * tick. Fails open (returns the current limit) if the RPC server is not yet
   * wired up, which can happen briefly in some MiniDFSCluster / standby paths.
   */
  private int computeAdaptivePendingLimit() {
    final FSNamesystem fsn = (FSNamesystem) namesystem;
    final long rpcQueueLength = fsn.getRpcCallQueueLength();
    if (rpcQueueLength < 0) {
      return getPendingRepLimit();
    }
    final long avgProcessingTimeMs =
        busyRpcProcessingTimeMs >= 0 ? fsn.getAvgRpcProcessingTimeMs() : -1;
    final long adjustedLowRedundancy =
        maxLowRedundancyBlocks >= 0 ? sampleAdjustedLowRedundancyBlocks() : -1;
    return computeEffectivePendingLimit(rpcQueueLength, avgProcessingTimeMs,
        adjustedLowRedundancy);
  }

  /**
   * Approximate the count of low-redundancy blocks that are NOT a result of our
   * own decommission scheduling, by subtracting the blocks currently pending
   * reconstruction (a proxy for in-flight scheduled work). Used only as a hard
   * safety ceiling, never as the primary pacing signal.
   */
  private long sampleAdjustedLowRedundancyBlocks() {
    long lowRedundancy = blockManager.getLowRedundancyBlocksCount();
    long ourInflight = blockManager.getPendingReconstructionBlocksCount();
    return Math.max(0, lowRedundancy - ourInflight);
  }

  /**
   * Pure mapping from sampled load signals to an effective pending limit.
   * Package-visible so it can be unit-tested without running the full,
   * lock-holding {@link #run()}.
   *
   * @param rpcQueueLength current client RPC call-queue length (&gt;= 0)
   * @param avgRpcProcessingTimeMs sampled avg RPC processing time in ms, or
   *        &lt; 0 if not sampled / the gate is disabled
   * @param adjustedLowRedundancyBlocks low-redundancy blocks minus our own
   *        in-flight work, or &lt; 0 if not sampled / the cap is disabled
   * @return the effective pending replication limit, within [min, max]
   */
  @VisibleForTesting
  int computeEffectivePendingLimit(long rpcQueueLength,
      long avgRpcProcessingTimeMs, long adjustedLowRedundancyBlocks) {
    int effective;
    if (rpcQueueLength <= healthyRpcQueueLength) {
      effective = maxPendingLimit;
    } else if (rpcQueueLength >= busyRpcQueueLength) {
      effective = minPendingLimit;
    } else {
      // Linear interpolation across the [healthy, busy] deadband. The span is
      // guaranteed positive here because validateAndFixup() disables adaptation
      // unless busyRpcQueueLength > healthyRpcQueueLength.
      long span = (long) busyRpcQueueLength - healthyRpcQueueLength;
      long range = (long) maxPendingLimit - minPendingLimit;
      long reduction = range * (rpcQueueLength - healthyRpcQueueLength) / span;
      effective = (int) (maxPendingLimit - reduction);
    }

    // Secondary hard cap: sustained high average RPC processing time.
    if (busyRpcProcessingTimeMs >= 0 && avgRpcProcessingTimeMs >= 0
        && avgRpcProcessingTimeMs >= busyRpcProcessingTimeMs) {
      effective = minPendingLimit;
    }

    // Secondary hard cap: too many low-redundancy blocks not attributable to
    // our own decommission scheduling.
    if (maxLowRedundancyBlocks >= 0 && adjustedLowRedundancyBlocks >= 0
        && adjustedLowRedundancyBlocks > maxLowRedundancyBlocks) {
      effective = minPendingLimit;
    }

    return Math.max(minPendingLimit, Math.min(maxPendingLimit, effective));
  }

  // ------------------------------------------------------------------
  // Runtime-reconfigurable knobs (mirrors get/setPendingRepLimit style).
  // Fields are volatile so the monitor thread observes updates without locking.
  // ------------------------------------------------------------------

  @VisibleForTesting
  public boolean isAdaptiveEnabled() {
    return adaptiveEnabled;
  }

  public void setAdaptiveEnabled(boolean adaptiveEnabled) {
    this.adaptiveEnabled = adaptiveEnabled;
  }

  @VisibleForTesting
  public int getMinPendingLimit() {
    return minPendingLimit;
  }

  public void setMinPendingLimit(int minPendingLimit) {
    this.minPendingLimit = minPendingLimit;
  }

  @VisibleForTesting
  public int getMaxPendingLimit() {
    return maxPendingLimit;
  }

  public void setMaxPendingLimit(int maxPendingLimit) {
    this.maxPendingLimit = maxPendingLimit;
  }

  @VisibleForTesting
  public int getHealthyRpcQueueLength() {
    return healthyRpcQueueLength;
  }

  public void setHealthyRpcQueueLength(int healthyRpcQueueLength) {
    this.healthyRpcQueueLength = healthyRpcQueueLength;
  }

  @VisibleForTesting
  public int getBusyRpcQueueLength() {
    return busyRpcQueueLength;
  }

  public void setBusyRpcQueueLength(int busyRpcQueueLength) {
    this.busyRpcQueueLength = busyRpcQueueLength;
  }

  @VisibleForTesting
  public long getBusyRpcProcessingTimeMs() {
    return busyRpcProcessingTimeMs;
  }

  public void setBusyRpcProcessingTimeMs(long busyRpcProcessingTimeMs) {
    this.busyRpcProcessingTimeMs = busyRpcProcessingTimeMs;
  }

  @VisibleForTesting
  public long getMaxLowRedundancyBlocks() {
    return maxLowRedundancyBlocks;
  }

  public void setMaxLowRedundancyBlocks(long maxLowRedundancyBlocks) {
    this.maxLowRedundancyBlocks = maxLowRedundancyBlocks;
  }
}
