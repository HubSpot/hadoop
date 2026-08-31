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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.DFSHedgedReadMetrics;
import org.apache.hadoop.hdfs.util.StripedBlockUtil.AlignedStripe;
import org.apache.hadoop.hdfs.util.StripedBlockUtil.BlockReadStats;
import org.apache.hadoop.hdfs.util.StripedBlockUtil.StripingChunk;
import org.junit.Before;
import org.junit.Test;

/**
 * HubSpot Edit: tests for {@link HubSpotStripedReadHedge}, which has no
 * upstream equivalent.
 */
public class TestHubSpotStripedReadHedge {

  @Before
  public void resetStaticState() {
    HubSpotStripedReadHedge.resetReloadCacheForTests();
    HubSpotStripedReadHedgeThrottler.resetForTests();
  }

  /** RS-6-3: six data units, three parity units. */
  private static final int WIDTH = 9;
  private static final int PARITY = 3;

  /**
   * Deliberately short: the hedge will not act on a read that has not been
   * running longer than the threshold, so the fixture has to wait it out.
   */
  private static final long THRESHOLD = 20;

  /** Records whether it was cancelled, and whether interruption was allowed. */
  private static final class RecordingFuture
      implements Future<BlockReadStats> {
    private boolean cancelled;
    private boolean interruptRequested;

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
      this.cancelled = true;
      this.interruptRequested = mayInterruptIfRunning;
      return true;
    }

    @Override
    public boolean isCancelled() {
      return cancelled;
    }

    @Override
    public boolean isDone() {
      return cancelled;
    }

    @Override
    public BlockReadStats get() {
      throw new UnsupportedOperationException();
    }

    @Override
    public BlockReadStats get(long timeout, TimeUnit unit) {
      throw new UnsupportedOperationException();
    }
  }

  /** A stripe plus its in-flight reads, mirroring StripeReader's state. */
  private static final class Fixture {
    private final HubSpotStripedReadHedge hedge;
    private final AlignedStripe stripe;
    private final Map<Future<BlockReadStats>, Integer> futures =
        new HashMap<>();
    private final Map<Integer, RecordingFuture> byIndex = new HashMap<>();
    private final Map<Integer, CountDownLatch> latches = new HashMap<>();

    Fixture(HubSpotStripedReadHedge hedge, int... pendingIndices) {
      this(hedge, THRESHOLD + 10, pendingIndices);
    }

    Fixture(HubSpotStripedReadHedge hedge, long settleMillis,
        int... pendingIndices) {
      this.hedge = hedge;
      this.stripe = new AlignedStripe(0, 1024, WIDTH);
      for (int i : pendingIndices) {
        stripe.chunks[i] = new StripingChunk(StripingChunk.PENDING);
        RecordingFuture f = new RecordingFuture();
        futures.put(f, i);
        byIndex.put(i, f);
        latches.put(i, hedge.trackCompletionOf(i));
      }
      // The submit-time gate only lets us hedge a read that has genuinely been
      // running longer than the threshold, so let that elapse.
      try {
        Thread.sleep(settleMillis);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    /** Simulate the read task for this chunk finishing its finally block. */
    void readTaskExits(int index) {
      latches.get(index).countDown();
    }

    long poll() {
      return hedge.pollTimeoutMillis(futures, stripe, PARITY);
    }

    int mark() {
      return hedge.markSlowestPending(futures, stripe, PARITY,
          i -> "dn-uuid-" + i);
    }

    List<Integer> retire() {
      return hedge.retireHedgedStragglers(futures, stripe);
    }
  }

  private static Configuration conf(long thresholdMillis) {
    Configuration c = new Configuration(false);
    c.setLong(HubSpotStripedReadHedge.THRESHOLD_MILLIS_KEY, thresholdMillis);
    // Keep the "would not unwind" path fast in tests.
    c.setLong(HubSpotStripedReadHedge.QUIESCE_TIMEOUT_MILLIS_KEY, 50);
    return c;
  }

  /** Metrics of the most recently constructed hedge, for assertions. */
  private static DFSHedgedReadMetrics lastMetrics;

  private static HubSpotStripedReadHedge hedge(long thresholdMillis) {
    return hedge(thresholdMillis, permissiveThrottler());
  }

  private static HubSpotStripedReadHedge hedge(long thresholdMillis,
      HubSpotStripedReadHedgeThrottler throttler) {
    lastMetrics = new DFSHedgedReadMetrics();
    return new HubSpotStripedReadHedge(
        conf(thresholdMillis), lastMetrics, throttler);
  }

  /**
   * A throttler that gates nothing, so the tests above measure the per-stripe
   * parity bound in isolation. The throttler's own gates are tested separately.
   */
  private static HubSpotStripedReadHedgeThrottler permissiveThrottler() {
    Configuration c = new Configuration(false);
    c.setInt(HubSpotStripedReadHedgeThrottler.MAX_IMPAIRED_NODES_KEY, 99);
    c.setInt(HubSpotStripedReadHedgeThrottler.MAX_HEDGES_PER_SECOND_KEY,
        10_000);
    return new HubSpotStripedReadHedgeThrottler(c);
  }

  @Test
  public void testDisabledByExplicitZero() {
    // 0 means "block indefinitely", i.e. exactly upstream behaviour.
    assertEquals(0, new Fixture(hedge(0), 2).poll());
  }

  @Test
  public void testNegativeThresholdIsTreatedAsDisabled() {
    assertEquals(0, new Fixture(hedge(-1), 2).poll());
  }

  /**
   * Hedging is off unless a cluster opts in. Deploying a build must not, by
   * itself, change how reads behave.
   */
  @Test
  public void testDisabledUnlessExplicitlyConfigured() {
    assertEquals("the default must be disabled",
        0, HubSpotStripedReadHedge.THRESHOLD_MILLIS_DEFAULT);

    HubSpotStripedReadHedge h = new HubSpotStripedReadHedge(
        new Configuration(false), new DFSHedgedReadMetrics(),
        permissiveThrottler());
    Fixture f = new Fixture(h, 2);
    assertEquals("so an unconfigured client blocks like upstream",
        0, f.poll());
    assertEquals("and never hedges", -1, f.mark());
  }

  /**
   * The core invariant of phase 1: noticing a straggler must not cancel it or
   * touch its state. Nothing may be given up before the replacement data is in
   * hand.
   */
  @Test
  public void testPhaseOneGivesNothingUp() {
    Fixture f = new Fixture(hedge(THRESHOLD), 2);

    assertEquals(THRESHOLD, f.poll());
    assertEquals(2, f.mark());

    assertFalse("phase 1 must not cancel the straggler",
        f.byIndex.get(2).cancelled);
    assertEquals("phase 1 must not change chunk state",
        StripingChunk.PENDING, f.stripe.chunks[2].state);
    assertEquals("phase 1 must not account for a missing chunk",
        0, f.stripe.missingChunksNum);
    assertTrue("the straggler is still outstanding",
        f.hedge.hasOutstandingHedges(f.futures, f.stripe));
    assertEquals("phase 1 counts as a hedged read op",
        1, lastMetrics.getHedgedReadOps());
    assertEquals("but not yet as a win", 0, lastMetrics.getHedgedReadWins());
  }

  /** Each chunk is hedged once; polling stops rather than spinning. */
  @Test
  public void testStopsPollingOnceEverythingIsHedged() {
    Fixture f = new Fixture(hedge(THRESHOLD), 2);
    assertEquals(2, f.mark());
    assertEquals(-1, f.mark());
    assertEquals("nothing left to hedge, so block instead of spinning",
        0, f.poll());
  }

  /**
   * Each hedged chunk consumes a parity unit when reconstructed, so hedging
   * must stop at the stripe's reconstruction capacity.
   */
  @Test
  public void testWillNotHedgeBeyondParityCapacity() {
    Fixture f = new Fixture(hedge(THRESHOLD), 0, 1, 2, 3, 4, 5);
    int hedgedCount = 0;
    while (f.mark() >= 0) {
      hedgedCount++;
      if (hedgedCount > PARITY + 2) {
        break;
      }
    }
    assertEquals("must not hedge more chunks than parity can reconstruct",
        PARITY, hedgedCount);
    assertEquals(0, f.poll());
  }

  /** Chunks already lost for other reasons eat into that same capacity. */
  @Test
  public void testExistingMissingChunksReduceHedgeCapacity() {
    Fixture f = new Fixture(hedge(THRESHOLD), 0, 1, 2);
    f.stripe.missingChunksNum = PARITY - 1;
    assertTrue("one unit of capacity remains", f.mark() >= 0);
    assertEquals("capacity is now exhausted", -1, f.mark());
  }

  /**
   * Phase 2: once the replacement data is in hand, the straggler is cancelled
   * with interruption and reported only after its read task has actually
   * stopped running.
   */
  @Test
  public void testPhaseTwoRetiresStragglerOnceItHasStopped() {
    Fixture f = new Fixture(hedge(THRESHOLD), 2);
    assertEquals(2, f.mark());

    f.readTaskExits(2);
    List<Integer> retired = f.retire();

    assertEquals(1, retired.size());
    assertEquals(Integer.valueOf(2), retired.get(0));
    assertTrue(f.byIndex.get(2).cancelled);
    assertTrue("cancellation must interrupt the blocked socket read",
        f.byIndex.get(2).interruptRequested);
    assertTrue("the retired future must STAY in the map, or "
        + "getNextCompletedStripedRead unboxes null into an int index",
        f.futures.containsKey(f.byIndex.get(2)));
    assertTrue("and be flagged so its late result is discarded",
        f.hedge.isRetired(2));
    assertEquals("StripeReader, not the hedge, mutates chunk state",
        StripingChunk.PENDING, f.stripe.chunks[2].state);
    assertEquals("retiring a straggler counts as a hedged read win",
        1, lastMetrics.getHedgedReadWins());
  }

  /**
   * The safety case. If a cancelled read will not unwind we cannot prove it has
   * stopped writing into the caller's buffer, so it must not be reported as
   * retired -- better a slow read than a torn one.
   */
  @Test
  public void testStragglerThatWillNotStopIsNotRetired() {
    Fixture f = new Fixture(hedge(THRESHOLD), 2);
    assertEquals(2, f.mark());

    // Deliberately do not call readTaskExits().
    List<Integer> retired = f.retire();

    assertTrue("must not retire a read that may still be writing",
        retired.isEmpty());
    // ops-minus-wins is how we detect a too-tight quiesce timeout.
    assertEquals(1, lastMetrics.getHedgedReadOps());
    assertEquals("a straggler we could not retire is not a win",
        0, lastMetrics.getHedgedReadWins());
    assertFalse("its future must stay so the reader keeps waiting on it",
        f.futures.isEmpty());
    assertTrue(f.hedge.hasOutstandingHedges(f.futures, f.stripe));
  }

  /**
   * A retired chunk's Future must stay in the caller's map when two stragglers
   * are hedged in one stripe and their quiesce outcomes diverge.
   *
   * <p>The ExecutorCompletionService still owns the completed task, and because
   * the other straggler stays PENDING the reader re-polls rather than breaking.
   * The completion service therefore hands the retired future back. If it had
   * been removed, getNextCompletedStripedRead's futures.remove(future) returns
   * null, which unboxes into StripingChunkReadResult's int index parameter and
   * throws an NPE out of readStripe, failing the read.
   */
  @Test
  public void testMixedQuiesceKeepsRetiredFutureInTheMap() {
    Fixture f = new Fixture(hedge(THRESHOLD), 2, 4);

    assertTrue(f.mark() >= 0);
    assertTrue(f.mark() >= 0);

    // Chunk 2's read stops; chunk 4's does not.
    f.readTaskExits(2);
    List<Integer> retired = f.retire();

    assertEquals("only the chunk that stopped is retired",
        1, retired.size());
    assertEquals(Integer.valueOf(2), retired.get(0));

    // The reader will re-poll because chunk 4 is still outstanding, so the
    // completion service can hand back chunk 2's future. It must still be in
    // the map for getNextCompletedStripedRead to resolve an index from.
    assertTrue("retired future must remain in the map",
        f.futures.containsKey(f.byIndex.get(2)));
    assertTrue(f.hedge.isRetired(2));
    assertFalse("the straggler that would not stop is not retired",
        f.hedge.isRetired(4));
    assertTrue("so the reader keeps waiting rather than breaking",
        f.hedge.hasOutstandingHedges(f.futures, f.stripe));
  }

  // ---------------------------------------------------------------------
  // Throttler: the safety envelope. These are the tests that back the claim
  // that a broad slowdown can never be amplified into something worse than
  // upstream behaviour.
  // ---------------------------------------------------------------------

  private static HubSpotStripedReadHedgeThrottler throttler(
      int maxImpaired, int maxPerSecond) {
    Configuration c = new Configuration(false);
    c.setInt(HubSpotStripedReadHedgeThrottler.MAX_IMPAIRED_NODES_KEY,
        maxImpaired);
    c.setInt(HubSpotStripedReadHedgeThrottler.MAX_HEDGES_PER_SECOND_KEY,
        maxPerSecond);
    return new HubSpotStripedReadHedgeThrottler(c);
  }

  /**
   * The core guarantee: once more than the allowed number of distinct
   * DataNodes look impaired, this is a broad slowdown rather than one bad
   * node, so hedging stops entirely rather than piling remedial reads onto an
   * already-struggling cluster.
   */
  @Test
  public void testBroadSlowdownDisablesHedgingEntirely() {
    HubSpotStripedReadHedgeThrottler g = throttler(2, 10_000);

    assertTrue("first impaired node may hedge", g.mayHedge("dn-a"));
    assertTrue("second may still hedge", g.mayHedge("dn-b"));
    assertFalse("a third distinct node means broad slowness -- stop",
        g.mayHedge("dn-c"));
    assertTrue("and the circuit stays open, not re-evaluated per read",
        g.isCircuitOpen());
    assertFalse("even a node we had already accepted is now refused",
        g.mayHedge("dn-a"));
  }

  /** Repeat stragglers on the same node are one impaired node, not many. */
  @Test
  public void testRepeatedStragglersOnOneNodeStayWithinBudget() {
    HubSpotStripedReadHedgeThrottler g = throttler(2, 10_000);
    for (int i = 0; i < 50; i++) {
      assertTrue("same node repeatedly is still a single impaired node",
          g.mayHedge("dn-a"));
    }
    assertFalse(g.isCircuitOpen());
    assertEquals(1, g.getImpairedNodeCount());
  }

  /**
   * The rate cap bounds amplification even for a single impaired node, so a
   * hot enough workload cannot turn one bad node into unbounded extra reads.
   */
  @Test
  public void testRateCapBoundsHedgeVolume() {
    HubSpotStripedReadHedgeThrottler g = throttler(99, 5);
    int allowed = 0;
    for (int i = 0; i < 100; i++) {
      if (g.mayHedge("dn-a")) {
        allowed++;
      }
    }
    assertEquals("hedges per second must be capped", 5, allowed);
    assertTrue("and the refusals are counted", g.getSuppressedCount() > 0);
  }

  /**
   * Limits must not be snapshotted by the throttler: each is resolved on use,
   * so a value that changes underneath it is observed. This is what makes the
   * limits changeable without restarting the process.
   *
   * <p>The test therefore mutates the Configuration and re-checks the
   * <em>same</em> throttler instance. Constructing a second throttler would
   * prove nothing, because a snapshotting implementation would pick the new
   * value up at construction too.
   *
   * <p>Uses the rate cap rather than the impaired-node rule because exceeding
   * the latter opens the circuit, which would mask whether the limit itself
   * was re-read.
   */
  @Test
  public void testLimitsAreReReadNotSnapshotted() {
    Configuration c = new Configuration(false);
    // keep the impaired-node rule out of it
    c.setInt(HubSpotStripedReadHedgeThrottler.MAX_IMPAIRED_NODES_KEY, 99);
    c.setInt(HubSpotStripedReadHedgeThrottler.MAX_HEDGES_PER_SECOND_KEY, 1);
    HubSpotStripedReadHedgeThrottler t =
        new HubSpotStripedReadHedgeThrottler(c);

    assertTrue("first hedge is within a cap of 1", t.mayHedge("dn-a"));
    assertFalse("second exceeds it", t.mayHedge("dn-a"));

    // Raise the cap on the same object, as reloadConfiguration() would.
    c.setInt(HubSpotStripedReadHedgeThrottler.MAX_HEDGES_PER_SECOND_KEY, 100);

    // Several consecutive calls: with the old snapshotting behaviour a cap of
    // 1 would allow at most one per second, so this cannot pass by luck at a
    // second boundary.
    for (int i = 0; i < 5; i++) {
      assertTrue("the same throttler instance must observe the raised cap "
          + "(call " + i + ")", t.mayHedge("dn-a"));
    }
  }

  /** An unattributable straggler cannot be throttled, so it is not hedged. */
  @Test
  public void testUnknownDataNodeIsRefused() {
    HubSpotStripedReadHedgeThrottler g = throttler(2, 10_000);
    assertFalse(g.mayHedge(null));
    assertEquals(0, g.getImpairedNodeCount());
  }

  /**
   * End to end through the hedge: with the throttler tripped, phase 1 declines
   * to act and the straggler is left exactly as upstream would leave it.
   */
  @Test
  public void testHedgeDefersToThrottlerRefusal() {
    HubSpotStripedReadHedgeThrottler g = throttler(0, 10_000);
    Fixture f = new Fixture(hedge(THRESHOLD, g), 2);

    assertEquals("throttler refuses, so nothing is hedged", -1, f.mark());
    assertFalse("and nothing was cancelled", f.byIndex.get(2).cancelled);
    assertEquals("chunk state untouched",
        StripingChunk.PENDING, f.stripe.chunks[2].state);
    assertEquals("no hedge is recorded", 0, lastMetrics.getHedgedReadOps());
  }

  /**
   * A read still sitting in the striped-read pool has not started, so its
   * slowness is not evidence of an impaired DataNode. Hedging it would
   * mistake pool saturation for node impairment and queue more work into the
   * pool that is already full.
   */
  @Test
  public void testWillNotHedgeAReadThatHasNotStarted() {
    // settle for 0ms, so the read has not been running for THRESHOLD yet
    Fixture f = new Fixture(hedge(THRESHOLD), 0L, 2);
    assertEquals("a queued read is not a straggler", -1, f.mark());
    assertEquals(0, lastMetrics.getHedgedReadOps());
  }

  @Test
  public void testChunksThatAreNotPendingAreNotHedged() {
    Fixture f = new Fixture(hedge(THRESHOLD), 2);
    f.stripe.chunks[2].state = StripingChunk.FETCHED;
    assertEquals("only PENDING chunks are candidates", -1, f.mark());
    assertFalse(f.byIndex.get(2).cancelled);
  }

  @Test
  public void testNoOutstandingHedgesWhenNothingHedged() {
    Fixture f = new Fixture(hedge(THRESHOLD), 2);
    assertFalse(f.hedge.hasOutstandingHedges(f.futures, f.stripe));
    assertTrue(f.retire().isEmpty());
  }



  /** Writes a minimal Hadoop config file defining just the threshold. */
  private static File thresholdFile(File f, long millis) throws IOException {
    try (FileWriter w = new FileWriter(f)) {
      w.write("<?xml version=\"1.0\"?>\n<configuration>\n  <property>\n"
          + "    <name>" + HubSpotStripedReadHedge.THRESHOLD_MILLIS_KEY
          + "</name>\n    <value>" + millis + "</value>\n"
          + "  </property>\n</configuration>\n");
    }
    return f;
  }

  private static Configuration reloadingConf(File resource, long intervalMs) {
    Configuration c = new Configuration(false);
    c.setLong(HubSpotStripedReadHedge.RELOAD_INTERVAL_MILLIS_KEY, intervalMs);
    c.set(HubSpotStripedReadHedge.RELOAD_RESOURCES_KEY,
        resource.getAbsolutePath());
    return c;
  }

  private static boolean enabledWith(Configuration c) {
    return new HubSpotStripedReadHedge(c, new DFSHedgedReadMetrics())
        .isEnabled();
  }

  /**
   * The point of the whole mechanism: a threshold changed on disk must take
   * effect without rebuilding the client, because the Configuration a DFSClient
   * holds is not the one an embedding service reloads.
   */
  @Test
  public void testThresholdIsRePickedUpFromDiskWithoutANewClient()
      throws Exception {
    File f = File.createTempFile("hedge-reload", ".xml");
    f.deleteOnExit();
    thresholdFile(f, 0);
    // 1ms interval: every lookup re-reads, so no sleep-tuning is needed beyond
    // letting the interval lapse.
    Configuration c = reloadingConf(f, 1);
    HubSpotStripedReadHedge.resetReloadCacheForTests();

    assertFalse("0 on disk means disabled", enabledWith(c));

    thresholdFile(f, 500);
    Thread.sleep(20);

    assertTrue("a threshold raised on disk must be observed without a new "
        + "client or a restart", enabledWith(c));
  }

  /** The file wins over whatever the client was constructed with. */
  @Test
  public void testDiskValueOverridesTheClientsOwnConfiguration()
      throws Exception {
    File f = File.createTempFile("hedge-reload", ".xml");
    f.deleteOnExit();
    thresholdFile(f, 0);
    Configuration c = reloadingConf(f, 1);
    c.setLong(HubSpotStripedReadHedge.THRESHOLD_MILLIS_KEY, 900);
    HubSpotStripedReadHedge.resetReloadCacheForTests();
    assertFalse("the on-disk 0 must win over the client's 900",
        enabledWith(c));
  }

  /** Reloading off pins values to the client, so disk changes are ignored. */
  @Test
  public void testReloadIntervalZeroPinsToTheClientConfiguration()
      throws Exception {
    File f = File.createTempFile("hedge-reload", ".xml");
    f.deleteOnExit();
    thresholdFile(f, 500);
    Configuration c = reloadingConf(f, 0);
    HubSpotStripedReadHedge.resetReloadCacheForTests();
    assertFalse("with re-reading disabled the file must be ignored, leaving "
        + "the client's absent threshold and therefore off", enabledWith(c));
  }

  /** An unreadable resource must not break reads, just fall back. */
  @Test
  public void testUnreadableResourceFallsBackToTheClientConfiguration()
      throws Exception {
    Configuration c = new Configuration(false);
    c.setLong(HubSpotStripedReadHedge.RELOAD_INTERVAL_MILLIS_KEY, 1);
    c.set(HubSpotStripedReadHedge.RELOAD_RESOURCES_KEY,
        "/nonexistent/dir/definitely-not-here.xml");
    c.setLong(HubSpotStripedReadHedge.THRESHOLD_MILLIS_KEY, 700);
    HubSpotStripedReadHedge.resetReloadCacheForTests();
    assertTrue("a missing resource must fall back to the client's value, "
        + "not disable the feature", enabledWith(c));
  }

  /**
   * The effective config is logged on first use and again when it changes, so
   * that a reload is visible in the log without DEBUG being enabled. Repeated
   * reads at an unchanged config must not log.
   */
  @Test
  public void testEffectiveConfigIsLoggedOnFirstUseAndOnChange()
      throws Exception {
    File f = File.createTempFile("hedge-reload", ".xml");
    f.deleteOnExit();
    thresholdFile(f, 250);
    Configuration c = reloadingConf(f, 1);
    HubSpotStripedReadHedge.resetReloadCacheForTests();

    enabledWith(c);
    assertEquals("first use must log once", 1,
        HubSpotStripedReadHedge.configLogCountForTests());

    // Same values on disk: re-reads happen, but nothing has drifted.
    for (int i = 0; i < 3; i++) {
      Thread.sleep(5);
      enabledWith(c);
    }
    assertEquals("an unchanged config must not log again", 1,
        HubSpotStripedReadHedge.configLogCountForTests());

    thresholdFile(f, 750);
    Thread.sleep(20);
    enabledWith(c);
    assertEquals("a changed threshold must log again", 2,
        HubSpotStripedReadHedge.configLogCountForTests());
  }

  /**
   * An unparseable configuration file must not fail a read. Operators can make
   * one invalid at any moment, and this code runs on the read path.
   */
  @Test
  public void testMalformedConfigFileFallsBackInsteadOfThrowing()
      throws Exception {
    File f = File.createTempFile("hedge-bad", ".xml");
    f.deleteOnExit();
    try (FileWriter w = new FileWriter(f)) {
      w.write("<?xml version=\"1.0\"?>\n<configuration>\n  <property>\n"
          + "    <name>truncated-and-never-closed\n");
    }
    Configuration c = reloadingConf(f, 1);
    c.setLong(HubSpotStripedReadHedge.THRESHOLD_MILLIS_KEY, 400);
    HubSpotStripedReadHedge.resetReloadCacheForTests();

    assertTrue("a broken file must leave the client's own value in force, not "
        + "disable the feature and not throw", enabledWith(c));
  }

  /** A garbage value for a limit must not throw either. */
  @Test
  public void testUnparseableValueFallsBackToTheClientConfiguration()
      throws Exception {
    File f = File.createTempFile("hedge-garbage", ".xml");
    f.deleteOnExit();
    try (FileWriter w = new FileWriter(f)) {
      w.write("<?xml version=\"1.0\"?>\n<configuration>\n  <property>\n"
          + "    <name>" + HubSpotStripedReadHedge.THRESHOLD_MILLIS_KEY
          + "</name>\n    <value>not-a-number</value>\n"
          + "  </property>\n</configuration>\n");
    }
    Configuration c = reloadingConf(f, 1);
    c.setLong(HubSpotStripedReadHedge.THRESHOLD_MILLIS_KEY, 400);
    HubSpotStripedReadHedge.resetReloadCacheForTests();

    assertTrue("an unparseable value must fall back to the client's value",
        enabledWith(c));
  }

  /**
   * A non-positive window or cooldown would silently neuter the circuit
   * breaker, so those are treated as unset and the breaker still trips.
   */
  @Test
  public void testNonPositiveEnvelopeValuesDoNotDisableTheBreaker() {
    Configuration c = new Configuration(false);
    c.setLong(HubSpotStripedReadHedge.RELOAD_INTERVAL_MILLIS_KEY, 0);
    c.setInt(HubSpotStripedReadHedgeThrottler.MAX_IMPAIRED_NODES_KEY, 2);
    c.setLong(HubSpotStripedReadHedgeThrottler.IMPAIRED_WINDOW_MILLIS_KEY, 0);
    c.setLong(HubSpotStripedReadHedgeThrottler.COOLDOWN_MILLIS_KEY, -1);
    HubSpotStripedReadHedgeThrottler.resetForTests();
    HubSpotStripedReadHedgeThrottler t =
        new HubSpotStripedReadHedgeThrottler(c);

    assertTrue(t.mayHedge("dn-a"));
    assertTrue(t.mayHedge("dn-b"));
    assertFalse("a third distinct node must still trip the breaker",
        t.mayHedge("dn-c"));
    assertTrue("and the circuit must stay open", t.isCircuitOpen());
  }
}
