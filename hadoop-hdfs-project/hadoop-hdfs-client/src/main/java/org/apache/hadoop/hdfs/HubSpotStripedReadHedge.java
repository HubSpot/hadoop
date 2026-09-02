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
package org.apache.hadoop.hdfs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.util.StripedBlockUtil.AlignedStripe;
import org.apache.hadoop.hdfs.util.StripedBlockUtil.BlockReadStats;
import org.apache.hadoop.hdfs.util.StripedBlockUtil.StripingChunk;
import org.apache.hadoop.util.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HubSpot Edit: this whole class is a HubSpot addition with no upstream
 * equivalent. It lets a striped (erasure coded) read route around a DataNode
 * that is slow but still alive.
 *
 * <h2>Why EC needs this</h2>
 *
 * Every internal block of a striped block group lives on exactly one DataNode.
 * There is no second copy of a given cell, so there is no alternate replica to
 * fail over to and {@code dfs.client.hedged.read.threadpool.size} has no effect
 * on EC data. A DataNode that is slow but still alive -- mid-decommission, or
 * backed by a degraded disk -- therefore stalls every read needing a cell it
 * holds, for as long as that read takes to complete or time out at the socket
 * layer. HDFS never ejects such a node, because it is slow rather than failed.
 *
 * <p>HDFS-17091 sorts decommissioning nodes to the bottom of the location list,
 * but {@code DatanodeManager.sortLocatedStripedBlock} only helps when an
 * internal block is duplicated (already reconstructed elsewhere), because
 * {@code StripedBlockUtil.parseStripedBlockGroup} keeps only the first location
 * per block index. For an internal block held solely by the slow node there is
 * nothing to reorder toward.
 *
 * <h2>Additive, never subtractive</h2>
 *
 * The important property of this class is that it <em>never</em> reduces the
 * stripe's ability to reconstruct. It works in two distinct phases:
 *
 * <ol>
 *   <li><b>On timeout, read more.</b> Issue the remaining data cells plus one
 *       parity unit. These land in the pooled decode buffers, never in the
 *       caller's memory, and the straggler's read stays in flight and still
 *       counts. Nothing is given up, so if these reads also fail we are no
 *       worse off than before.</li>
 *   <li><b>Only once a complete reconstruction set is in hand</b> -- that is,
 *       {@code fetchedChunksNum == dataBlkNum} without counting the straggler
 *       -- do we stop waiting on it. Abandoning a chunk can therefore never
 *       cost us the ability to reconstruct, because by then the replacement
 *       bytes are already in memory.</li>
 * </ol>
 *
 * <p>The ordering matters. Cancelling on timeout alone, trusting parity to
 * cover the gap, can surrender more chunks than {@code parityBlkNum} and turn a
 * slow read into a failed one ("N missing blocks, the stripe is: ..."). Waiting
 * for the replacement bytes first makes that state unreachable rather than
 * merely unlikely.
 *
 * <h2>One writer to the caller's buffer</h2>
 *
 * For a positional read the straggler's {@code StripingChunk} wraps a slice of
 * the <em>caller's</em> buffer (see
 * {@code StripedBlockUtil.divideByteRangeIntoStripes}). Decoding itself is
 * safe -- {@code decodeAndFillBuffer} decodes into the pooled buffers -- but
 * its final step copies the reconstructed cell into that caller slice. So the
 * straggler must be known to have stopped writing before that copy happens.
 *
 * <p>{@code Future.cancel(true)} is not sufficient evidence: {@code FutureTask}
 * moves to CANCELLED synchronously, so {@code get()} returns immediately while
 * the worker thread may still be unwinding inside the block reader. This class
 * therefore tracks a completion latch per chunk, signalled in a {@code finally}
 * block by the read task itself, and only reports a straggler retired once that
 * latch has fired. If it does not fire in time we leave the chunk alone and let
 * its own read finish normally -- losing the speedup on that read, never
 * risking a torn buffer.
 *
 * <p>Cancellation does interrupt a DataNode read: client sockets are NIO
 * sockets ({@code StandardSocketFactory.createSocket} opens a
 * {@code SocketChannel}), so reads go through {@code NioInetPeer} and
 * {@code SocketIOWithTimeout}, which raises {@code InterruptedIOException} when
 * the blocked thread is interrupted.
 *
 * <h2>Observability</h2>
 *
 * This reuses the existing {@link DFSHedgedReadMetrics} counters rather than
 * adding new ones. HBase surfaces those three by explicit getter
 * ({@code MetricsRegionServerWrapperImpl.getHedgedReadOps} and friends), so a
 * new field here would not be collected without matching changes in the HBase
 * repo; whereas these already arrive in Grafana as
 * {@code collectd_hbase_hedgedreads_count} and
 * {@code collectd_hbase_hedgedreads_wins_total}.
 *
 * <p>Reusing them is unambiguous on an erasure coded cluster, because
 * replica-based hedged reads cannot fire there at all: every internal block has
 * a single location, and in practice
 * {@code dfs.client.hedged.read.threadpool.size} is 0, so the counters are
 * otherwise permanently zero. On a cluster where replica hedged reads ARE
 * enabled the two flavours would be mixed together -- worth remembering before
 * reading these numbers on a replicated cluster.
 *
 * <p>The mapping is:
 * <ul>
 *   <li>{@code hedgedReadOps} -- phase 1 fired; we started reading around a
 *       straggler</li>
 *   <li>{@code hedgedReadOpsWin} -- phase 2 succeeded; the straggler was
 *       retired and the reconstruction supplied the data</li>
 * </ul>
 *
 * <p>The difference between them is the number of times a straggler would not
 * unwind in time and we fell back to waiting for it, which is the signal that
 * the quiesce timeout is set too tight.
 *
 * <h2>Sizing the threshold</h2>
 *
 * Reconstructing one cell requires reading a full stripe, so the threshold
 * should sit well above the cluster's normal p99 read latency in order to fire
 * only on genuine outliers.
 */
class HubSpotStripedReadHedge {
  private static final Logger LOG =
      LoggerFactory.getLogger(HubSpotStripedReadHedge.class);

  static final String THRESHOLD_MILLIS_KEY =
      "dfs.client.hubspot.striped.read.hedge.threshold.millis";

  /**
   * Disabled by default: deploying a build must not, by itself, change how
   * reads behave. Enabling this is a deliberate, per-cluster configuration
   * decision.
   *
   * <p>Pick a threshold comfortably above the cluster's p99 striped read
   * latency, so it fires only on genuine outliers, and above the cost of a
   * reconstruction, so hedging is not slower than waiting. Reconstructing one
   * cell means reading a full stripe, so a threshold below that cost trades a
   * slow read for an equally slow read plus the extra I/O. Faster media wants a
   * lower value.
   */
  static final long THRESHOLD_MILLIS_DEFAULT = 0;

  /**
   * How long to wait for a cancelled read task to actually exit before giving
   * up on retiring it. The read is interrupted, so this normally completes
   * almost immediately; the bound only exists so that a reader which refuses to
   * unwind degrades to ordinary waiting instead of blocking here.
   */
  static final String QUIESCE_TIMEOUT_MILLIS_KEY =
      "dfs.client.hubspot.striped.read.hedge.quiesce.timeout.millis";
  static final long QUIESCE_TIMEOUT_MILLIS_DEFAULT = 250;

  /**
   * How often to re-read the configuration files from the classpath, so that
   * these settings can be changed without restarting the process. 0 disables
   * re-reading, pinning every value to the Configuration this client was built
   * with.
   *
   * <p>Re-reading the files is necessary because the Configuration a DFSClient
   * holds is not the one an embedding service reloads. HBase's update_config
   * calls reloadConfiguration() on the RegionServer's own Configuration, while
   * HFile reads are served by a client obtained from the cache-backed
   * FileSystem.get(), which keeps whatever Configuration existed when it was
   * first built. The two can therefore disagree indefinitely, with the /conf
   * servlet reporting a value the reader never sees.
   */
  static final String RELOAD_INTERVAL_MILLIS_KEY =
      "dfs.client.hubspot.striped.read.hedge.reload.interval.millis";
  static final long RELOAD_INTERVAL_MILLIS_DEFAULT = 30_000;

  /**
   * Resources to consult when re-reading, in addition to the Hadoop defaults
   * (which already include hdfs-site.xml). An entry containing '/' is treated
   * as a file path, anything else as a classpath resource name.
   *
   * <p>Defaults to hbase-site.xml because that is where these properties are
   * configured in practice, and unlike hdfs-site.xml it is not a Hadoop default
   * resource, so a freshly constructed Configuration would not otherwise see
   * it. Naming an HBase file here is a layering compromise, which is why it is
   * a setting rather than a constant.
   */
  static final String RELOAD_RESOURCES_KEY =
      "dfs.client.hubspot.striped.read.hedge.reload.resources";
  static final String RELOAD_RESOURCES_DEFAULT = "hbase-site.xml";

  /**
   * Most recent re-read, keyed by the resource list it was built from. Keyed,
   * because two clients in one JVM may name different resources and must not
   * be served each other's values.
   */
  private static final java.util.concurrent.ConcurrentHashMap<String, Reloaded>
      RELOADED = new java.util.concurrent.ConcurrentHashMap<>();

  private static final class Reloaded {
    private final Configuration conf;
    private final long loadedAt;

    Reloaded(Configuration conf, long loadedAt) {
      this.conf = conf;
      this.loadedAt = loadedAt;
    }
  }

  /**
   * A Configuration re-read from disk, at most once per reload interval, or
   * null when re-reading is disabled or unavailable.
   *
   * <p>Racing callers may each build one; that is harmless duplicate work and
   * cheaper than holding a lock across file parsing.
   */
  private static Configuration reloadedConf(Configuration clientConf) {
    final long interval = clientConf.getLong(
        RELOAD_INTERVAL_MILLIS_KEY, RELOAD_INTERVAL_MILLIS_DEFAULT);
    if (interval <= 0) {
      return null;
    }
    final String[] resources = clientConf.getTrimmedStrings(
        RELOAD_RESOURCES_KEY, RELOAD_RESOURCES_DEFAULT);
    final String cacheKey = String.join(",", resources);
    final long now = Time.monotonicNow();
    final Reloaded current = RELOADED.get(cacheKey);
    if (current != null && now - current.loadedAt < interval) {
      return current.conf;
    }
    try {
      final Configuration fresh = new HdfsConfiguration();
      for (String resource : resources) {
        if (resource.isEmpty()) {
          continue;
        }
        if (resource.indexOf('/') >= 0) {
          fresh.addResource(new org.apache.hadoop.fs.Path(resource));
        } else {
          fresh.addResource(resource);
        }
      }
      // Parse now, so no striped read pays for it later.
      fresh.size();
      RELOADED.put(cacheKey, new Reloaded(fresh, now));
      return fresh;
    } catch (Exception e) {
      // Catch broadly and on purpose. This runs on the read path, and a
      // configuration file is something an operator can make unparseable at
      // any moment; a bad file must degrade to the values this client already
      // has, never fail a read. Errors are deliberately not caught -- those
      // signal a JVM-level problem where continuing is not obviously right.
      LOG.warn("Striped read hedge: could not re-read configuration from {}; "
          + "keeping the values this client was built with", cacheKey, e);
      // Back off for a full interval rather than retrying on every read.
      RELOADED.put(cacheKey, new Reloaded(null, now));
      return null;
    }
  }

  /**
   * The live value of {@code key}: whatever the re-read configuration files say
   * if they define it, else whatever this client was built with, else
   * {@code dflt}. Shared with HubSpotStripedReadHedgeThrottler.
   */
  static long liveLong(Configuration clientConf, String key, long dflt) {
    return resolveLong(reloadedConf(clientConf), clientConf, key, dflt);
  }

  /**
   * Resolves against an already-obtained re-read Configuration. Callers that
   * hold one must use this rather than {@link #liveLong}: re-entering
   * reloadedConf() from inside its own bookkeeping can see the entry it just
   * stored as already stale and rebuild, recursing without bound.
   */
  private static long resolveLong(Configuration reloaded,
      Configuration clientConf, String key, long dflt) {
    final String live = rawFrom(reloaded, key);
    if (live != null) {
      try {
        return Long.parseLong(live);
      } catch (NumberFormatException e) {
        LOG.warn("Striped read hedge: ignoring unparseable {}={}", key, live);
      }
    }
    return clientConf.getLong(key, dflt);
  }

  static int liveInt(Configuration clientConf, String key, int dflt) {
    return resolveInt(reloadedConf(clientConf), clientConf, key, dflt);
  }

  private static int resolveInt(Configuration reloaded,
      Configuration clientConf, String key, int dflt) {
    final String live = rawFrom(reloaded, key);
    if (live != null) {
      try {
        return Integer.parseInt(live);
      } catch (NumberFormatException e) {
        LOG.warn("Striped read hedge: ignoring unparseable {}={}", key, live);
      }
    }
    return clientConf.getInt(key, dflt);
  }

  private static String rawFrom(Configuration reloaded, String key) {
    if (reloaded == null) {
      return null;
    }
    final String value = reloaded.getTrimmed(key);
    return value == null || value.isEmpty() ? null : value;
  }

  /** Test hook: forget the last re-read so the next call goes back to disk. */
  static void resetReloadCacheForTests() {
    RELOADED.clear();
    LAST_LOGGED_CONFIG.set(null);
    CONFIG_LOG_COUNT.set(0);
  }

  private final long thresholdMillis;
  private final long quiesceTimeoutMillis;
  private final DFSHedgedReadMetrics metrics;
  private final HubSpotStripedReadHedgeThrottler throttler;

  /**
   * When each chunk's read was submitted. A read still queued in the striped
   * read pool has not started, so its slowness says nothing about any DataNode
   * -- hedging it would mistake pool saturation for node impairment and queue
   * yet more work into the pool that is already full.
   */
  /** When each chunk's read task was submitted. */
  private final Map<Integer, Long> submittedAt = new HashMap<>();

  /** Chunk indices for which the extra reads have already been issued. */
  private final Set<Integer> hedged = new HashSet<>();

  /** Signals, per chunk index, that the read task has finished running. */
  private final Map<Integer, CountDownLatch> completion = new HashMap<>();

  /**
   * Chunk indices already retired in phase 2. Their futures are deliberately
   * left in the caller's {@code futures} map -- see
   * {@link #retireHedgedStragglers} -- so the caller needs to recognise and
   * discard their late results.
   */
  private final Set<Integer> retiredIndices = new HashSet<>();

  /**
   * Logs the effective configuration at INFO on first use and again whenever
   * it changes, so a reload announces itself without DEBUG being enabled.
   *
   * <p>Deliberately reports what <em>this</em> Configuration yields, not what
   * hbase-site.xml on disk says or what the RegionServer's /conf servlet
   * reports. Those are different objects: a DFSClient obtained from the
   * FileSystem cache keeps whatever Configuration existed when it was first
   * built, so the file, the servlet and the reader can all disagree. Without
   * this line, "the hedge is not firing" is indistinguishable from "the hedge
   * never saw the config".
   */
  private static final java.util.concurrent.atomic.AtomicReference<long[]>
      LAST_LOGGED_CONFIG = new java.util.concurrent.atomic.AtomicReference<>();

  /** Incremented each time the effective config is logged. For tests. */
  private static final java.util.concurrent.atomic.AtomicInteger
      CONFIG_LOG_COUNT = new java.util.concurrent.atomic.AtomicInteger();

  private static void logEffectiveConfigIfChanged(Configuration reloaded,
      Configuration conf) {
    final long[] now = {
        resolveLong(reloaded, conf, THRESHOLD_MILLIS_KEY,
            THRESHOLD_MILLIS_DEFAULT),
        resolveLong(reloaded, conf, QUIESCE_TIMEOUT_MILLIS_KEY,
            QUIESCE_TIMEOUT_MILLIS_DEFAULT),
        resolveInt(reloaded, conf,
            HubSpotStripedReadHedgeThrottler.MAX_IMPAIRED_NODES_KEY,
            HubSpotStripedReadHedgeThrottler.MAX_IMPAIRED_NODES_DEFAULT),
        resolveLong(reloaded, conf,
            HubSpotStripedReadHedgeThrottler.IMPAIRED_WINDOW_MILLIS_KEY,
            HubSpotStripedReadHedgeThrottler.IMPAIRED_WINDOW_MILLIS_DEFAULT),
        resolveInt(reloaded, conf,
            HubSpotStripedReadHedgeThrottler.MAX_HEDGES_PER_SECOND_KEY,
            HubSpotStripedReadHedgeThrottler.MAX_HEDGES_PER_SECOND_DEFAULT),
        resolveLong(reloaded, conf,
            HubSpotStripedReadHedgeThrottler.COOLDOWN_MILLIS_KEY,
            HubSpotStripedReadHedgeThrottler.COOLDOWN_MILLIS_DEFAULT),
        conf.getLong(RELOAD_INTERVAL_MILLIS_KEY,
            RELOAD_INTERVAL_MILLIS_DEFAULT),
    };
    if (java.util.Arrays.equals(LAST_LOGGED_CONFIG.get(), now)) {
      return;
    }
    LAST_LOGGED_CONFIG.set(now);
    CONFIG_LOG_COUNT.incrementAndGet();
    LOG.info("Striped read hedge effective config: {}={} ({}), {}={}, "
            + "{}={}, {}={}, {}={}, {}={}, {}={} ({})",
        THRESHOLD_MILLIS_KEY, now[0], now[0] > 0 ? "ENABLED" : "DISABLED",
        QUIESCE_TIMEOUT_MILLIS_KEY, now[1],
        HubSpotStripedReadHedgeThrottler.MAX_IMPAIRED_NODES_KEY, now[2],
        HubSpotStripedReadHedgeThrottler.IMPAIRED_WINDOW_MILLIS_KEY, now[3],
        HubSpotStripedReadHedgeThrottler.MAX_HEDGES_PER_SECOND_KEY, now[4],
        HubSpotStripedReadHedgeThrottler.COOLDOWN_MILLIS_KEY, now[5],
        RELOAD_INTERVAL_MILLIS_KEY, now[6],
        now[6] > 0
            ? "values re-read from disk, no restart needed"
            : "pinned at startup, restart required to change");
  }

  /** How many times the effective config has been logged. For tests. */
  static int configLogCountForTests() {
    return CONFIG_LOG_COUNT.get();
  }

  HubSpotStripedReadHedge(Configuration conf, DFSHedgedReadMetrics metrics) {
    this(conf, metrics, HubSpotStripedReadHedgeThrottler.get(conf));
  }

  HubSpotStripedReadHedge(Configuration conf, DFSHedgedReadMetrics metrics,
      HubSpotStripedReadHedgeThrottler throttler) {
    this.metrics = metrics;
    this.throttler = throttler;
    // One re-read for the whole constructor, so the two values below cannot
    // disagree with what was just logged.
    final Configuration reloaded = reloadedConf(conf);
    try {
      logEffectiveConfigIfChanged(reloaded, conf);
    } catch (Exception e) {
      // Logging is a diagnostic; it must not be able to break a read.
      LOG.warn("Striped read hedge: could not log effective config", e);
    }
    final long configured = resolveLong(reloaded, conf, THRESHOLD_MILLIS_KEY,
        THRESHOLD_MILLIS_DEFAULT);
    if (configured < 0) {
      LOG.warn("Ignoring negative {} of {}; disabling striped read hedging",
          THRESHOLD_MILLIS_KEY, configured);
      this.thresholdMillis = 0;
    } else {
      this.thresholdMillis = configured;
    }
    this.quiesceTimeoutMillis = resolveLong(reloaded, conf,
        QUIESCE_TIMEOUT_MILLIS_KEY, QUIESCE_TIMEOUT_MILLIS_DEFAULT);
  }

  boolean isEnabled() {
    return thresholdMillis > 0;
  }

  /**
   * Registers a latch that the read task for {@code chunkIndex} will count down
   * when it stops running. Called by StripeReader as each chunk read is
   * submitted.
   */
  CountDownLatch trackCompletionOf(int chunkIndex) {
    CountDownLatch latch = new CountDownLatch(1);
    completion.put(chunkIndex, latch);
    submittedAt.put(chunkIndex, Time.monotonicNow());
    return latch;
  }

  /**
   * Whether another chunk may be hedged. Each hedged chunk consumes one parity
   * unit when it is eventually reconstructed, so there is no point hedging more
   * than the stripe could reconstruct.
   */
  private boolean canHedgeAnother(AlignedStripe alignedStripe,
      int parityBlkNum) {
    return alignedStripe.missingChunksNum + hedged.size() < parityBlkNum;
  }

  /**
   * The poll timeout to hand to
   * {@code StripedBlockUtil.getNextCompletedStripedRead}.
   *
   * @return the configured threshold, or 0 to block indefinitely as upstream
   *         does. 0 is returned when hedging is disabled, when the stripe has
   *         no reconstruction capacity left to hedge into, and once every
   *         outstanding chunk has already been hedged -- at which point
   *         continuing to poll would only spin.
   */
  long pollTimeoutMillis(Map<Future<BlockReadStats>, Integer> futures,
      AlignedStripe alignedStripe, int parityBlkNum) {
    if (thresholdMillis <= 0 || !canHedgeAnother(alignedStripe, parityBlkNum)) {
      return 0;
    }
    for (Integer index : futures.values()) {
      if (!hedged.contains(index)) {
        return thresholdMillis;
      }
    }
    return 0;
  }

  /**
   * Phase 1. Nothing completed within the threshold, so pick the slowest
   * outstanding chunk and record that we are going to read around it. The
   * caller is expected to issue the additional data and parity reads; this
   * method deliberately does not cancel anything.
   *
   * @return the chunk index now hedged, or -1 if there is nothing to hedge
   */
  int markSlowestPending(Map<Future<BlockReadStats>, Integer> futures,
      AlignedStripe alignedStripe, int parityBlkNum,
      IntFunction<String> datanodeUuidOf) {
    // Disabled means disabled. In practice pollTimeoutMillis returning 0 means
    // the reader blocks and never sees a TIMEOUT, so this is unreachable via
    // readStripe -- but the precondition belongs here too rather than being
    // implied by the caller.
    if (thresholdMillis <= 0
        || !canHedgeAnother(alignedStripe, parityBlkNum)) {
      return -1;
    }
    final long now = Time.monotonicNow();
    for (Integer index : futures.values()) {
      if (hedged.contains(index)) {
        continue;
      }
      final StripingChunk chunk = alignedStripe.chunks[index];
      if (chunk == null || chunk.state != StripingChunk.PENDING) {
        continue;
      }
      // Only hedge a read that has actually been running longer than the
      // threshold. One still queued in the pool is not evidence of a slow
      // DataNode, and treating it as such is what turns pool saturation into
      // a feedback loop.
      final Long submitted = submittedAt.get(index);
      if (submitted == null || now - submitted < thresholdMillis) {
        continue;
      }
      // The safety envelope: single-impaired-node rule, rate cap, circuit
      // breaker. Refusal means we behave exactly as upstream would.
      if (!throttler.mayHedge(datanodeUuidOf.apply(index))) {
        continue;
      }
      hedged.add(index);
      metrics.incHedgedReadOps();
      LOG.debug("Striped read hedge: chunk {} of stripe {} exceeded {}ms; "
          + "reading the rest of the stripe so it can be reconstructed",
          index, alignedStripe, thresholdMillis);
      return index;
    }
    return -1;
  }

  /**
   * Whether this chunk was already retired, meaning it has been accounted as
   * MISSING and any result the completion service later hands back for it must
   * be discarded rather than processed.
   */
  boolean isRetired(int chunkIndex) {
    return retiredIndices.contains(chunkIndex);
  }

  /** Whether any hedged chunk is still outstanding. */
  boolean hasOutstandingHedges(Map<Future<BlockReadStats>, Integer> futures,
      AlignedStripe alignedStripe) {
    for (Integer index : futures.values()) {
      if (!hedged.contains(index)) {
        continue;
      }
      final StripingChunk chunk = alignedStripe.chunks[index];
      if (chunk != null && chunk.state == StripingChunk.PENDING) {
        return true;
      }
    }
    return false;
  }

  /**
   * Phase 2. Called once the stripe holds a complete reconstruction set without
   * the hedged chunks. Cancels each hedged straggler and waits for its read
   * task to actually stop running, so that nothing else can write into the
   * caller's buffer while the decoded cell is copied there.
   *
   * <p><b>The retired future is deliberately left in {@code futures}.</b>
   * Removing it here would desynchronise that map from the
   * {@code ExecutorCompletionService}, which still owns the completed task and
   * will hand it back from a later {@code take()}. At that point
   * {@code StripedBlockUtil.getNextCompletedStripedRead} calls
   * {@code futures.remove(future)}, gets {@code null}, and unboxes it into the
   * {@code int index} parameter of {@code StripingChunkReadResult} -- an NPE
   * thrown straight out of {@code readStripe}, which catches only
   * {@code InterruptedException}. That would turn a slow read into a failed
   * one, the exact outcome this class exists to avoid. It is reachable whenever
   * two stragglers are hedged in one stripe and their quiesce outcomes diverge,
   * because the caller then re-polls instead of breaking.
   *
   * <p>So the entry stays, and the caller must skip results for any index
   * reported by {@link #isRetired}.
   *
   * @return the indices confirmed to have stopped, which the caller may now
   *         mark MISSING and reconstruct. Chunks whose read would not unwind in
   *         time are omitted and left to complete normally.
   */
  List<Integer> retireHedgedStragglers(
      Map<Future<BlockReadStats>, Integer> futures,
      AlignedStripe alignedStripe) {
    final List<Integer> retired = new ArrayList<>();

    for (Map.Entry<Future<BlockReadStats>, Integer> entry
        : futures.entrySet()) {
      final int index = entry.getValue();
      if (!hedged.contains(index)) {
        continue;
      }
      final StripingChunk chunk = alignedStripe.chunks[index];
      if (chunk == null || chunk.state != StripingChunk.PENDING) {
        continue;
      }
      entry.getKey().cancel(true);
      if (awaitCompletionOf(index)) {
        retired.add(index);
        retiredIndices.add(index);
        metrics.incHedgedReadWins();
      } else {
        // Could not confirm the read has stopped touching the caller's buffer,
        // so leave it be: it will finish on its own and fill the chunk itself.
        LOG.warn("Striped read hedge: read for chunk {} did not stop within "
            + "{}ms of cancellation; waiting for it instead of reconstructing",
            index, quiesceTimeoutMillis);
      }
    }
    return retired;
  }

  /**
   * @return true if the read task for this chunk is known to have stopped
   */
  private boolean awaitCompletionOf(int chunkIndex) {
    final CountDownLatch latch = completion.get(chunkIndex);
    if (latch == null) {
      // No latch means we never saw the read submitted, so we cannot make a
      // claim about it. Treat as "still running" and stay on the safe side.
      return false;
    }
    try {
      return latch.await(quiesceTimeoutMillis, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }
}
