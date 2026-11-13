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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.client.HdfsClientConfigKeys;
import org.apache.hadoop.hdfs.protocol.DatanodeInfo;
import org.apache.hadoop.hdfs.protocol.ErasureCodingPolicy;
import org.apache.hadoop.hdfs.protocol.ExtendedBlock;
import org.apache.hadoop.hdfs.protocol.HdfsConstants;
import org.apache.hadoop.hdfs.protocol.LocatedBlocks;
import org.apache.hadoop.hdfs.protocol.LocatedStripedBlock;
import org.apache.hadoop.hdfs.server.datanode.DataNode;
import org.apache.hadoop.hdfs.util.StripedBlockUtil;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.test.GenericTestUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

/**
 * Reads from an open striped stream after the balancer has moved some of its
 * internal blocks. Each read that hits a moved block marks its old node dead
 * for the stream, as replicated reads do. The first failed cell re-fetches a
 * prefetch window of block groups, so each window marks at most one node
 * dead, and the test sets the prefetch size below one group so each window is
 * one group. After reading through more moved groups than parity covers, a
 * group with a cell on every old node has too many dead cells to read. Like a
 * replicated read with every replica dead, the stream must then clear its
 * dead nodes, re-fetch locations and retry. See HubSpotStripedRefetch.
 */
public class TestDFSStripedInputStreamWithMovedBlocks {
  private final ErasureCodingPolicy ecPolicy =
      StripedFileTestUtil.getDefaultECPolicy();
  private final short dataBlocks = (short) ecPolicy.getNumDataUnits();
  private final short parityBlocks = (short) ecPolicy.getNumParityUnits();
  private final int groupSize = dataBlocks + parityBlocks;
  private final int cellSize = ecPolicy.getCellSize();
  private final int blockSize = 2 * cellSize;
  private final int blockGroupSize = blockSize * dataBlocks;
  // More moved groups (one dead node each) than parity can cover.
  private final int numMoved = parityBlocks + 1;
  private final int numGroups = numMoved + 1;
  private final int fileLength = numGroups * blockGroupSize;
  private final Path path = new Path("/ec/moved");

  private MiniDFSCluster cluster;
  private DistributedFileSystem fs;
  private DFSClient client;
  private byte[] expected;

  @Rule
  public Timeout globalTimeout = new Timeout(300000);

  @Before
  public void setup() throws Exception {
    Configuration conf = new HdfsConfiguration();
    conf.setLong(DFSConfigKeys.DFS_BLOCK_SIZE_KEY, blockSize);
    // Each re-fetch after a failed cell then covers only that block group.
    conf.setLong(HdfsClientConfigKeys.Read.PREFETCH_SIZE_KEY, cellSize);
    // Keep the wait before each re-fetch short.
    conf.setInt(HdfsClientConfigKeys.Retry.WINDOW_BASE_KEY, 10);
    conf.setLong(DFSConfigKeys.DFS_HEARTBEAT_INTERVAL_KEY, 1);
    conf.setInt(
        DFSConfigKeys.DFS_NAMENODE_REDUNDANCY_INTERVAL_SECONDS_KEY, 1);
    // Exactly groupSize nodes so every block group lands on the same nodes.
    cluster = new MiniDFSCluster.Builder(conf).numDataNodes(groupSize).build();
    cluster.waitActive();
    fs = cluster.getFileSystem();
    fs.enableErasureCodingPolicy(ecPolicy.getName());
    fs.mkdirs(path.getParent());
    fs.setErasureCodingPolicy(path.getParent(), ecPolicy.getName());
    client = fs.getClient();

    expected = StripedFileTestUtil.generateBytes(fileLength);
    DFSTestUtil.writeFile(fs, path, expected);
    StripedFileTestUtil.waitBlockGroupsReported(fs, path.toUri().getPath());

    // Spare nodes to move internal blocks onto.
    cluster.startDataNodes(conf, numMoved, true, null, null);
    cluster.waitActive();
  }

  @After
  public void tearDown() {
    if (cluster != null) {
      cluster.shutdown();
      cluster = null;
    }
  }

  @Test
  public void testPreadRetriesAfterMovesMarkTooManyNodesDead()
      throws Exception {
    try (DFSStripedInputStream in =
        (DFSStripedInputStream) client.open(path.toString())) {
      // Cache the locations of every block group.
      assertReads(in, 0, fileLength);

      List<DatanodeInfo> sources = moveOneCellPerGroup(numMoved);

      // Each moved group has one stale cell; reading it marks the old node
      // dead and decodes the cell from parity.
      for (int g = 0; g < numMoved; g++) {
        assertReads(in, g * blockGroupSize, blockGroupSize);
      }
      assertAllDead(in, sources);

      // The last group still has a cell on every source node. Without the
      // retry this read fails with "4 missing blocks".
      assertReads(in, numMoved * blockGroupSize, blockGroupSize);
      assertNoneDead(in, sources);
    }
  }

  @Test
  public void testStatefulReadRetriesAfterMovesMarkTooManyNodesDead()
      throws Exception {
    try (DFSStripedInputStream in =
        (DFSStripedInputStream) client.open(path.toString())) {
      assertReads(in, 0, fileLength);

      List<DatanodeInfo> sources = moveOneCellPerGroup(numMoved);

      in.seek(0);
      byte[] moved = new byte[numMoved * blockGroupSize];
      IOUtils.readFully(in, moved, 0, moved.length);
      assertArrayEquals(Arrays.copyOfRange(expected, 0, moved.length), moved);
      assertAllDead(in, sources);

      byte[] last = new byte[blockGroupSize];
      IOUtils.readFully(in, last, 0, last.length);
      assertArrayEquals(
          Arrays.copyOfRange(expected, moved.length, fileLength), last);
      assertNoneDead(in, sources);
    }
  }

  @Test
  public void testUnreachableNodeIsStillMarkedDead() throws Exception {
    try (DFSStripedInputStream in =
        (DFSStripedInputStream) client.open(path.toString())) {
      assertReads(in, 0, fileLength);

      DatanodeInfo source = moveOneCellPerGroup(1).get(0);
      cluster.stopDataNode(source.getXferAddr());

      // One dead cell is within parity, so there is no re-fetch and the
      // stopped node stays excluded.
      assertReads(in, 0, blockGroupSize);
      assertTrue(source + " should be marked dead",
          in.getLocalDeadNodes().containsKey(source));
    }
  }

  @Test
  public void testTooManyUnreachableNodesStillFails() throws Exception {
    try (DFSStripedInputStream in =
        (DFSStripedInputStream) client.open(path.toString())) {
      LocatedStripedBlock first = group(0);
      for (int i = 0; i < parityBlocks + 1; i++) {
        cluster.stopDataNode(first.getLocations()[i].getXferAddr());
      }
      // Re-fetching cannot help, so after the retries the read fails as
      // before.
      try {
        assertReads(in, 0, blockGroupSize);
        fail("read should fail with more nodes down than parity covers");
      } catch (IOException e) {
        assertTrue(e.toString(), e.getMessage().contains("missing blocks"));
      }
    }
  }

  private void assertAllDead(DFSStripedInputStream in,
      List<DatanodeInfo> nodes) {
    for (DatanodeInfo node : nodes) {
      assertTrue(node + " should be marked dead",
          in.getLocalDeadNodes().containsKey(node));
    }
  }

  private void assertNoneDead(DFSStripedInputStream in,
      List<DatanodeInfo> nodes) {
    for (DatanodeInfo node : nodes) {
      assertTrue(node + " should no longer be marked dead",
          !in.getLocalDeadNodes().containsKey(node));
    }
  }

  private void assertReads(DFSStripedInputStream in, int offset, int length)
      throws IOException {
    byte[] buf = new byte[length];
    in.readFully(offset, buf, 0, length);
    assertArrayEquals("bytes at offset " + offset,
        Arrays.copyOfRange(expected, offset, offset + length), buf);
  }

  private LocatedStripedBlock group(int g) throws IOException {
    return (LocatedStripedBlock) client
        .getLocatedBlocks(path.toString(), 0, fileLength).get(g);
  }

  /**
   * For each of the first {@code count} block groups, moves a data internal
   * block on a different source node to a spare node the way the balancer
   * does, and waits until the NameNode only lists the new location and the
   * source has deleted its copy.
   *
   * @return the source nodes, one per moved group
   */
  private List<DatanodeInfo> moveOneCellPerGroup(int count) throws Exception {
    LocatedBlocks lbs =
        client.getLocatedBlocks(path.toString(), 0, fileLength);
    assertEquals(numGroups, lbs.getLocatedBlocks().size());
    Set<DatanodeInfo> used = new HashSet<>(
        Arrays.asList(((LocatedStripedBlock) lbs.get(0)).getLocations()));
    for (int g = 1; g < numGroups; g++) {
      assertEquals(used, new HashSet<>(
          Arrays.asList(((LocatedStripedBlock) lbs.get(g)).getLocations())));
    }

    List<DatanodeInfo> spares = new ArrayList<>();
    for (DatanodeInfo dn : client.datanodeReport(
        HdfsConstants.DatanodeReportType.LIVE)) {
      if (!used.contains(dn)) {
        spares.add(dn);
      }
    }
    assertTrue(spares.size() >= count);

    List<DatanodeInfo> sources = new ArrayList<>();
    for (int g = 0; g < count; g++) {
      LocatedStripedBlock group = (LocatedStripedBlock) lbs.get(g);
      // A read only touches parity cells when a data cell fails, so move a
      // data cell, off a node no earlier group used.
      int i = 0;
      while (sources.contains(group.getLocations()[i])
          || group.getBlockIndices()[i] >= dataBlocks) {
        i++;
      }
      DatanodeInfo source = group.getLocations()[i];
      int idx = group.getBlockIndices()[i];
      DatanodeInfo target = spares.get(g);
      ExtendedBlock internal = StripedBlockUtil.constructInternalBlock(
          group.getBlock(), cellSize, dataBlocks, idx);
      assertTrue("replaceBlock " + internal + " " + source + " -> " + target,
          DFSTestUtil.replaceBlock(internal, source, source, target));
      waitForMove(g, internal, idx, source, target);
      sources.add(source);
    }
    return sources;
  }

  private void waitForMove(int g, ExtendedBlock internal, int idx,
      DatanodeInfo source, DatanodeInfo target) throws Exception {
    DataNode sourceDn = cluster.getDataNode(source.getIpcPort());
    GenericTestUtils.waitFor(() -> {
      try {
        cluster.triggerHeartbeats();
        LocatedStripedBlock lsb = group(g);
        List<DatanodeInfo> holders = new ArrayList<>();
        for (int j = 0; j < lsb.getBlockIndices().length; j++) {
          if (lsb.getBlockIndices()[j] == idx) {
            holders.add(lsb.getLocations()[j]);
          }
        }
        return holders.size() == 1 && holders.get(0).equals(target)
            && !sourceDn.getFSDataset().contains(internal);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }, 100, 60000);
  }
}
