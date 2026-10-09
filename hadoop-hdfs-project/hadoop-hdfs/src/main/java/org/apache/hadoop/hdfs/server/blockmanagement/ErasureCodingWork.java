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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

class ErasureCodingWork extends BlockReconstructionWork {
  private final byte[] liveBlockIndices;
  private final byte[] liveBusyBlockIndices;
  private final byte[] excludeReconstructedIndices;
  private final String blockPoolId;
  /**
   * Whether every internal block is live and the work only fixes placement,
   * so its targets should be in failure domains that can take a moved
   * internal block.
   */
  private boolean placementOnly = false;

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

  /** Mark the work as only fixing placement; see {@link #chooseTargets}. */
  void setPlacementOnly() {
    placementOnly = true;
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
      // When only placement needs fixing, first try domains with room for a
      // moved internal block, so each relieving copy can later become a move
      // rather than permanent over-replication.
      final Set<Node> excluded = placementOnly
          ? excludeDomainsWithoutRoom(blockplacement, excludedNodes)
          : excludedNodes;
      chosenTargets = blockplacement.chooseTarget(
          getSrcPath(), getAdditionalReplRequired(), getSrcNodes()[0],
          getLiveReplicaStorages(), false, excluded, getBlockSize(),
          storagePolicySuite.getPolicy(getStoragePolicyID()), null);
      if ((chosenTargets == null || chosenTargets.length == 0)
          && excluded != excludedNodes) {
        // No domain has room: relieving the placement still comes first.
        chosenTargets = blockplacement.chooseTarget(
            getSrcPath(), getAdditionalReplRequired(), getSrcNodes()[0],
            getLiveReplicaStorages(), false, excludedNodes, getBlockSize(),
            storagePolicySuite.getPolicy(getStoragePolicyID()), null);
      }
    } else {
      LOG.warn("ErasureCodingWork could not need choose targets for {}", getBlock());
    }
    setTargets(chosenTargets);
  }

  /**
   * {@code excludedNodes} plus every node in a failure domain that already
   * holds the maximum number of internal blocks that exist nowhere else. A
   * copy relieving another domain can never become the only copy there.
   */
  private Set<Node> excludeDomainsWithoutRoom(
      BlockPlacementPolicy blockplacement, Set<Node> excludedNodes) {
    final BlockPlacementPolicyErasureCoding policy =
        (BlockPlacementPolicyErasureCoding) blockplacement;
    if (policy.clusterMap == null) {
      return excludedNodes;
    }
    final BlockInfoStriped stripedBlk = (BlockInfoStriped) getBlock();
    final List<DatanodeStorageInfo> live = getLiveReplicaStorages();
    final String[] domains = new String[live.size()];
    final byte[] indices = new byte[live.size()];
    for (int i = 0; i < live.size(); i++) {
      domains[i] = live.get(i).getDatanodeDescriptor().getNetworkLocation();
      indices[i] = (byte) stripedBlk.getStorageBlockIndex(live.get(i));
    }
    final Set<String> full = policy.domainsWithoutSpareCapacity(domains,
        indices, stripedBlk.getRealTotalBlockNum(),
        stripedBlk.getParityBlockNum());
    if (full.isEmpty()) {
      return excludedNodes;
    }
    final Set<Node> excluded = new HashSet<>(excludedNodes);
    for (String domain : full) {
      excluded.addAll(policy.clusterMap.getDatanodesInRack(domain));
    }
    return excluded;
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
   * Targets in domains with room come first: a domain has room while it holds
   * fewer sole-copy internal blocks than the budget, counting the ones it is
   * about to receive. A relieving copy in such a domain can later become the
   * only copy (the excess handling reclaims the original), so the copy is
   * really a move; a copy into a domain without room can never be, and stays
   * as over-replication. Among targets with room, domains the group does not
   * occupy yet come first, so the copies also extend the group's spread. A
   * target for which no copy improves placement is left unused.
   *
   * @return the targets that were given a copy
   */
  private DatanodeStorageInfo[] addPlacementRelievingCopies(
      DatanodeStorageInfo[] targets, BlockInfoStriped stripedBlk) {
    final int budget = stripedBlk.getParityBlockNum();
    final DatanodeDescriptor[] srcNodes = getSrcNodes();

    // The domains holding a live copy of each internal block. Copies on
    // decommissioning or maintenance nodes do not protect the group, matching
    // the policy's verification.
    Map<Byte, Set<String>> domainsPerIndex = new HashMap<>();
    for (DatanodeStorageInfo storage : getLiveReplicaStorages()) {
      int index = stripedBlk.getStorageBlockIndex(storage);
      if (index < 0) {
        continue;
      }
      domainsPerIndex.computeIfAbsent((byte) index, k -> new HashSet<>())
          .add(storage.getDatanodeDescriptor().getNetworkLocation());
    }

    // Sole-copy internal blocks each domain will hold once the originals of
    // the relieving copies made here are reclaimed.
    final Map<String, Integer> projected =
        soleCopiesPerDomain(domainsPerIndex);
    final List<DatanodeStorageInfo> remaining =
        new ArrayList<>(Arrays.asList(targets));
    List<DatanodeStorageInfo> used = new ArrayList<>(targets.length);
    while (!remaining.isEmpty()) {
      final Set<String> occupied = occupiedDomains(domainsPerIndex);
      DatanodeStorageInfo target = remaining.get(0);
      int targetRank = Integer.MAX_VALUE;
      for (DatanodeStorageInfo candidate : remaining) {
        final String domain =
            candidate.getDatanodeDescriptor().getNetworkLocation();
        final int rank = (projected.getOrDefault(domain, 0) < budget ? 0 : 2)
            + (occupied.contains(domain) ? 1 : 0);
        if (rank < targetRank) {
          targetRank = rank;
          target = candidate;
        }
      }
      remaining.remove(target);
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
      if (bestScore > 1) {
        // The copy relieves an over-budget domain, so once the original is
        // reclaimed the target's domain holds this internal block alone.
        projected.merge(targetDomain, 1, Integer::sum);
      }
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

  private static Map<String, Integer> soleCopiesPerDomain(
      Map<Byte, Set<String>> domainsPerIndex) {
    Map<String, Integer> soleCopies = new HashMap<>();
    for (Set<String> domains : domainsPerIndex.values()) {
      if (domains.size() == 1) {
        soleCopies.merge(domains.iterator().next(), 1, Integer::sum);
      }
    }
    return soleCopies;
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
