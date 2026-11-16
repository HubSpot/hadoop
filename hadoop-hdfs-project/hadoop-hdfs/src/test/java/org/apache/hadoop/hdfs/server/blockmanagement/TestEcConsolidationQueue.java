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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.apache.hadoop.hdfs.protocol.Block;
import org.apache.hadoop.util.FakeTimer;
import org.junit.Test;

public class TestEcConsolidationQueue {
  private final FakeTimer timer = new FakeTimer();
  private final EcConsolidationQueue queue = new EcConsolidationQueue(timer);

  private BlockInfo block(long id) {
    return new BlockInfoContiguous(new Block(id), (short) 1);
  }

  @Test
  public void testRetryDoesNotBlockReadyWork() {
    BlockInfo delayed = block(1);
    BlockInfo ready = block(2);
    queue.retry(delayed);
    queue.add(ready);
    assertEquals(Collections.singletonList(ready), queue.pollN(10));
    assertTrue(queue.contains(delayed));
    timer.advance(EcConsolidationQueue.RETRY_DELAY_MS - 1);
    assertTrue(queue.pollN(10).isEmpty());
    timer.advance(1);
    assertEquals(Collections.singletonList(delayed), queue.pollN(10));
    assertTrue(queue.isEmpty());
  }

  @Test
  public void testDeduplicationPreservesBackoff() {
    BlockInfo block = block(1);
    queue.add(block);
    queue.retry(block);
    queue.add(block);
    assertEquals(1, queue.size());
    assertTrue(queue.pollN(1).isEmpty());
    timer.advance(EcConsolidationQueue.RETRY_DELAY_MS);
    assertEquals(Collections.singletonList(block), queue.pollN(1));
    assertTrue(queue.pollN(1).isEmpty());
  }

  @Test
  public void testRemovalAndClearDiscardDelayedEntries() {
    BlockInfo deleted = block(1);
    BlockInfo ready = block(2);
    queue.retry(deleted);
    queue.add(ready);
    queue.remove(deleted);
    assertFalse(queue.contains(deleted));
    assertEquals(Collections.singletonList(ready), queue.pollN(1));
    timer.advance(EcConsolidationQueue.RETRY_DELAY_MS);
    assertTrue(queue.pollN(10).isEmpty());
    queue.retry(deleted);
    queue.add(ready);
    queue.clear();
    timer.advance(EcConsolidationQueue.RETRY_DELAY_MS);
    assertTrue(queue.pollN(10).isEmpty());
    assertEquals(0, queue.size());
  }

  @Test
  public void testPollingIsBoundedAndOrdered() {
    BlockInfo first = block(1);
    BlockInfo second = block(2);
    BlockInfo third = block(3);
    queue.add(first);
    queue.add(second);
    timer.advance(1);
    queue.add(third);
    assertTrue(queue.pollN(0).isEmpty());
    assertEquals(Arrays.asList(first, second), queue.pollN(2));
    assertEquals(Collections.singletonList(third), queue.pollN(2));
  }
}
