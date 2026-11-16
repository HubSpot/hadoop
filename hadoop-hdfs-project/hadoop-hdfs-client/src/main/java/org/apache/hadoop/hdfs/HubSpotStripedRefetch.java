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

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.ThreadLocalRandom;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.client.impl.DfsClientConf;

/**
 * HubSpot Edit: gives striped reads the refetch-and-retry that replicated
 * reads get from {@link DFSInputStream}'s {@code refetchLocations}.
 *
 * <p>Both kinds of read add a DataNode to the stream's dead nodes when reading
 * from it fails, and skip it for every later block the stream reads. That
 * includes a healthy DataNode that only lacks a block the balancer or the
 * locality healer moved off it. When every location of a block is dead, a
 * replicated read waits, clears the dead nodes, re-fetches block locations
 * and retries, up to {@code dfs.client.max.block.acquire.failures} times. A
 * striped read had no such step. Once a stripe had more cells on dead nodes
 * than parity covers, it failed with "N missing blocks", and only the
 * periodic block location refresh cleared the dead nodes.
 *
 * <p>Now {@link StripeReader} throws {@link MissingBlocksException} for that
 * case, and {@link DFSStripedInputStream} handles it as a replicated read
 * does: it waits the same way, clears the dead nodes, re-fetches locations
 * and reads the stripe again, with the same limit. Once a read has re-fetched
 * that many times, the original exception is thrown.
 *
 * <p>The wait can be set apart from replicated reads with
 * {@value #RETRY_WINDOW_BASE_KEY}. A replicated read waits because every
 * replica failed, which may mean the cluster is overloaded. A striped read
 * mostly gets here because the balancer moved blocks, where waiting does not
 * help and only adds to the read's latency.
 */
@InterfaceAudience.Private
final class HubSpotStripedRefetch {

  /**
   * A stripe has more missing cells than parity units. The message is the
   * same as before this class existed.
   */
  static final class MissingBlocksException extends IOException {
    private static final long serialVersionUID = 1L;
    private final int missingChunksNum;

    MissingBlocksException(int missingChunksNum, String message) {
      super(message);
      this.missingChunksNum = missingChunksNum;
    }

    int getMissingChunksNum() {
      return missingChunksNum;
    }
  }

  /**
   * Base wait in milliseconds before a striped read re-fetches block
   * locations, in place of {@code dfs.client.retry.window.base} for striped
   * reads only. The wait grows the same way with each re-fetch. Unset or
   * negative uses {@code dfs.client.retry.window.base}, so deploying a build
   * does not change how long reads wait. 0 re-fetches without waiting.
   *
   * <p>Read live, as the striped read hedge settings are, so it can be changed
   * without restarting the process. See
   * {@link HubSpotStripedReadHedge#liveLong}.
   */
  static final String RETRY_WINDOW_BASE_KEY =
      "dfs.client.hubspot.striped.retry.window.base";
  static final long RETRY_WINDOW_BASE_DEFAULT = -1;

  private HubSpotStripedRefetch() {
  }

  /**
   * The base wait for striped reads: {@value #RETRY_WINDOW_BASE_KEY} if set
   * to 0 or more, else {@code dfs.client.retry.window.base}.
   */
  static int timeWindow(Configuration conf, DfsClientConf dfsConf) {
    final long window = HubSpotStripedReadHedge.liveLong(
        conf, RETRY_WINDOW_BASE_KEY, RETRY_WINDOW_BASE_DEFAULT);
    if (window < 0) {
      return dfsConf.getTimeWindow();
    }
    return (int) Math.min(window, Integer.MAX_VALUE);
  }

  /**
   * Same wait as DFSInputStream#refetchLocations: a random 0..window on the
   * first failure, and a growing grace period plus window after that.
   *
   * @param random a value in [0, 1)
   */
  static long waitMs(int timeWindow, int failures, double random) {
    return Math.round((double) timeWindow * failures
        + (double) timeWindow * (failures + 1) * random);
  }

  /**
   * Waits before the next re-fetch as a replicated read would, using the
   * striped base wait from {@link #timeWindow}, or rethrows {@code e} once
   * {@code failures} has reached the limit.
   *
   * @param failures re-fetches already done in this read
   */
  static void waitBeforeRefetch(MissingBlocksException e, int failures,
      Configuration conf, DfsClientConf dfsConf, String src)
      throws IOException {
    if (failures >= dfsConf.getMaxBlockAcquireFailures()) {
      throw e;
    }
    final long waitMs = waitMs(timeWindow(conf, dfsConf), failures,
        ThreadLocalRandom.current().nextDouble());
    DFSClient.LOG.warn("Striped read of {}: {} missing blocks in a stripe, got"
        + " # {} such failure. Will clear dead nodes and get new block"
        + " locations from namenode after {} msec.", src,
        e.getMissingChunksNum(), failures + 1, waitMs);
    if (waitMs <= 0) {
      return;
    }
    try {
      Thread.sleep(waitMs);
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      throw new InterruptedIOException(
          "Interrupted while waiting to re-fetch block locations for " + src);
    }
  }
}
