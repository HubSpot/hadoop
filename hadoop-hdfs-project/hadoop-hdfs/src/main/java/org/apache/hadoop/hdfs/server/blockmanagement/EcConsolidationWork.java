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

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.hadoop.hdfs.protocol.Block;
import org.apache.hadoop.hdfs.util.StripedBlockUtil;
import org.apache.hadoop.net.Node;
import org.apache.hadoop.net.NodeBase;

/**
 * Copies one internal block of a safely placed erasure-coded block group into
 * a given failure domain, so that the excess handling can then reclaim more
 * than one existing copy of it: in effect a move that reduces the group's
 * over-replication. See
 * {@link BlockPlacementPolicyErasureCoding#planConsolidation}.
 */
class EcConsolidationWork extends BlockReconstructionWork {
  private final byte index;
  private final String domain;

  EcConsolidationWork(BlockInfoStriped block, BlockCollection bc,
      DatanodeDescriptor source, List<DatanodeDescriptor> containingNodes,
      List<DatanodeStorageInfo> liveReplicaStorages, byte index,
      String domain) {
    super(block, bc, new DatanodeDescriptor[] {source}, containingNodes,
        liveReplicaStorages, 1,
        LowRedundancyBlocks.QUEUE_REPLICAS_BADLY_DISTRIBUTED);
    this.index = index;
    this.domain = domain;
  }

  /** The internal block to copy. */
  byte getIndex() {
    return index;
  }

  /** The failure domain to copy it into. */
  String getDomain() {
    return domain;
  }

  /** Choose one target in {@link #getDomain()}, or none. */
  @Override
  void chooseTargets(BlockPlacementPolicy blockplacement,
      BlockStoragePolicySuite storagePolicySuite, Set<Node> excludedNodes) {
    if (getBlock().isDeleted()) {
      setTargets(null);
      return;
    }
    final BlockPlacementPolicyDefault policy =
        (BlockPlacementPolicyDefault) blockplacement;
    final Set<Node> excluded = new HashSet<>(excludedNodes);
    for (Node leaf : policy.clusterMap.getLeaves(NodeBase.ROOT)) {
      if (!domain.equals(leaf.getNetworkLocation())) {
        excluded.add(leaf);
      }
    }
    DatanodeStorageInfo[] chosen = blockplacement.chooseTarget(getSrcPath(),
        1, getSrcNodes()[0], getLiveReplicaStorages(), false, excluded,
        getBlockSize(), storagePolicySuite.getPolicy(getStoragePolicyID()),
        null);
    if (chosen != null && chosen.length > 0 && domain.equals(
        chosen[0].getDatanodeDescriptor().getNetworkLocation())) {
      setTargets(new DatanodeStorageInfo[] {chosen[0]});
    } else {
      setTargets(null);
    }
  }

  @Override
  void addTaskToDatanode(NumberReplicas numberReplicas) {
    final BlockInfoStriped stripedBlk = (BlockInfoStriped) getBlock();
    final long internalBlockLength = StripedBlockUtil.getInternalBlockLength(
        stripedBlk.getNumBytes(), stripedBlk.getCellSize(),
        stripedBlk.getDataBlockNum(), index);
    final Block internalBlock = new Block(stripedBlk.getBlockId() + index,
        internalBlockLength, stripedBlk.getGenerationStamp());
    getSrcNodes()[0].addECBlockToBeReplicated(internalBlock, getTargets());
    LOG.debug("Add consolidation task from source {} to target {} for EC"
        + " block {}", getSrcNodes()[0], getTargets()[0], internalBlock);
  }
}
