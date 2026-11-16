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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.hadoop.hdfs.util.LightWeightLinkedSet;
import org.apache.hadoop.util.Timer;

/**
 * Deduplicated consolidation work ordered by its next eligible attempt.
 * Guarded by the namesystem lock.
 */
final class EcConsolidationQueue {
  static final long RETRY_DELAY_MS = 30_000L;

  private final Timer timer;
  private final Map<BlockInfo, Long> scheduled = new HashMap<>();
  private final TreeMap<Long, LightWeightLinkedSet<BlockInfo>> attempts =
      new TreeMap<>();

  EcConsolidationQueue() {
    this(new Timer());
  }

  EcConsolidationQueue(Timer timer) {
    this.timer = timer;
  }

  void add(BlockInfo block) {
    if (!contains(block)) {
      add(block, timer.monotonicNow());
    }
  }

  void retry(BlockInfo block) {
    remove(block);
    add(block, timer.monotonicNow() + RETRY_DELAY_MS);
  }

  private void add(BlockInfo block, long notBefore) {
    scheduled.put(block, notBefore);
    attempts.computeIfAbsent(notBefore, t -> new LightWeightLinkedSet<>())
        .add(block);
  }

  boolean contains(BlockInfo block) {
    return scheduled.containsKey(block);
  }

  void remove(BlockInfo block) {
    Long notBefore = scheduled.remove(block);
    if (notBefore != null) {
      LightWeightLinkedSet<BlockInfo> blocks = attempts.get(notBefore);
      blocks.remove(block);
      if (blocks.isEmpty()) {
        attempts.remove(notBefore);
      }
    }
  }

  List<BlockInfo> pollN(int limit) {
    List<BlockInfo> ready = new ArrayList<>();
    long now = timer.monotonicNow();
    while (ready.size() < limit && !attempts.isEmpty()
        && attempts.firstKey() <= now) {
      Map.Entry<Long, LightWeightLinkedSet<BlockInfo>> first =
          attempts.firstEntry();
      for (BlockInfo block : first.getValue().pollN(limit - ready.size())) {
        scheduled.remove(block);
        ready.add(block);
      }
      if (first.getValue().isEmpty()) {
        attempts.pollFirstEntry();
      }
    }
    return ready;
  }

  int size() {
    return scheduled.size();
  }

  boolean isEmpty() {
    return scheduled.isEmpty();
  }

  void clear() {
    scheduled.clear();
    attempts.clear();
  }
}
