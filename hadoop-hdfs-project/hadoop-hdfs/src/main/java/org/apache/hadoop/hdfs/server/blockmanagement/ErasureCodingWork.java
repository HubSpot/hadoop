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

import org.apache.hadoop.hdfs.protocol.Block;
import org.apache.hadoop.hdfs.protocol.ExtendedBlock;
import org.apache.hadoop.hdfs.util.StripedBlockUtil;
import org.apache.hadoop.net.Node;
import org.apache.hadoop.util.Preconditions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.apache.hadoop.hdfs.server.blockmanagement.BlockPlacementPolicyErasureCoding.soleCopiesPerDomain;

class ErasureCodingWork extends BlockReconstructionWork {
  private final byte[] liveBlockIndices;
  private final byte[] liveBusyBlockIndices;
  private final byte[] excludeReconstructedIndices;
  private final String blockPoolId;
  /**
   * The storages the group's placement was judged on (see
   * {@link BlockManager#getPlacementStorages}), when only placement needs
   * fixing.
   */
  private List<DatanodeStorageInfo> placementStorages;

  public ErasureCodingWork(String blockPoolId, BlockInfo block,
      BlockCollection bc,
      DatanodeDescriptor[] srcNodes,
      List<DatanodeDescriptor> containingNodes,
      List<DatanodeStorageInfo> liveReplicaStorages,
      int additionalReplRequired, int priority,
      byte[] liveBlockIndices, byte[] liveBusyBlockIndices,
      byte[] excludeReconstrutedIndices) {
    super(block, bc, srcNodes, containingNodes,
        liveReplicaStorages, additionalReplRequired, priority);
    this.blockPoolId = blockPoolId;
    this.liveBlockIndices = liveBlockIndices;
    this.liveBusyBlockIndices = liveBusyBlockIndices;
    this.excludeReconstructedIndices = excludeReconstrutedIndices;
    LOG.debug("Creating an ErasureCodingWork to {} reconstruct ",
        block);
  }

  byte[] getLiveBlockIndices() {
    return liveBlockIndices;
  }

  /**
   * Set the storages the group's placement was judged on, so that relief
   * copies are chosen against the same view of the placement.
   */
  void setPlacementStorages(List<DatanodeStorageInfo> storages) {
    this.placementStorages = storages;
  }

  @Override
  void chooseTargets(BlockPlacementPolicy blockplacement,
      BlockStoragePolicySuite storagePolicySuite,
      Set<Node> excludedNodes) {
    // TODO: new placement policy for EC considering multiple writers
    DatanodeStorageInfo[] chosenTargets = null;
    // HDFS-14720. If the block is deleted, the block size will become
    // BlockCommand.NO_ACK (LONG.MAX_VALUE) . This kind of block we don't need
    // to send for replication or reconstruction
    if (!getBlock().isDeleted()) {
      chosenTargets = blockplacement.chooseTarget(
          getSrcPath(), getAdditionalReplRequired(), getSrcNodes()[0],
          getLiveReplicaStorages(), false, excludedNodes, getBlockSize(),
          storagePolicySuite.getPolicy(getStoragePolicyID()), null);
    } else {
      LOG.warn("ErasureCodingWork could not need choose targets for {}", getBlock());
    }
    setTargets(chosenTargets);
  }

  /**
   * @return true if the current source nodes cover all the internal blocks.
   * I.e., we only need to have more racks.
   */
  private boolean hasAllInternalBlocks() {
    final BlockInfoStriped block = (BlockInfoStriped) getBlock();
    if (liveBlockIndices.length
        + liveBusyBlockIndices.length < block.getRealTotalBlockNum()) {
      return false;
    }
    BitSet bitSet = new BitSet(block.getTotalBlockNum());
    for (byte index : liveBlockIndices) {
      bitSet.set(index);
    }
    for (byte busyIndex: liveBusyBlockIndices) {
      bitSet.set(busyIndex);
    }
    for (int i = 0; i < block.getRealDataBlockNum(); i++) {
      if (!bitSet.get(i)) {
        return false;
      }
    }
    for (int i = block.getDataBlockNum(); i < block.getTotalBlockNum(); i++) {
      if (!bitSet.get(i)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Schedule copies of existing internal blocks into {@code targets} so that
   * the group's placement satisfies {@link BlockPlacementPolicyErasureCoding}:
   * no failure domain may hold more than the parity count of internal blocks
   * that exist nowhere else, and the group should occupy every domain it can.
   * <p>
   * Each target receives at most one copy, picked greedily:
   * <ol>
   *   <li>An internal block whose only copies are in the most over-budget
   *   domain (other than the target's), since copying it out reduces that
   *   domain's exposure by one.</li>
   *   <li>Otherwise, if the target's domain holds no copy of the group yet, any
   *   internal block, since the copy adds a domain to the group.</li>
   * </ol>
   * A target for which no copy improves placement is left unused.
   *
   * @return the targets that were given a copy
   */
  private DatanodeStorageInfo[] addPlacementRelievingCopies(
      DatanodeStorageInfo[] targets, BlockInfoStriped stripedBlk) {
    final int budget = stripedBlk.getParityBlockNum();
    final DatanodeDescriptor[] srcNodes = getSrcNodes();

    // The domains holding a copy of each internal block, counted exactly as
    // the placement policy counted them when it asked for these copies.
    Preconditions.checkState(placementStorages != null,
        "Placement storages of %s were not set", stripedBlk);
    Map<Byte, Set<String>> domainsPerIndex = new HashMap<>();
    for (DatanodeStorageInfo storage : placementStorages) {
      int index = stripedBlk.getStorageBlockIndex(storage);
      if (index < 0) {
        continue;
      }
      domainsPerIndex.computeIfAbsent((byte) index, k -> new HashSet<>())
          .add(storage.getDatanodeDescriptor().getNetworkLocation());
    }

    List<DatanodeStorageInfo> used = new ArrayList<>(targets.length);
    for (DatanodeStorageInfo target : targets) {
      final Set<String> occupied = occupiedDomains(domainsPerIndex);
      final String targetDomain =
          target.getDatanodeDescriptor().getNetworkLocation();
      final boolean newDomain = !occupied.contains(targetDomain);
      final Map<String, Integer> soleCopies = soleCopiesPerDomain(
          domainsPerIndex);

      int bestSource = -1;
      int bestScore = 0;
      for (int i = 0; i < srcNodes.length; i++) {
        final byte index = liveBlockIndices[i];
        final Set<String> domains = domainsPerIndex.get(index);
        if (domains != null && domains.contains(targetDomain)) {
          // The target's domain already has this internal block.
          continue;
        }
        int score = 0;
        if (domains != null && domains.size() == 1) {
          final int exposure = soleCopies.get(domains.iterator().next());
          if (exposure > budget) {
            // Relieves an over-budget domain; prefer the most exposed one.
            score = 2 + exposure;
          }
        }
        if (score == 0 && newDomain) {
          score = 1;
        }
        if (score > bestScore || (score == bestScore && score > 0
            && !srcNodes[bestSource].isInService()
            && srcNodes[i].isInService())) {
          bestScore = score;
          bestSource = i;
        }
      }
      if (bestSource < 0) {
        LOG.debug("No internal block of {} improves placement when copied to"
            + " {}; leaving the target unused", stripedBlk, target);
        continue;
      }
      createReplicationWork(bestSource, target);
      used.add(target);
      domainsPerIndex.computeIfAbsent(liveBlockIndices[bestSource],
          k -> new HashSet<>()).add(targetDomain);
    }
    return used.toArray(new DatanodeStorageInfo[0]);
  }

  private static Set<String> occupiedDomains(
      Map<Byte, Set<String>> domainsPerIndex) {
    Set<String> occupied = new HashSet<>();
    for (Set<String> domains : domainsPerIndex.values()) {
      occupied.addAll(domains);
    }
    return occupied;
  }

  @Override
  void addTaskToDatanode(NumberReplicas numberReplicas) {
    final DatanodeStorageInfo[] targets = getTargets();
    assert targets.length > 0;
    BlockInfoStriped stripedBlk = (BlockInfoStriped) getBlock();

    // Some branches below give work to only some of the chosen targets; the
    // targets are trimmed to those so that pending reconstruction only waits
    // for copies that were actually requested.
    if (hasNotEnoughRack()) {
      // All internal blocks are live but the placement is unsafe. Copy
      // internal blocks into the targets, each one chosen to fix the placement.
      setTargets(addPlacementRelievingCopies(targets, stripedBlk));
    } else if ((numberReplicas.decommissioning() > 0 ||
        numberReplicas.liveEnteringMaintenanceReplicas() > 0) &&
        hasAllInternalBlocks()) {
      List<Integer> leavingServiceSources = findLeavingServiceSources();
      // decommissioningSources.size() should be >= targets.length
      final int num = Math.min(leavingServiceSources.size(), targets.length);
      for (int i = 0; i < num; i++) {
        createReplicationWork(leavingServiceSources.get(i), targets[i]);
      }
      setTargets(Arrays.copyOf(targets, num));
    } else {
      targets[0].getDatanodeDescriptor().addBlockToBeErasureCoded(
          new ExtendedBlock(blockPoolId, stripedBlk), getSrcNodes(), targets,
          liveBlockIndices, excludeReconstructedIndices, stripedBlk.getErasureCodingPolicy());
    }
  }

  private void createReplicationWork(int sourceIndex,
      DatanodeStorageInfo target) {
    BlockInfoStriped stripedBlk = (BlockInfoStriped) getBlock();
    final byte blockIndex = liveBlockIndices[sourceIndex];
    final DatanodeDescriptor source = getSrcNodes()[sourceIndex];
    final long internBlkLen = StripedBlockUtil.getInternalBlockLength(
        stripedBlk.getNumBytes(), stripedBlk.getCellSize(),
        stripedBlk.getDataBlockNum(), blockIndex);
    final Block targetBlk = new Block(stripedBlk.getBlockId() + blockIndex,
        internBlkLen, stripedBlk.getGenerationStamp());
    source.addECBlockToBeReplicated(targetBlk,
        new DatanodeStorageInfo[] {target});
    LOG.debug("Add replication task from source {} to "
        + "target {} for EC block {}", source, target, targetBlk);
  }

  private List<Integer> findLeavingServiceSources() {
    // Mark the block in normal node.
    BlockInfoStriped block = (BlockInfoStriped)getBlock();
    BitSet bitSet = new BitSet(block.getRealTotalBlockNum());
    for (int i = 0; i < getSrcNodes().length; i++) {
      if (getSrcNodes()[i].isInService()) {
        bitSet.set(liveBlockIndices[i]);
      }
    }
    // If the block is on the node which is decommissioning or
    // entering_maintenance, and it doesn't exist on other normal nodes,
    // we just add the node into source list.
    List<Integer> srcIndices = new ArrayList<>();
    for (int i = 0; i < getSrcNodes().length; i++) {
      if ((getSrcNodes()[i].isDecommissionInProgress() ||
          (getSrcNodes()[i].isEnteringMaintenance() &&
          getSrcNodes()[i].isAlive())) &&
          !bitSet.get(liveBlockIndices[i])) {
        srcIndices.add(i);
      }
    }
    return srcIndices;
  }
}
