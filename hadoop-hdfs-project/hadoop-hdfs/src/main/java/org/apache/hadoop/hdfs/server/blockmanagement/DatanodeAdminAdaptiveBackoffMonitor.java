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
 * HubSpot: an adaptive variant of {@link DatanodeAdminBackoffMonitor} that paces
 * decommission-driven replication with a closed-loop feedback controller.
 *
 * <p>The upstream backoff monitor paces off a fixed pending-replication limit
 * ({@code pendingRepLimit}). This subclass makes that limit dynamic: each monitor
 * tick it samples a NameNode-load signal and nudges the effective limit up or
 * down, so decommission runs aggressively when the NameNode is healthy and yields
 * to foreground traffic when it is busy.
 *
 * <p><b>Signal.</b> The load signal is the <em>average RPC queue time</em> - the
 * time client calls wait in the call queue before a handler picks them up -
 * exposed by {@link FSNamesystem#getAvgRpcQueueTimeMs()}. This is a windowed mean
 * maintained by the RPC metrics system (a genuine sustained-contention measure),
 * not an instantaneous point sample, and unlike block-queue counts it is not
 * inflated by the monitor's own decommission scheduling. It can optionally be
 * further smoothed across ticks with an EWMA.
 *
 * <p><b>Controller.</b> Rather than mapping the signal to an absolute target, the
 * limit is an integral (accumulator) control variable, in the spirit of HBase's
 * {@code FeedbackAdaptiveRateLimiter} and Janert's <i>Feedback Control for
 * Computer Systems</i>:
 * <ul>
 *   <li>signal at/below the healthy threshold -&gt; ramp the limit UP by
 *       {@code rampUpStep};</li>
 *   <li>signal at/above the busy threshold -&gt; ramp the limit DOWN by
 *       {@code rampDownStep};</li>
 *   <li>in between (the deadband) -&gt; hold (this is the hysteresis that stops
 *       tick-to-tick flapping).</li>
 * </ul>
 * Ramp-down is larger than ramp-up by default (fast to yield, slow to re-expand,
 * like AIMD), and the limit is always clamped to {@code [min, max]}. Two optional
 * hard overrides - sustained RPC processing time and a low-redundancy-block
 * ceiling - slam the limit straight to the floor for immediate protection.
 *
 * <p>All behavior is gated by
 * {@code dfs.namenode.decommission.backoff.monitor.adaptive.enabled} (default
 * false); while disabled this class behaves identically to
 * {@link DatanodeAdminBackoffMonitor}. All thresholds are runtime-reconfigurable
 * via {@code hdfs dfsadmin -reconfig}.
 */
public class DatanodeAdminAdaptiveBackoffMonitor
    extends DatanodeAdminBackoffMonitor {

  private static final Logger LOG =
      LoggerFactory.getLogger(DatanodeAdminAdaptiveBackoffMonitor.class);

  /** Master switch. When false, behaves like the stock backoff monitor. */
  private volatile boolean adaptiveEnabled;
  /** Floor on the effective pending limit (applied when busy). Always &gt; 0. */
  private volatile int minPendingLimit;
  /** Ceiling on the effective pending limit (applied when healthy). */
  private volatile int maxPendingLimit;
  /** Avg RPC queue time (ms) at/below which the controller ramps up. */
  private volatile long healthyRpcQueueTimeMs;
  /** Avg RPC queue time (ms) at/above which the controller ramps down. */
  private volatile long busyRpcQueueTimeMs;
  /** Blocks added to the limit per tick while healthy. Always &gt; 0. */
  private volatile int rampUpStep;
  /** Blocks removed from the limit per tick while busy. Always &gt; 0. */
  private volatile int rampDownStep;
  /** EWMA window (ms) for the signal; &lt;= 0 disables smoothing. */
  private volatile long signalEmaWindowMs;
  /** Avg RPC processing time (ms) forcing the floor; &lt; 0 disables the gate. */
  private volatile long busyRpcProcessingTimeMs;
  /** Low-redundancy block ceiling forcing the floor; &lt; 0 disables the cap. */
  private volatile long maxLowRedundancyBlocks;

  /** Monitor tick interval in ms, used to derive the EWMA alpha. */
  private volatile long tickIntervalMs;
  /** Derived EWMA smoothing factor; 1.0 means "no smoothing". */
  private volatile double emaAlpha = 1.0;

  /**
   * The controller's integral state: the effective limit chosen last tick.
   * {@code < 0} means "not seeded yet" (the first tick seeds it from the current
   * pending limit so enabling the feature is smooth, not a step change).
   */
  private volatile int controllerLimit = -1;
  /** EWMA state for the signal; {@code < 0} means "not seeded yet". */
  private volatile double signalEma = -1.0;

  DatanodeAdminAdaptiveBackoffMonitor() {
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
    // Default the healthy-state ceiling to whatever the parent resolved for
    // pending.limit (read in super.processConf()), NOT a hardcoded constant, so
    // a cluster that tuned pending.limit above the stock default does not
    // silently lose peak throughput when adaptive pacing is enabled.
    this.maxPendingLimit = conf.getInt(
        DFSConfigKeys.DFS_NAMENODE_DECOMMISSION_BACKOFF_MONITOR_MAX_PENDING_LIMIT,
        getPendingRepLimit());
    this.healthyRpcQueueTimeMs = conf.getLong(
        DFSConfigKeys
            .DFS_NAMENODE_DECOMMISSION_BACKOFF_MONITOR_HEALTHY_RPC_QUEUE_TIME_MS,
        DFSConfigKeys
            .DFS_NAMENODE_DECOMMISSION_BACKOFF_MONITOR_HEALTHY_RPC_QUEUE_TIME_MS_DEFAULT);
    this.busyRpcQueueTimeMs = conf.getLong(
        DFSConfigKeys
            .DFS_NAMENODE_DECOMMISSION_BACKOFF_MONITOR_BUSY_RPC_QUEUE_TIME_MS,
        DFSConfigKeys
            .DFS_NAMENODE_DECOMMISSION_BACKOFF_MONITOR_BUSY_RPC_QUEUE_TIME_MS_DEFAULT);
    this.rampUpStep = conf.getInt(
        DFSConfigKeys.DFS_NAMENODE_DECOMMISSION_BACKOFF_MONITOR_RAMP_UP_STEP,
        DFSConfigKeys
            .DFS_NAMENODE_DECOMMISSION_BACKOFF_MONITOR_RAMP_UP_STEP_DEFAULT);
    this.rampDownStep = conf.getInt(
        DFSConfigKeys.DFS_NAMENODE_DECOMMISSION_BACKOFF_MONITOR_RAMP_DOWN_STEP,
        DFSConfigKeys
            .DFS_NAMENODE_DECOMMISSION_BACKOFF_MONITOR_RAMP_DOWN_STEP_DEFAULT);
    this.signalEmaWindowMs = conf.getLong(
        DFSConfigKeys
            .DFS_NAMENODE_DECOMMISSION_BACKOFF_MONITOR_SIGNAL_EMA_WINDOW_MS,
        DFSConfigKeys
            .DFS_NAMENODE_DECOMMISSION_BACKOFF_MONITOR_SIGNAL_EMA_WINDOW_MS_DEFAULT);
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
    this.tickIntervalMs = 1000L * conf.getInt(
        DFSConfigKeys.DFS_NAMENODE_DECOMMISSION_INTERVAL_KEY,
        DFSConfigKeys.DFS_NAMENODE_DECOMMISSION_INTERVAL_DEFAULT);

    validateAndFixup();
    recomputeEmaAlpha();

    LOG.info("Initialized adaptive backoff decommission monitor. enabled={}, "
            + "pendingLimit=[{}, {}], rpcQueueTimeMs healthy<={} busy>={}, "
            + "rampStep up={} down={}, signalEmaWindowMs={} (alpha={}), "
            + "busyProcessingTimeMs={}, maxLowRedundancyBlocks={}",
        adaptiveEnabled, minPendingLimit, maxPendingLimit, healthyRpcQueueTimeMs,
        busyRpcQueueTimeMs, rampUpStep, rampDownStep, signalEmaWindowMs, emaAlpha,
        busyRpcProcessingTimeMs, maxLowRedundancyBlocks);
  }

  /**
   * Clamp any nonsensical configuration back to safe defaults rather than
   * letting it break decommission pacing. Mirrors the defensive style of the
   * parent {@code processConf}.
   *
   * <p>Runs at startup (from {@link #processConf()}) and is re-run by
   * {@link DatanodeAdminManager} after every runtime reconfiguration, so the
   * cross-field invariants below (max &gt;= min, busy &gt; healthy, and
   * disabling adaptation on a broken threshold pair) hold identically whether a
   * value came from config at boot or from {@code hdfs dfsadmin -reconfig}.
   * Idempotent, and logs whenever it corrects something.
   */
  void validateAndFixup() {
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
    if (rampUpStep < 1) {
      rampUpStep = DFSConfigKeys
          .DFS_NAMENODE_DECOMMISSION_BACKOFF_MONITOR_RAMP_UP_STEP_DEFAULT;
    }
    if (rampDownStep < 1) {
      rampDownStep = DFSConfigKeys
          .DFS_NAMENODE_DECOMMISSION_BACKOFF_MONITOR_RAMP_DOWN_STEP_DEFAULT;
    }
    if (signalEmaWindowMs < 0) {
      signalEmaWindowMs = 0;
    }
    if (tickIntervalMs < 1) {
      tickIntervalMs =
          1000L * DFSConfigKeys.DFS_NAMENODE_DECOMMISSION_INTERVAL_DEFAULT;
    }
    if (busyRpcQueueTimeMs <= healthyRpcQueueTimeMs) {
      LOG.error("{} ({}) must be greater than {} ({}). Disabling adaptive pacing.",
          DFSConfigKeys.DFS_NAMENODE_DECOMMISSION_BACKOFF_MONITOR_BUSY_RPC_QUEUE_TIME_MS,
          busyRpcQueueTimeMs,
          DFSConfigKeys.DFS_NAMENODE_DECOMMISSION_BACKOFF_MONITOR_HEALTHY_RPC_QUEUE_TIME_MS,
          healthyRpcQueueTimeMs);
      adaptiveEnabled = false;
    }
  }

  /**
   * alpha = tick / (window + tick): the fraction of each new sample folded into
   * the EWMA. A window of 0 yields alpha == 1.0, i.e. no smoothing (the sample,
   * which is itself an RPC-metrics windowed mean, is used directly).
   */
  private void recomputeEmaAlpha() {
    this.emaAlpha = signalEmaWindowMs <= 0 ? 1.0
        : (double) tickIntervalMs / (signalEmaWindowMs + tickIntervalMs);
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
   * Sample the load signal, smooth it, and advance the controller by one tick.
   * Fails open (returns the current limit without advancing controller state) if
   * the RPC server is not yet wired up, which can happen briefly in some
   * MiniDFSCluster / standby paths.
   */
  private int computeAdaptivePendingLimit() {
    final FSNamesystem fsn = (FSNamesystem) namesystem;
    final long queueTimeMs = fsn.getAvgRpcQueueTimeMs();
    if (queueTimeMs < 0) {
      return getPendingRepLimit();
    }
    // Seed the integral state from the current limit so enabling the controller
    // is smooth rather than a step to some default.
    if (controllerLimit < 0) {
      controllerLimit =
          Math.max(minPendingLimit, Math.min(maxPendingLimit, getPendingRepLimit()));
    }
    double smoothed = smoothSignal(signalEma, queueTimeMs);
    signalEma = smoothed;
    controllerLimit =
        nextControllerLimit(controllerLimit, smoothed, safetyOverrideTripped(fsn));
    return controllerLimit;
  }

  /**
   * Whether an optional hard override should force the limit to the floor this
   * tick: sustained high RPC processing time, or too many low-redundancy blocks
   * not attributable to our own decommission scheduling.
   */
  private boolean safetyOverrideTripped(FSNamesystem fsn) {
    if (busyRpcProcessingTimeMs >= 0) {
      long avgProcessingTimeMs = fsn.getAvgRpcProcessingTimeMs();
      if (avgProcessingTimeMs >= 0 && avgProcessingTimeMs >= busyRpcProcessingTimeMs) {
        return true;
      }
    }
    if (maxLowRedundancyBlocks >= 0
        && sampleAdjustedLowRedundancyBlocks() > maxLowRedundancyBlocks) {
      return true;
    }
    return false;
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
   * One integral-control step. Package-visible and pure so the ramp/deadband
   * behavior can be unit-tested without running the lock-holding {@link #run()}.
   *
   * @param current the limit chosen last tick
   * @param smoothedSignal the smoothed avg RPC queue time (ms)
   * @param forceMin whether a hard override demands the floor this tick
   * @return the new limit, clamped to {@code [min, max]}
   */
  @VisibleForTesting
  int nextControllerLimit(int current, double smoothedSignal, boolean forceMin) {
    if (forceMin) {
      return minPendingLimit;
    }
    if (smoothedSignal <= healthyRpcQueueTimeMs) {
      return Math.min(maxPendingLimit, current + rampUpStep);
    }
    if (smoothedSignal >= busyRpcQueueTimeMs) {
      return Math.max(minPendingLimit, current - rampDownStep);
    }
    return current; // within the deadband: hold
  }

  /**
   * Fold a new sample into the EWMA. Package-visible and pure for unit testing.
   *
   * @param prevEma the previous EWMA value, or {@code < 0} if unseeded
   * @param sample the new signal sample
   * @return {@code sample} on the first call (or when smoothing is disabled,
   *         since then alpha == 1.0), otherwise the updated EWMA
   */
  @VisibleForTesting
  double smoothSignal(double prevEma, long sample) {
    if (prevEma < 0) {
      return sample;
    }
    return emaAlpha * sample + (1.0 - emaAlpha) * prevEma;
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
  public long getHealthyRpcQueueTimeMs() {
    return healthyRpcQueueTimeMs;
  }

  public void setHealthyRpcQueueTimeMs(long healthyRpcQueueTimeMs) {
    this.healthyRpcQueueTimeMs = healthyRpcQueueTimeMs;
  }

  @VisibleForTesting
  public long getBusyRpcQueueTimeMs() {
    return busyRpcQueueTimeMs;
  }

  public void setBusyRpcQueueTimeMs(long busyRpcQueueTimeMs) {
    this.busyRpcQueueTimeMs = busyRpcQueueTimeMs;
  }

  @VisibleForTesting
  public int getRampUpStep() {
    return rampUpStep;
  }

  public void setRampUpStep(int rampUpStep) {
    this.rampUpStep = rampUpStep;
  }

  @VisibleForTesting
  public int getRampDownStep() {
    return rampDownStep;
  }

  public void setRampDownStep(int rampDownStep) {
    this.rampDownStep = rampDownStep;
  }

  @VisibleForTesting
  public long getSignalEmaWindowMs() {
    return signalEmaWindowMs;
  }

  public void setSignalEmaWindowMs(long signalEmaWindowMs) {
    this.signalEmaWindowMs = signalEmaWindowMs;
    recomputeEmaAlpha();
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
