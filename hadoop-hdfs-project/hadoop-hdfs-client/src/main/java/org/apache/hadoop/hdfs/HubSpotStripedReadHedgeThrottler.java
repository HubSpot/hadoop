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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.util.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HubSpot Edit: this whole class is a HubSpot addition with no upstream
 * equivalent. It is the safety envelope around
 * {@link HubSpotStripedReadHedge}.
 *
 * <h2>The property this exists to guarantee</h2>
 *
 * Hedging striped reads is only ever meant to be a best-effort optimisation for
 * the case of a <em>single</em> impaired DataNode. It must never make things
 * worse. That is not automatic, because a hedge is not free:
 *
 * <ul>
 *   <li>{@code StripeReader.readChunk} establishes a block reader
 *       <em>synchronously on the calling thread</em> before submitting anything
 *       to the striped-read pool. If a remedial read lands on a second slow
 *       DataNode, the caller blocks in {@code createBlockReader} for that
 *       node's latency, and neither the cancel nor the quiesce latch can reach
 *       it -- the work is not in the pool.</li>
 *   <li>Remedial reads share the bounded pool with the stuck ones, so under
 *       broad slowness the pool saturates, the completion poll times out
 *       because reads have not <em>started</em> rather than because a node is
 *       slow, and phase 1 reads that as another straggler and queues more work.
 *       That is a positive feedback loop.</li>
 *   <li>The per-stripe parity bound in {@code HubSpotStripedReadHedge} caps
 *       hedges within one stripe, but says nothing about how many stripes hedge
 *       concurrently, so cluster-wide amplification is unbounded.</li>
 * </ul>
 *
 * <p>Left alone, those combine into exactly the wrong behaviour: a legitimate
 * workload spike that slows many DataNodes at once would be amplified into
 * widespread stalls. So every hedge has to pass this gate first, and the worst
 * case when the gates trip is that hedging switches itself off and reads follow
 * the unmodified upstream path. "Never worse" is therefore a property of the
 * structure rather than of tuning.
 *
 * <h2>The gates</h2>
 *
 * <ol>
 *   <li><b>Single-impaired-node rule.</b> Track which DataNodes produced a
 *       straggler recently. Once more than
 *       {@code max.impaired.nodes} distinct nodes are implicated, this is not
 *       one bad node -- it is a broad event -- so stop hedging and open the
 *       circuit. Ages out on its own.</li>
 *   <li><b>Rate cap.</b> Bound hedge starts per second, so amplification is
 *       bounded by construction. A rate cap rather than a concurrency permit
 *       deliberately: there is nothing to release, so nothing can leak and
 *       silently disable hedging forever.</li>
 *   <li><b>Circuit breaker.</b> Once tripped, stay off for a cooldown instead
 *       of re-evaluating on every read, which gives hysteresis and stops
 *       flapping at the boundary.</li>
 * </ol>
 *
 * <p>The fourth guard -- only hedging a read that has actually been running
 * longer than the threshold, rather than one still sitting in the pool queue --
 * lives in {@link HubSpotStripedReadHedge} where the submit times are known.
 *
 * <p>One instance is shared per JVM so the view of cluster health is shared
 * across all readers, in the same spirit as the static
 * {@link DFSHedgedReadMetrics} on {@link DFSClient}.
 */
class HubSpotStripedReadHedgeThrottler {
  private static final Logger LOG =
      LoggerFactory.getLogger(HubSpotStripedReadHedgeThrottler.class);

  static final String MAX_IMPAIRED_NODES_KEY =
      "dfs.client.hubspot.striped.read.hedge.max.impaired.nodes";
  /**
   * Two rather than one: a cluster with one badly impaired node often also has
   * a second mildly degraded one, and a strict single-node rule would then
   * refuse to hedge in exactly the situation this is for. Two still bails out
   * well before a cluster-wide event.
   */
  static final int MAX_IMPAIRED_NODES_DEFAULT = 2;

  static final String IMPAIRED_WINDOW_MILLIS_KEY =
      "dfs.client.hubspot.striped.read.hedge.impaired.window.millis";
  static final long IMPAIRED_WINDOW_MILLIS_DEFAULT = 60_000;

  static final String MAX_HEDGES_PER_SECOND_KEY =
      "dfs.client.hubspot.striped.read.hedge.max.per.second";
  static final int MAX_HEDGES_PER_SECOND_DEFAULT = 200;

  static final String COOLDOWN_MILLIS_KEY =
      "dfs.client.hubspot.striped.read.hedge.cooldown.millis";
  static final long COOLDOWN_MILLIS_DEFAULT = 30_000;

  private static volatile HubSpotStripedReadHedgeThrottler instance;

  /**
   * Held rather than snapshotted, and every limit is resolved through
   * {@link HubSpotStripedReadHedge#liveLong} / {@code liveInt} so that changing
   * one does not require a process restart.
   *
   * <p>Note that {@code update_config} on a RegionServer is <em>not</em>
   * sufficient on its own: it calls {@code conf.reloadConfiguration()} on the
   * RegionServer's own Configuration, but HFile reads are served by a client
   * from the cache-backed {@code FileSystem.get()}, which keeps whatever
   * Configuration existed when it was first built. That is why the live lookup
   * re-reads the configuration files directly rather than trusting this
   * reference.
   *
   * <p>Resolved per call rather than cached here. mayHedge() is only reached
   * when a hedge is actually being considered, which is rare by design, so the
   * lookup cost is irrelevant next to having a kill switch that takes effect
   * without a restart.
   */
  private final Configuration conf;

  /** DataNode UUID -> monotonic millis when it last produced a straggler. */
  private final Map<String, Long> impaired = new ConcurrentHashMap<>();

  private final AtomicLong circuitOpenUntil = new AtomicLong(0);
  private final AtomicLong currentSecond = new AtomicLong(-1);
  private final AtomicInteger hedgesThisSecond = new AtomicInteger();
  private final AtomicLong suppressed = new AtomicLong();

  HubSpotStripedReadHedgeThrottler(Configuration conf) {
    this.conf = conf;
  }

  private int maxImpairedNodes() {
    return HubSpotStripedReadHedge.liveInt(
        conf, MAX_IMPAIRED_NODES_KEY, MAX_IMPAIRED_NODES_DEFAULT);
  }

  /**
   * A non-positive window would prune every entry immediately, so no node could
   * ever accumulate as impaired and the breaker could never trip. That weakens
   * the safety envelope rather than the feature, so treat it as unset.
   */
  private long impairedWindowMillis() {
    final long configured = HubSpotStripedReadHedge.liveLong(
        conf, IMPAIRED_WINDOW_MILLIS_KEY, IMPAIRED_WINDOW_MILLIS_DEFAULT);
    return configured > 0 ? configured : IMPAIRED_WINDOW_MILLIS_DEFAULT;
  }

  private int maxHedgesPerSecond() {
    return HubSpotStripedReadHedge.liveInt(
        conf, MAX_HEDGES_PER_SECOND_KEY, MAX_HEDGES_PER_SECOND_DEFAULT);
  }

  /**
   * A non-positive cooldown would reopen the circuit immediately after it
   * tripped, so likewise treat it as unset rather than as a request to disable
   * hysteresis.
   */
  private long cooldownMillis() {
    final long configured = HubSpotStripedReadHedge.liveLong(
        conf, COOLDOWN_MILLIS_KEY, COOLDOWN_MILLIS_DEFAULT);
    return configured > 0 ? configured : COOLDOWN_MILLIS_DEFAULT;
  }

  static HubSpotStripedReadHedgeThrottler get(Configuration conf) {
    HubSpotStripedReadHedgeThrottler g = instance;
    if (g == null) {
      synchronized (HubSpotStripedReadHedgeThrottler.class) {
        g = instance;
        if (g == null) {
          g = new HubSpotStripedReadHedgeThrottler(conf);
          instance = g;
        }
      }
    }
    return g;
  }

  /**
   * Whether a straggler on this DataNode may be hedged.
   *
   * @param datanodeUuid the node serving the slow chunk, or null if unknown --
   *                     unknown is refused, since a hedge we cannot attribute
   *                     cannot be throttled
   * @return true only if every gate passes; false means fall back to the
   *         unmodified upstream behaviour of waiting for the read
   */
  boolean mayHedge(String datanodeUuid) {
    final long now = Time.monotonicNow();

    if (now < circuitOpenUntil.get()) {
      suppressed.incrementAndGet();
      return false;
    }
    if (datanodeUuid == null) {
      suppressed.incrementAndGet();
      return false;
    }

    pruneImpaired(now);

    // Gate 1: is this a single impaired node, or a broad event?
    if (!impaired.containsKey(datanodeUuid)
        && impaired.size() >= maxImpairedNodes()) {
      openCircuit(now, (impaired.size() + 1) + " distinct DataNodes "
          + "impaired within " + impairedWindowMillis()
          + "ms, which is a broad slowdown rather than one bad node");
      return false;
    }

    // Gate 2: rate cap.
    if (!allowByRate(now)) {
      suppressed.incrementAndGet();
      return false;
    }

    impaired.put(datanodeUuid, now);
    return true;
  }

  private boolean allowByRate(long now) {
    final long second = now / 1000L;
    if (currentSecond.get() != second) {
      // Racy by design: a small overshoot at a second boundary is harmless.
      currentSecond.set(second);
      hedgesThisSecond.set(0);
    }
    return hedgesThisSecond.incrementAndGet() <= maxHedgesPerSecond();
  }

  private void pruneImpaired(long now) {
    final long window = impairedWindowMillis();
    impaired.entrySet()
        .removeIf(e -> now - e.getValue() > window);
  }

  private void openCircuit(long now, String why) {
    final long cooldown = cooldownMillis();
    final long until = now + cooldown;
    if (circuitOpenUntil.getAndSet(until) < now) {
      LOG.warn("Striped read hedging disabled for {}ms: {}. Reads will wait "
          + "for slow DataNodes as they did before hedging existed.",
          cooldown, why);
    }
  }

  /** Test hook: forget all state so cases do not leak into each other. */
  static void resetForTests() {
    instance = null;
  }

  /** @return how many hedges were declined by these gates */
  long getSuppressedCount() {
    return suppressed.get();
  }

  boolean isCircuitOpen() {
    return Time.monotonicNow() < circuitOpenUntil.get();
  }

  int getImpairedNodeCount() {
    pruneImpaired(Time.monotonicNow());
    return impaired.size();
  }
}
