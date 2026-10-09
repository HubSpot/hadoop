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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.DFSTestUtil;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.hdfs.HdfsConfiguration;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.hdfs.StripedFileTestUtil;
import org.apache.hadoop.hdfs.protocol.DatanodeInfo;
import org.apache.hadoop.hdfs.protocol.ErasureCodingPolicy;
import org.apache.hadoop.hdfs.server.datanode.DataNode;
import org.apache.hadoop.hdfs.server.namenode.FSNamesystem;
import org.apache.hadoop.hdfs.server.namenode.INodeFile;
import org.apache.hadoop.test.GenericTestUtils;
import org.apache.hadoop.test.Whitebox;
import org.junit.After;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * End-to-end test of {@link BlockPlacementPolicyErasureCoding} on a real
 * MiniDFSCluster: an erasure-coded file is written onto too few racks so one
 * rack holds more internal blocks than the scheme can lose, then the cluster
 * grows, a node is decommissioned, and the NameNode restarts. Throughout, no
 * internal block may lose its last copy; at each settling point every block
 * group must be safely placed with no reclaimable copy left; and the file must
 * read back intact with any single rack offline.
 */
public class TestErasureCodingPlacementEndToEnd {
  private static final Logger LOG =
      LoggerFactory.getLogger(TestErasureCodingPlacementEndToEnd.class);

  private final ErasureCodingPolicy ecPolicy =
      StripedFileTestUtil.getDefaultECPolicy();
  private final int cellSize = ecPolicy.getCellSize();
  private final int dataBlocks = ecPolicy.getNumDataUnits();

  private static final long STALE_INTERVAL_MS = 3000;

  static {
    GenericTestUtils.setLogLevel(BlockManager.LOG, org.slf4j.event.Level.DEBUG);
    GenericTestUtils.setLogLevel(BlockManager.blockLog,
        org.slf4j.event.Level.DEBUG);
  }

  private MiniDFSCluster cluster;
  private DistributedFileSystem fs;

  @After
  public void tearDown() {
    if (cluster != null) {
      cluster.shutdown();
      cluster = null;
    }
  }

  private HdfsConfiguration newConf() {
    HdfsConfiguration conf = new HdfsConfiguration();
    conf.setInt(DFSConfigKeys.DFS_NAMENODE_REDUNDANCY_INTERVAL_SECONDS_KEY, 1);
    conf.setBoolean(DFSConfigKeys.DFS_NAMENODE_REDUNDANCY_CONSIDERLOAD_KEY,
        false);
    conf.setInt(DFSConfigKeys.DFS_NAMENODE_DECOMMISSION_INTERVAL_KEY, 1);
    conf.setLong(DFSConfigKeys.DFS_HEARTBEAT_INTERVAL_KEY, 1);
    conf.setInt(
        DFSConfigKeys.DFS_NAMENODE_RECONSTRUCTION_PENDING_TIMEOUT_SEC_KEY, 10);
    conf.setInt(DFSConfigKeys.DFS_NAMENODE_REPLICATION_MAX_STREAMS_KEY, 20);
    conf.setInt(DFSConfigKeys.DFS_NAMENODE_REPLICATION_STREAMS_HARD_LIMIT_KEY,
        40);
    conf.setLong(DFSConfigKeys.DFS_BLOCK_SIZE_KEY, 2L * cellSize);
    // The striped read client uses only the first listed copy of each internal
    // block, so a duplicate in a healthy rack serves reads only once the lost
    // rack's nodes are sorted after it, i.e. once they are stale.
    conf.setBoolean(
        DFSConfigKeys.DFS_NAMENODE_AVOID_STALE_DATANODE_FOR_READ_KEY, true);
    conf.setLong(DFSConfigKeys.DFS_NAMENODE_STALE_DATANODE_INTERVAL_KEY,
        STALE_INTERVAL_MS);
    return conf;
  }

  private BlockManager bm() {
    return cluster.getNamesystem().getBlockManager();
  }

  private BlockPlacementPolicyErasureCoding policy() {
    return bm().getStriptedBlockPlacementPolicy();
  }

  private List<BlockInfoStriped> groups(Path file) {
    INodeFile inode;
    try {
      inode = cluster.getNamesystem().getFSDirectory()
          .getINode(file.toString()).asFile();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    List<BlockInfoStriped> groups = new ArrayList<>();
    for (BlockInfo b : inode.getBlocks()) {
      groups.add((BlockInfoStriped) bm().getStoredBlock(b));
    }
    return groups;
  }

  /** Live, in-service, non-excess, non-corrupt copies: rack per index. */
  private Map<Integer, List<String>> liveCopies(BlockInfoStriped g) {
    Map<Integer, List<String>> copies = new HashMap<>();
    for (DatanodeStorageInfo s : bm().blocksMap.getStorages(g)) {
      DatanodeDescriptor dn = s.getDatanodeDescriptor();
      if (dn.isInService() && dn.isAlive() && !bm().isExcess(dn, g)
          && !bm().isReplicaCorrupt(g, dn)) {
        copies.computeIfAbsent((int) g.getStorageBlockIndex(s),
            k -> new ArrayList<>()).add(dn.getNetworkLocation());
      }
    }
    return copies;
  }

  private BlockPlacementStatusErasureCoding status(BlockInfoStriped g,
      Map<Integer, List<String>> copies, int skipIndex, int skipOrdinal) {
    List<DatanodeInfo> locs = new ArrayList<>();
    List<Byte> indices = new ArrayList<>();
    for (Map.Entry<Integer, List<String>> e : copies.entrySet()) {
      for (int i = 0; i < e.getValue().size(); i++) {
        if (e.getKey() == skipIndex && i == skipOrdinal) {
          continue;
        }
        DatanodeDescriptor fake = new DatanodeDescriptor(
            DFSTestUtil.getLocalDatanodeID(), e.getValue().get(i));
        locs.add(fake);
        indices.add((byte) (int) e.getKey());
      }
    }
    byte[] idx = new byte[indices.size()];
    for (int i = 0; i < idx.length; i++) {
      idx[i] = indices.get(i);
    }
    return policy().verifyBlockPlacement(locs.toArray(new DatanodeInfo[0]),
        idx, g.getRealTotalBlockNum(), g.getParityBlockNum());
  }

  private Set<String> racks() {
    Set<String> racks = new HashSet<>();
    for (DataNode dn : cluster.getDataNodes()) {
      DatanodeDescriptor d;
      try {
        d = bm().getDatanodeManager().getDatanode(dn.getDatanodeId());
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      if (d != null && d.isInService()) {
        racks.add(d.getNetworkLocation());
      }
    }
    return racks;
  }

  /**
   * Why {@code g} is not yet settled, or null if it is: every internal block
   * live, placement satisfied, nothing pending or awaiting deletion, every
   * remaining duplicate protective, and every rack's loss survivable.
   */
  private String unsettledReason(BlockInfoStriped g, Set<String> racks) {
    Map<Integer, List<String>> copies = liveCopies(g);
    if (copies.size() != g.getRealTotalBlockNum()) {
      return "missing internal blocks " + copies;
    }
    NumberReplicas num = bm().countNodes(g);
    if (num.excessReplicas() > 0) {
      return "excess replicas awaiting deletion";
    }
    if (bm().pendingReconstruction.getNumReplicas(g) > 0) {
      return "reconstruction pending";
    }
    BlockPlacementStatusErasureCoding st = status(g, copies, -1, -1);
    if (!st.isPlacementPolicySatisfied()) {
      return "unsafe: " + st.getErrorDescription() + " " + copies;
    }
    for (Map.Entry<Integer, List<String>> e : copies.entrySet()) {
      for (int i = 0; e.getValue().size() > 1 && i < e.getValue().size();
           i++) {
        if (!status(g, copies, e.getKey(), i).isLessSafeThan(st)) {
          return "reclaimable copy of " + e.getKey() + " on "
              + e.getValue().get(i) + " in " + copies;
        }
      }
    }
    for (String rack : racks) {
      Set<Integer> survivors = new HashSet<>();
      for (Map.Entry<Integer, List<String>> e : copies.entrySet()) {
        for (String r : e.getValue()) {
          if (!r.equals(rack)) {
            survivors.add(e.getKey());
          }
        }
      }
      if (survivors.size() < g.getRealDataBlockNum()) {
        return "does not survive loss of " + rack + ": " + copies;
      }
    }
    return null;
  }

  private void waitUntilSettled(Path file, String phase) throws Exception {
    final AtomicReference<String> last = new AtomicReference<>();
    try {
      GenericTestUtils.waitFor(() -> {
        FSNamesystem fsn = cluster.getNamesystem();
        fsn.readLock();
        try {
          Set<String> racks = racks();
          for (BlockInfoStriped g : groups(file)) {
            String reason = unsettledReason(g, racks);
            if (reason != null) {
              last.set(g + ": " + reason);
              return false;
            }
          }
          return true;
        } finally {
          fsn.readUnlock();
        }
      }, 500, 180000);
    } catch (Exception e) {
      throw new AssertionError(phase + " did not settle: " + last.get(), e);
    }
    LOG.info("{} settled", phase);
  }

  /**
   * Background check, sampled continuously: no internal block may ever be
   * left without a physical copy on a live node.
   */
  private final class NoLossMonitor extends Thread {
    private final Path file;
    private final AtomicBoolean stop = new AtomicBoolean();
    private final AtomicReference<String> violation = new AtomicReference<>();
    private final Map<Long, Integer> maxCopies = new HashMap<>();
    /** Once set, no group may lose the ability to survive any rack's loss. */
    private final AtomicBoolean armed = new AtomicBoolean();
    private volatile int samples;

    NoLossMonitor(Path file) {
      this.file = file;
      setDaemon(true);
    }

    @Override
    public void run() {
      while (!stop.get()) {
        FSNamesystem fsn = cluster.getNamesystem();
        fsn.readLock();
        try {
          for (BlockInfoStriped g : groups(file)) {
            Set<Integer> present = new HashSet<>();
            maxCopies.merge(g.getBlockId(), bm().blocksMap.numNodes(g),
                Math::max);
            for (DatanodeStorageInfo s : bm().blocksMap.getStorages(g)) {
              if (s.getDatanodeDescriptor().isAlive()
                  && !bm().isReplicaCorrupt(g, s.getDatanodeDescriptor())) {
                present.add((int) g.getStorageBlockIndex(s));
              }
            }
            if (present.size() < g.getRealTotalBlockNum()) {
              violation.compareAndSet(null, g + " has only " + present);
            }
            if (armed.get()) {
              String regression = rackLossRegression(g);
              if (regression != null) {
                violation.compareAndSet(null, regression);
              }
            }
          }
          samples++;
        } finally {
          fsn.readUnlock();
        }
        try {
          Thread.sleep(50);
        } catch (InterruptedException e) {
          return;
        }
      }
    }

    void arm() {
      armed.set(true);
    }

    /** Blocks that at some point had more copies than they do now. */
    int groupsReclaimed() {
      int reclaimed = 0;
      FSNamesystem fsn = cluster.getNamesystem();
      fsn.readLock();
      try {
        for (BlockInfoStriped g : groups(file)) {
          Integer max = maxCopies.get(g.getBlockId());
          if (max != null && max > bm().blocksMap.numNodes(g)) {
            reclaimed++;
          }
        }
      } finally {
        fsn.readUnlock();
      }
      return reclaimed;
    }

    void finish() throws InterruptedException {
      stop.set(true);
      join();
      assertTrue("monitor never sampled", samples > 0);
      assertNull("durability violation", violation.get());
    }
  }

  private void setContentsStale(boolean stale) {
    cluster.getNamesystem().writeLock();
    try {
      for (DatanodeDescriptor dn : bm().getDatanodeManager()
          .getDatanodeListForReport(
              org.apache.hadoop.hdfs.protocol.HdfsConstants.DatanodeReportType
                  .LIVE)) {
        for (DatanodeStorageInfo s : dn.getStorageInfos()) {
          s.setBlockContentsStale(stale);
        }
      }
    } finally {
      cluster.getNamesystem().writeUnlock();
    }
  }

  /**
   * Whether {@code g} would be unrecoverable after losing some rack, counting
   * every non-excess, non-corrupt copy on a live node that is in service or
   * still decommissioning (both remain readable).
   */
  private String rackLossRegression(BlockInfoStriped g) {
    Map<Integer, Set<String>> racksPerIndex = new HashMap<>();
    Set<String> allRacks = new HashSet<>();
    for (DatanodeStorageInfo s : bm().blocksMap.getStorages(g)) {
      DatanodeDescriptor dn = s.getDatanodeDescriptor();
      allRacks.add(dn.getNetworkLocation());
      if (dn.isAlive() && !dn.isDecommissioned() && !bm().isExcess(dn, g)
          && !bm().isReplicaCorrupt(g, dn)) {
        racksPerIndex.computeIfAbsent((int) g.getStorageBlockIndex(s),
            k -> new HashSet<>()).add(dn.getNetworkLocation());
      }
    }
    for (String rack : allRacks) {
      int survivors = 0;
      for (Set<String> racks : racksPerIndex.values()) {
        if (!racks.equals(Collections.singleton(rack))) {
          survivors++;
        }
      }
      if (survivors < g.getRealDataBlockNum()) {
        return g + " would not survive losing " + rack + ": " + racksPerIndex;
      }
    }
    return null;
  }

  private void assertReadable(Path file, byte[] expected, String context)
      throws Exception {
    assertArrayEquals(context, expected,
        DFSTestUtil.readFileAsBytes(fs, file));
  }

  /**
   * Read the file with every node in each rack stopped, one rack at a time,
   * once the NameNode considers those nodes stale (what a NameNode configured
   * to avoid stale nodes for reads does within the stale interval of a real
   * rack outage).
   */
  private void assertSurvivesEachRackOutage(Path file, byte[] expected)
      throws Exception {
    for (String rack : new ArrayList<>(racks())) {
      List<MiniDFSCluster.DataNodeProperties> stopped = new ArrayList<>();
      final List<DatanodeDescriptor> offline = new ArrayList<>();
      for (DataNode dn : new ArrayList<>(cluster.getDataNodes())) {
        DatanodeDescriptor d =
            bm().getDatanodeManager().getDatanode(dn.getDatanodeId());
        if (d != null && rack.equals(d.getNetworkLocation())) {
          offline.add(d);
          stopped.add(cluster.stopDataNode(dn.getDatanodeId().getXferAddr()));
        }
      }
      GenericTestUtils.waitFor(() -> {
        for (DatanodeDescriptor d : offline) {
          if (!d.isStale(STALE_INTERVAL_MS)) {
            return false;
          }
        }
        return true;
      }, 200, 60000);
      LOG.info("Reading with {} ({} nodes) offline", rack, stopped.size());
      assertReadable(file, expected, "read with " + rack + " offline");
      for (MiniDFSCluster.DataNodeProperties p : stopped) {
        cluster.restartDataNode(p, true);
      }
      cluster.waitActive();
    }
  }

  @Test(timeout = 900000)
  public void testUnsafeWriteRelievedReclaimedDecommissionedAndRestarted()
      throws Exception {
    // 10 nodes: /a has 1, /b has 7, /c has 2. RS-6-3 groups land 1/6/2, so /b
    // holds 6 sole internal blocks against a budget of 3.
    String[] racks = {"/a", "/b", "/b", "/b", "/b", "/b", "/b", "/b", "/c",
        "/c"};
    String[] hosts = new String[racks.length];
    for (int i = 0; i < hosts.length; i++) {
      hosts[i] = "host-" + racks[i].substring(1) + i;
    }
    HdfsConfiguration conf = newConf();
    cluster = new MiniDFSCluster.Builder(conf).racks(racks).hosts(hosts)
        .numDataNodes(racks.length).build();
    cluster.waitActive();
    fs = cluster.getFileSystem();
    fs.enableErasureCodingPolicy(ecPolicy.getName());
    fs.setErasureCodingPolicy(new Path("/"), ecPolicy.getName());

    // 4 full block groups plus a partial one.
    final Path file = new Path("/ec-file");
    final byte[] data = StripedFileTestUtil.generateBytes(
        cellSize * dataBlocks * 2 * 4 + cellSize * 3 + 123);
    DFSTestUtil.writeFile(fs, file, data);
    assertReadable(file, data, "after write");

    NoLossMonitor monitor = new NoLossMonitor(file);
    monitor.start();

    // The scenario is only meaningful if some group starts unsafe.
    boolean anyUnsafe = false;
    cluster.getNamesystem().readLock();
    try {
      for (BlockInfoStriped g : groups(file)) {
        if (!status(g, liveCopies(g), -1, -1).isPlacementPolicySatisfied()) {
          anyUnsafe = true;
        }
      }
    } finally {
      cluster.getNamesystem().readUnlock();
    }
    assertTrue("expected a group to start unsafely placed", anyUnsafe);

    // Hold back excess processing (it postpones blocks with a replica on a
    // storage whose contents are stale) so every relief copy lands before any
    // reclaiming. The reclaim pass then sees several duplicates at once, as
    // the startup/failover scan does for already over-replicated groups.
    setContentsStale(true);

    // Grow /a and /c so relief copies have somewhere to go.
    cluster.startDataNodes(conf, 6, true, null,
        new String[] {"/a", "/a", "/a", "/c", "/c", "/c"},
        new String[] {"host-a10", "host-a11", "host-a12", "host-c13",
            "host-c14", "host-c15"}, null, false);
    cluster.waitActive();

    final AtomicReference<String> pending = new AtomicReference<>();
    try {
      GenericTestUtils.waitFor(() -> {
        cluster.getNamesystem().readLock();
        try {
          for (BlockInfoStriped g : groups(file)) {
            Map<Integer, List<String>> copies = liveCopies(g);
            if (copies.size() != g.getRealTotalBlockNum()
                || bm().pendingReconstruction.getNumReplicas(g) > 0
                || !status(g, copies, -1, -1).isPlacementPolicySatisfied()) {
              pending.set(g + " " + copies);
              return false;
            }
          }
          return true;
        } finally {
          cluster.getNamesystem().readUnlock();
        }
      }, 500, 180000);
    } catch (Exception e) {
      throw new AssertionError("relief did not complete: " + pending.get(), e);
    }
    int multiDuplicateGroups = 0;
    cluster.getNamesystem().readLock();
    try {
      for (BlockInfoStriped g : groups(file)) {
        int duplicated = 0;
        for (List<String> racksOfIndex : liveCopies(g).values()) {
          if (racksOfIndex.size() > 1) {
            duplicated++;
          }
        }
        if (duplicated > 1) {
          multiDuplicateGroups++;
        }
      }
    } finally {
      cluster.getNamesystem().readUnlock();
    }
    assertTrue("expected groups holding several duplicates before reclaiming",
        multiDuplicateGroups > 0);
    assertReadable(file, data, "after relief, before reclaim");

    // Every group is now safely placed. From here on, reclaiming (and
    // everything after it) must never leave a group unable to survive the loss
    // of any rack, even transiently.
    monitor.arm();
    setContentsStale(false);
    waitUntilSettled(file, "after growing the cluster");
    assertTrue("relief copies should have been made and then partly reclaimed",
        monitor.groupsReclaimed() > 0);

    assertReadable(file, data, "after relief and reclaim");

    // Decommission the original /a node, which held the only /a copy of every
    // group before the cluster grew.
    DataNode original = null;
    for (DataNode dn : cluster.getDataNodes()) {
      if (dn.getDatanodeId().getHostName().equals(hosts[0])) {
        original = dn;
      }
    }
    assertNotNull(original);
    DatanodeManager dm = bm().getDatanodeManager();
    final DatanodeDescriptor decom = dm.getDatanode(original.getDatanodeId());
    DatanodeAdminManager admin = (DatanodeAdminManager)
        Whitebox.getInternalState(dm, "datanodeAdminManager");
    cluster.getNamesystem().writeLock();
    try {
      admin.startDecommission(decom);
    } finally {
      cluster.getNamesystem().writeUnlock();
    }
    GenericTestUtils.waitFor(decom::isDecommissioned, 500, 180000);
    waitUntilSettled(file, "after decommission");
    assertReadable(file, data, "after decommission");

    // A NameNode restart re-scans every block for excess redundancy (the
    // cleanup path relied on in production); it must not reclaim anything
    // that protects the group.
    monitor.finish();
    cluster.restartNameNode(true);
    cluster.waitActive();
    fs = cluster.getFileSystem();
    monitor = new NoLossMonitor(file);
    monitor.start();
    GenericTestUtils.waitFor(() -> !cluster.getNamesystem().isInSafeMode(),
        500, 60000);
    waitUntilSettled(file, "after NameNode restart");
    monitor.finish();

    assertSurvivesEachRackOutage(file, data);
    assertFalse(cluster.getNamesystem().isInSafeMode());
  }
}
