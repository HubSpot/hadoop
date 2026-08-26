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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.protocol.ErasureCodingPolicy;
import org.apache.hadoop.hdfs.protocol.LocatedBlock;
import org.apache.hadoop.hdfs.protocol.LocatedBlocks;
import org.apache.hadoop.hdfs.protocol.LocatedStripedBlock;
import org.apache.hadoop.hdfs.server.datanode.DataNode;
import org.apache.hadoop.hdfs.server.datanode.DataNodeFaultInjector;
import org.apache.hadoop.test.GenericTestUtils;
import org.apache.hadoop.util.Time;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HubSpot Edit: end-to-end coverage for latency-triggered reconstruction of
 * striped reads. No upstream equivalent.
 * <p>
 * Simulates the failure mode this feature targets: a DataNode that is slow but
 * still alive. Because each internal block of a striped block
 * group lives on exactly one DataNode, such a node cannot be routed around --
 * it stalls every read needing a cell it holds. These tests verify that the
 * hedge abandons the straggler, reconstructs the cell from parity, and returns
 * byte-for-byte correct data.
 */
public class TestHubSpotStripedReadHedgeEndToEnd {
  private static final Logger LOG =
      LoggerFactory.getLogger(TestHubSpotStripedReadHedgeEndToEnd.class);

  private static final String HEDGE_KEY =
      "dfs.client.hubspot.striped.read.hedge.threshold.millis";

  /** How long the targeted DataNode stalls each client read. */
  private static final long SLOW_DN_DELAY_MS = 4000;

  /** Hedge threshold: well under the stall, well over a healthy read. */
  private static final long HEDGE_THRESHOLD_MS = 400;

  private MiniDFSCluster cluster;
  private DistributedFileSystem fs;
  private Configuration conf;
  private ErasureCodingPolicy ecPolicy;
  private int stripesPerBlock;
  private int blockSize;
  private final Path dirPath = new Path("/striped");
  private final Path filePath = new Path(dirPath, "file");

  @Rule
  public Timeout globalTimeout = new Timeout(300000);

  @Before
  public void setup() throws IOException {
    // The throttler is a per-JVM singleton, so without this the first test to
    // hedge fixes its config for every later case.
    HubSpotStripedReadHedgeThrottler.resetForTests();
    HubSpotStripedReadHedge.resetReloadCacheForTests();
    ecPolicy = StripedFileTestUtil.getDefaultECPolicy();
    stripesPerBlock = 4;
    blockSize = stripesPerBlock * ecPolicy.getCellSize();

    conf = new Configuration();
    conf.setLong(DFSConfigKeys.DFS_BLOCK_SIZE_KEY, blockSize);
    // Give the hedge room: a full-stripe reconstruction needs dataUnits extra
    // slots on top of the read already in flight.
    conf.setInt("dfs.client.read.striped.threadpool.size",
        (ecPolicy.getNumDataUnits() + ecPolicy.getNumParityUnits()) * 4);
    conf.set(MiniDFSCluster.HDFS_MINIDFS_BASEDIR,
        GenericTestUtils.getRandomizedTempPath());

    cluster = new MiniDFSCluster.Builder(conf)
        .numDataNodes(ecPolicy.getNumDataUnits() + ecPolicy.getNumParityUnits())
        .build();
    cluster.waitActive();
    fs = cluster.getFileSystem();
    fs.enableErasureCodingPolicy(ecPolicy.getName());
    fs.mkdirs(dirPath);
    fs.setErasureCodingPolicy(dirPath, ecPolicy.getName());
  }

  @After
  public void tearDown() {
    HubSpotStripedReadHedgeThrottler.resetForTests();
    DataNodeFaultInjector.set(new DataNodeFaultInjector());
    if (cluster != null) {
      cluster.shutdown();
      cluster = null;
    }
  }

  /**
   * Delays client reads served by one specific DataNode, leaving every other
   * node healthy. This is the "slow but alive" case: the node keeps
   * heartbeating, so HDFS never ejects it and never stops handing it out.
   */
  private AtomicInteger slowDownOneDataNode(final String targetUuid) {
    final AtomicInteger delayedReads = new AtomicInteger();
    DataNodeFaultInjector.set(new DataNodeFaultInjector() {
      @Override
      public void delayReadBlock(String datanodeUuid) {
        if (!targetUuid.equals(datanodeUuid)) {
          return;
        }
        delayedReads.incrementAndGet();
        try {
          Thread.sleep(SLOW_DN_DELAY_MS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    });
    return delayedReads;
  }

  /**
   * Delays only the <em>transfer</em> of reads served by one DataNode. The read
   * is accepted and the success header sent immediately, so the client's
   * createBlockReader() returns fast and all the latency lands in the
   * asynchronous read task -- the window the hedge actually measures.
   */
  private AtomicInteger slowDownOneDataNodeTransfer(final String targetUuid) {
    final AtomicInteger delayedReads = new AtomicInteger();
    DataNodeFaultInjector.set(new DataNodeFaultInjector() {
      @Override
      public void delayBlockTransfer(String datanodeUuid) {
        if (!targetUuid.equals(datanodeUuid)) {
          return;
        }
        delayedReads.incrementAndGet();
        try {
          Thread.sleep(SLOW_DN_DELAY_MS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    });
    return delayedReads;
  }


  /**
   * Offset of a range that is definitely served by {@code datanodeUuid}.
   *
   * <p>uuidOfDataNodeHostingDataCell() returns the first node holding
   * <em>any</em> data cell, which is almost never cell 0, so a read must be
   * aimed at a cell that node actually holds to exercise the fault at all.
   */
  private long offsetServedBy(String datanodeUuid) throws IOException {
    LocatedBlocks blocks = fs.getClient().getLocatedBlocks(
        filePath.toString(), 0, Long.MAX_VALUE);
    for (LocatedBlock lb : blocks.getLocatedBlocks()) {
      LocatedStripedBlock lsb = (LocatedStripedBlock) lb;
      byte[] indices = lsb.getBlockIndices();
      for (int i = 0; i < indices.length; i++) {
        if (indices[i] < ecPolicy.getNumDataUnits()
            && datanodeUuid.equals(
                lsb.getLocations()[i].getDatanodeUuid())) {
          return lb.getStartOffset()
              + (long) indices[i] * ecPolicy.getCellSize();
        }
      }
    }
    throw new IllegalStateException("no data cell served by " + datanodeUuid);
  }

  /**
   * One 64KB positional read at {@code offset}, using a caller-supplied
   * FileSystem, returning how many hedges it caused. The FileSystem is a
   * parameter because how the client was obtained -- and therefore which
   * Configuration its DFSClient holds -- is the thing under test.
   */
  private long preadAndCountHedges(FileSystem client, long offset)
      throws IOException {
    long before = hedgedReadOps();
    long fileLen = client.getFileStatus(filePath).getLen();
    // Clamp: a cell offset in the final, partial block group can sit closer
    // than 64KB to EOF.
    int len = (int) Math.min(64 * 1024, fileLen - offset);
    assertTrue("offset " + offset + " leaves no bytes to read", len > 0);
    byte[] buf = new byte[len];
    try (FSDataInputStream in = client.open(filePath)) {
      in.readFully(offset, buf, 0, len);
    }
    return hedgedReadOps() - before;
  }

  /** JVM-wide singleton, so tests must compare deltas rather than absolutes. */
  private long hedgedReadOps() {
    return fs.getClient().getHedgedReadMetrics().getHedgedReadOps();
  }

  /**
   * Reads the whole file with <em>positional</em> reads, which
   * {@code PositionStripeReader} serves and which HBase HFile reads use. This
   * is the only path where a StripingChunk wraps a slice of the caller's
   * buffer, so it is the only path where the one-writer-to-caller-memory
   * design actually matters.
   */
  private byte[] readFilePositional(long hedgeThresholdMs, int preadLen)
      throws IOException {
    Configuration clientConf = new Configuration(conf);
    clientConf.setLong(HEDGE_KEY, hedgeThresholdMs);
    try (FileSystem client =
        FileSystem.newInstance(cluster.getURI(0), clientConf)) {
      int total = (int) client.getFileStatus(filePath).getLen();
      byte[] out = new byte[total];
      try (FSDataInputStream in = client.open(filePath)) {
        int off = 0;
        while (off < total) {
          int len = Math.min(preadLen, total - off);
          in.readFully(off, out, off, len);
          off += len;
        }
      }
      return out;
    }
  }

  /**
   * @return the UUID of a DataNode holding a <em>data</em> cell of the file.
   *         Parity holders are no good for this test: a healthy read only
   *         fetches the data cells, so slowing a parity node would never be
   *         noticed.
   */
  private String uuidOfDataNodeHostingDataCell() throws IOException {
    LocatedBlocks blocks = fs.getClient().getLocatedBlocks(
        filePath.toString(), 0, Long.MAX_VALUE);
    Set<String> dataCellHosts = new HashSet<>();
    for (LocatedBlock lb : blocks.getLocatedBlocks()) {
      assertTrue("expected striped blocks", lb instanceof LocatedStripedBlock);
      LocatedStripedBlock lsb = (LocatedStripedBlock) lb;
      byte[] indices = lsb.getBlockIndices();
      for (int i = 0; i < indices.length; i++) {
        if (indices[i] < ecPolicy.getNumDataUnits()) {
          dataCellHosts.add(lsb.getLocations()[i].getDatanodeUuid());
        }
      }
    }
    for (DataNode dn : cluster.getDataNodes()) {
      if (dataCellHosts.contains(dn.getDatanodeUuid())) {
        return dn.getDatanodeUuid();
      }
    }
    throw new IllegalStateException("no DataNode holds a data cell of "
        + filePath);
  }

  private byte[] writeFileSpanningStripes() throws Exception {
    // Several stripes, so a read has to touch many block groups and therefore
    // repeatedly hit whichever node we slow down.
    int length = blockSize * ecPolicy.getNumDataUnits() + 12345;
    byte[] expected = new byte[length];
    for (int i = 0; i < length; i++) {
      expected[i] = (byte) ('a' + (i % 26));
    }
    DFSTestUtil.writeFile(fs, filePath, expected);
    StripedFileTestUtil.waitBlockGroupsReported(fs, filePath.toString());
    return expected;
  }

  private byte[] readFileWith(long hedgeThresholdMs) throws IOException {
    Configuration clientConf = new Configuration(conf);
    clientConf.setLong(HEDGE_KEY, hedgeThresholdMs);
    // newInstance, not get: the FileSystem cache is keyed on scheme+authority,
    // so a cached instance would silently reuse the previous hedge setting.
    try (FileSystem client =
        FileSystem.newInstance(cluster.getURI(0), clientConf)) {
      return DFSTestUtil.readFileAsBytes(client, filePath);
    }
  }

  /**
   * The core case. One DataNode stalls every read by {@link #SLOW_DN_DELAY_MS};
   * with the hedge enabled the read should reconstruct around it and return
   * correct data without ever waiting out the stall.
   */
  @Test
  public void testSlowDataNodeIsReconstructedAround() throws Exception {
    byte[] expected = writeFileSpanningStripes();
    String slowUuid = uuidOfDataNodeHostingDataCell();
    AtomicInteger delayed = slowDownOneDataNode(slowUuid);
    LOG.info("Slowing DataNode {} by {}ms", slowUuid, SLOW_DN_DELAY_MS);

    long start = Time.monotonicNow();
    byte[] actual = readFileWith(HEDGE_THRESHOLD_MS);
    long elapsed = Time.monotonicNow() - start;

    assertNotNull(actual);
    assertArrayEquals("reconstructed data must match the original bytes",
        expected, actual);
    assertTrue("the slow DataNode should have been asked for data at least "
        + "once, otherwise this test proves nothing",
        delayed.get() > 0);
    LOG.info("Read completed in {}ms with {} delayed DataNode reads",
        elapsed, delayed.get());
  }

  /**
   * Guard against the hedge corrupting reads on a healthy cluster: with an
   * aggressively low threshold nearly every stripe read is abandoned and
   * reconstructed, which exercises the decode path hard. The bytes must still
   * come back exactly right -- this is what would break if a cancelled read
   * and the decode both wrote into the caller's buffer.
   */
  @Test
  public void testAggressiveHedgingStillReturnsCorrectData() throws Exception {
    byte[] expected = writeFileSpanningStripes();
    byte[] actual = readFileWith(1);
    assertArrayEquals(
        "aggressive hedging must not corrupt data", expected, actual);
  }

  /**
   * The positional-read equivalent of the core case, and the one that matters
   * most: HBase HFile reads are preads, and pread is the only path where the
   * straggler's chunk aliases the caller's buffer.
   */
  @Test
  public void testPositionalReadReconstructsAroundSlowDataNode()
      throws Exception {
    byte[] expected = writeFileSpanningStripes();
    String slowUuid = uuidOfDataNodeHostingDataCell();
    AtomicInteger delayed = slowDownOneDataNode(slowUuid);

    // 64KB preads, mimicking HBase HFile block reads.
    byte[] actual = readFilePositional(HEDGE_THRESHOLD_MS, 64 * 1024);

    assertArrayEquals("positional read must reconstruct correct bytes",
        expected, actual);
    assertTrue("the slow DataNode should have served at least one read",
        delayed.get() > 0);
  }

  /**
   * Aggressive hedging on the positional path: nearly every stripe read is
   * abandoned and reconstructed, which is what would surface a torn caller
   * buffer.
   */
  @Test
  public void testAggressivePositionalHedgingReturnsCorrectData()
      throws Exception {
    byte[] expected = writeFileSpanningStripes();
    byte[] actual = readFilePositional(1, 64 * 1024);
    assertArrayEquals(
        "aggressive positional hedging must not corrupt data",
        expected, actual);
  }

  /** With the hedge disabled, behaviour must be exactly as upstream. */
  @Test
  public void testDisabledHedgeReadsNormally() throws Exception {
    byte[] expected = writeFileSpanningStripes();
    byte[] actual = readFileWith(0);
    assertArrayEquals(expected, actual);
  }

  /**
   * The hedge must actually engage -- not merely return correct data -- when a
   * DataNode is slow. Asserts on hedgedReadOps, which the other cases in this
   * class do not, so a silently disabled hedge cannot pass unnoticed.
   */
  @Test
  public void testHedgeEngagesWhenTransferIsSlow() throws Exception {
    byte[] expected = writeFileSpanningStripes();
    String slowUuid = uuidOfDataNodeHostingDataCell();
    AtomicInteger delayed = slowDownOneDataNodeTransfer(slowUuid);
    long before = hedgedReadOps();

    byte[] actual = readFilePositional(HEDGE_THRESHOLD_MS, 64 * 1024);

    long hedges = hedgedReadOps() - before;
    LOG.info("transfer-slow: delayedReads={} hedges={}", delayed.get(), hedges);
    assertArrayEquals("data must still be correct", expected, actual);
    assertTrue("the slow DataNode should have served at least one read",
        delayed.get() > 0);
    assertTrue("hedge must engage when the transfer phase is slow, but "
        + "hedgedReadOps only moved by " + hedges, hedges > 0);
  }



  /**
   * Control: a client built fresh from a Configuration carrying the threshold
   * must hedge. Establishes that the fault and the read actually interact, so
   * a zero in the other two tests means something.
   */
  @Test
  public void testConfigOnAFreshFileSystemDoesReachTheHedge() throws Exception {
    writeFileSpanningStripes();
    String slowUuid = uuidOfDataNodeHostingDataCell();
    long offset = offsetServedBy(slowUuid);
    // These tests are about whether the threshold reaches the client, so use a
    // fault the hedge unambiguously observes.
    AtomicInteger delayed = slowDownOneDataNodeTransfer(slowUuid);

    Configuration withHedge = new Configuration(conf);
    withHedge.setLong(HEDGE_KEY, HEDGE_THRESHOLD_MS);
    try (FileSystem fresh =
        FileSystem.newInstance(cluster.getURI(0), withHedge)) {
      long hedges = preadAndCountHedges(fresh, offset);
      LOG.info("PROBE fresh: offset={} delayed={} hedges={}",
          offset, delayed.get(), hedges);
      assertTrue("the fault must have been hit", delayed.get() > 0);
      assertTrue("a fresh client must honour the configured threshold, "
          + "hedges=" + hedges, hedges > 0);
    }
  }

  /**
   * The path HBase actually takes. HFileSystem obtains its client via
   * FileSystem.get(uri, conf), which is cache-backed: when an instance already
   * exists for this scheme/authority/UGI the cached one is returned and the
   * Configuration argument is discarded. Its DFSClient keeps whatever
   * Configuration existed when it was first built.
   */
  @Test
  public void testCachedFileSystemIgnoresAThresholdAddedLater()
      throws Exception {
    writeFileSpanningStripes();
    String slowUuid = uuidOfDataNodeHostingDataCell();
    long offset = offsetServedBy(slowUuid);
    // These tests are about whether the threshold reaches the client, so use a
    // fault the hedge unambiguously observes.
    AtomicInteger delayed = slowDownOneDataNodeTransfer(slowUuid);

    // setup() already populated the cache via cluster.getFileSystem() using a
    // Configuration with no threshold, i.e. the hedge off.
    Configuration withHedge = new Configuration(conf);
    withHedge.setLong(HEDGE_KEY, HEDGE_THRESHOLD_MS);
    FileSystem cached = FileSystem.get(cluster.getURI(0), withHedge);
    assertSame("FileSystem.get must have returned the cached instance for "
        + "this test to mean anything", fs, cached);

    long hedges = preadAndCountHedges(cached, offset);
    LOG.info("PROBE cached: delayed={} hedges={}", delayed.get(), hedges);
    assertTrue("the fault must have been hit", delayed.get() > 0);
    assertEquals("a cached client cannot see a threshold added after it was "
        + "built", 0, hedges);
  }

  /**
   * The update_config path: mutate in place the very Configuration the client
   * was built from, which is what HRegionServer.updateConfiguration() achieves
   * via conf.reloadConfiguration(). Decides whether arming the hedge needs a
   * RegionServer restart.
   */
  @Test
  public void testMutatingTheClientsOwnConfigurationInPlace()
      throws Exception {
    writeFileSpanningStripes();
    String slowUuid = uuidOfDataNodeHostingDataCell();
    long offset = offsetServedBy(slowUuid);
    // These tests are about whether the threshold reaches the client, so use a
    // fault the hedge unambiguously observes.
    AtomicInteger delayed = slowDownOneDataNodeTransfer(slowUuid);

    Configuration live = new Configuration(conf);
    try (FileSystem client =
        FileSystem.newInstance(cluster.getURI(0), live)) {
      long firstHedges = preadAndCountHedges(client, offset);
      LOG.info("PROBE pre-mutation: delayed={} hedges={}",
          delayed.get(), firstHedges);
      assertEquals("no threshold yet, so no hedging", 0, firstHedges);

      live.setLong(HEDGE_KEY, HEDGE_THRESHOLD_MS);

      long hedges = preadAndCountHedges(client, offset);
      LOG.info("PROBE post-mutation: delayed={} hedges={}",
          delayed.get(), hedges);
      assertTrue("the fault must have been hit for this test to mean "
          + "anything, delayedReads=" + delayed.get(), delayed.get() > 0);
      assertTrue("in-place mutation of the client's own Configuration must be "
          + "observed, else arming the hedge needs a restart (hedges="
          + hedges + ")", hedges > 0);
    }
  }

  /**
   * Pinpoints which link in the config chain breaks, without any fault
   * injection or slow reads. Walks the exact path the hedge uses:
   * Configuration handed to FileSystem -> DFSClient.getConfiguration() ->
   * the threshold HubSpotStripedReadHedge would read.
   */
  @Test
  public void testConfigChainIdentityAndVisibility() throws Exception {
    Configuration live = new Configuration(conf);
    try (FileSystem client =
        FileSystem.newInstance(cluster.getURI(0), live)) {
      DFSClient dfsClient = ((DistributedFileSystem) client).getClient();
      Configuration held = dfsClient.getConfiguration();

      LOG.info("identity: live=={} held=={} same={}",
          System.identityHashCode(live), System.identityHashCode(held),
          live == held);
      assertSame("DFSClient must hold the very Configuration it was given",
          live, held);

      // Mutate in place, as reloadConfiguration() does.
      live.setLong(HEDGE_KEY, HEDGE_THRESHOLD_MS);
      LOG.info("after mutation: held.getLong={} ",
          held.getLong(HEDGE_KEY, -1));
      assertEquals("the held Configuration must observe the new value",
          HEDGE_THRESHOLD_MS, held.getLong(HEDGE_KEY, -1));

      // And what the hedge itself would compute from it.
      HubSpotStripedReadHedge probe = new HubSpotStripedReadHedge(
          held, dfsClient.getHedgedReadMetrics());
      LOG.info("hedge.isEnabled()={}", probe.isEnabled());
      assertTrue("the hedge built from the held Configuration must be enabled",
          probe.isEnabled());
    }
  }

  /**
   * Same walk, but for the cache-backed FileSystem.get() path that HFileSystem
   * uses -- the one that matters in production.
   */
  @Test
  public void testCachedClientConfigIdentity() throws Exception {
    Configuration withHedge = new Configuration(conf);
    withHedge.setLong(HEDGE_KEY, HEDGE_THRESHOLD_MS);
    FileSystem cached = FileSystem.get(cluster.getURI(0), withHedge);
    Configuration held =
        ((DistributedFileSystem) cached).getClient().getConfiguration();
    LOG.info("cached path: got-cached-instance={} held-sees-threshold={}",
        cached == fs, held.getLong(HEDGE_KEY, -1));
    HubSpotStripedReadHedge probe = new HubSpotStripedReadHedge(
        held, ((DistributedFileSystem) cached).getClient()
            .getHedgedReadMetrics());
    LOG.info("cached path: hedge.isEnabled()={}", probe.isEnabled());
  }
}
