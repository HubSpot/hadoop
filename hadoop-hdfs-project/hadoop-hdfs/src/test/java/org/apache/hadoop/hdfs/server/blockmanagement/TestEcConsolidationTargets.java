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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.StorageType;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.DFSTestUtil;
import org.apache.hadoop.hdfs.protocol.BlockStoragePolicy;
import org.apache.hadoop.net.NetworkTopology;
import org.junit.Test;
import org.mockito.Mockito;

public class TestEcConsolidationTargets {
  private static final long BLOCK_SIZE = 1024;
  private final NetworkTopology topology = new NetworkTopology();
  private int nextHost;

  private DatanodeStorageInfo storage(String rack, StorageType type) {
    int host = ++nextHost;
    DatanodeStorageInfo storage = DFSTestUtil.createDatanodeStorageInfo(
        "s" + host, "10.0.0." + host, rack, "host" + host, type, null);
    storage = storage.getDatanodeDescriptor().getStorageInfos()[0];
    storage.setUtilizationForTesting(1024 * BLOCK_SIZE, 0,
        1024 * BLOCK_SIZE, 0);
    storage.getDatanodeDescriptor().setAlive(true);
    topology.add(storage.getDatanodeDescriptor());
    return storage;
  }

  private BlockPlacementPolicyErasureCoding policy() {
    Configuration conf = new Configuration();
    conf.setBoolean(DFSConfigKeys.DFS_NAMENODE_REDUNDANCY_CONSIDERLOAD_KEY,
        false);
    BlockPlacementPolicyErasureCoding policy =
        new BlockPlacementPolicyErasureCoding();
    policy.initialize(conf, Mockito.mock(FSClusterStats.class), topology,
        new Host2NodesMap());
    return policy;
  }

  @Test
  public void testDoesNotFallBackToDifferentStorageType() {
    DatanodeStorageInfo source = storage("/source", StorageType.SSD);
    storage("/target", StorageType.DISK);
    BlockStoragePolicy allSsd =
        BlockStoragePolicySuite.createDefaultSuite().getPolicy("ALL_SSD");
    assertEquals(Collections.singletonList(StorageType.DISK),
        allSsd.chooseStorageTypes((short) 2,
            Collections.singletonList(StorageType.SSD),
            EnumSet.of(StorageType.SSD), false));
    assertNull(policy().chooseConsolidationTarget("/target",
        Collections.singletonList(source), new HashSet<>(), BLOCK_SIZE,
        allSsd));
  }

  @Test
  public void testSameStorageTypeMayBePolicyFallback() {
    DatanodeStorageInfo source = storage("/source", StorageType.DISK);
    DatanodeStorageInfo target = storage("/target", StorageType.DISK);
    BlockStoragePolicy allSsd =
        BlockStoragePolicySuite.createDefaultSuite().getPolicy("ALL_SSD");
    assertEquals(target, policy().chooseConsolidationTarget("/target",
        Collections.singletonList(source), new HashSet<>(), BLOCK_SIZE,
        allSsd));
  }
}
