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
package org.apache.hadoop.hdfs.server.balancer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.protocol.DatanodeInfo;
import org.apache.hadoop.hdfs.server.blockmanagement.BlockPlacementPolicyErasureCoding;
import org.apache.hadoop.hdfs.server.blockmanagement.BlockPlacementStatusErasureCoding;
import org.apache.hadoop.net.NetworkTopology;
import org.apache.hadoop.net.Node;
import org.apache.hadoop.net.NodeBase;

/**
 * Decides whether moving an internal block of an erasure-coded block group is
 * one the NameNode will complete.
 * <p>
 * When the moved copy arrives, the NameNode deletes the source copy only if
 * the group, judged by {@link BlockPlacementPolicyErasureCoding} on the copies
 * of in-service datanodes, is no less safe without it than with both copies.
 * Otherwise it keeps the source and may delete the new copy, undoing the move.
 * This class applies the same test before a move is scheduled, so the
 * Balancer and Mover do not keep retrying moves the NameNode reverts.
 * <p>
 * To agree with the NameNode it needs the same view of the cluster: every
 * live datanode, including those the Balancer excludes or ignores, with
 * decommissioning and decommissioned datanodes not counting towards the number
 * of occupied racks.
 */
@InterfaceAudience.Private
class ErasureCodingMoveChecker {

  private final NetworkTopology topology;
  private final BlockPlacementPolicyErasureCoding policy;
  /** The in-service live datanodes, by datanode UUID. */
  private final Map<String, DatanodeInfo> inServiceNodes = new HashMap<>();

  ErasureCodingMoveChecker(Configuration conf) {
    topology = NetworkTopology.getInstance(conf);
    policy = new BlockPlacementPolicyErasureCoding();
    policy.initialize(conf, null, topology, null);
  }

  /**
   * Record a live datanode. Must be called for every live datanode, before
   * any call to {@link #isMovable}.
   */
  void addDatanode(DatanodeInfo dn) {
    // The Dispatcher adds the DatanodeInfo itself to its own topology; a node
    // can only belong to one, so add a stand-in here.
    final Node node = new NodeBase(dn.getDatanodeUuid(),
        dn.getNetworkLocation());
    topology.add(node);
    if (dn.isDecommissionInProgress() || dn.isDecommissioned()) {
      topology.decommissionNode(node);
    }
    if (dn.isInService()) {
      inServiceNodes.put(dn.getDatanodeUuid(), dn);
    }
  }

  /**
   * Whether moving the internal block held by {@code source} to {@code target}
   * leaves the group no less safe, by both measures of the erasure-coded
   * placement status (racks occupied and internal blocks at risk of a
   * single-domain loss).
   *
   * @param datanodeUuids the datanode of each copy of the group, as reported
   *          by the NameNode, including those on nodes the caller ignores
   * @param indices the internal block index of each copy
   * @param totalInternalBlocks the number of internal blocks of the group
   * @param parityBlockNum the parity count of the group's scheme
   * @return whether the move is allowed, or null if {@code source} holds no
   *         copy in the given placement and so the move cannot be judged
   */
  Boolean isMovable(String[] datanodeUuids, byte[] indices,
      int totalInternalBlocks, int parityBlockNum, DatanodeInfo source,
      DatanodeInfo target) {
    int sourcePos = -1;
    for (int i = 0; i < datanodeUuids.length; i++) {
      if (datanodeUuids[i].equals(source.getDatanodeUuid())) {
        sourcePos = i;
        break;
      }
    }
    if (sourcePos < 0) {
      return null;
    }

    final List<DatanodeInfo> withLocs = new ArrayList<>();
    final List<Byte> withIndices = new ArrayList<>();
    final List<DatanodeInfo> withoutLocs = new ArrayList<>();
    final List<Byte> withoutIndices = new ArrayList<>();
    for (int i = 0; i < datanodeUuids.length; i++) {
      final DatanodeInfo dn = inServiceNodes.get(datanodeUuids[i]);
      if (dn == null) {
        continue;
      }
      withLocs.add(dn);
      withIndices.add(indices[i]);
      if (i != sourcePos) {
        withoutLocs.add(dn);
        withoutIndices.add(indices[i]);
      }
    }
    withLocs.add(target);
    withIndices.add(indices[sourcePos]);
    withoutLocs.add(target);
    withoutIndices.add(indices[sourcePos]);

    final BlockPlacementStatusErasureCoding withSource =
        status(withLocs, withIndices, totalInternalBlocks, parityBlockNum);
    final BlockPlacementStatusErasureCoding withoutSource =
        status(withoutLocs, withoutIndices, totalInternalBlocks,
            parityBlockNum);
    return !withoutSource.isLessSafeThan(withSource);
  }

  private BlockPlacementStatusErasureCoding status(List<DatanodeInfo> locs,
      List<Byte> indices, int totalInternalBlocks, int parityBlockNum) {
    final byte[] indexArray = new byte[indices.size()];
    for (int i = 0; i < indexArray.length; i++) {
      indexArray[i] = indices.get(i);
    }
    return policy.verifyBlockPlacement(locs.toArray(new DatanodeInfo[0]),
        indexArray, totalInternalBlocks, parityBlockNum);
  }
}
