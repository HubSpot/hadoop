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
package org.apache.hadoop.hdfs.util;

import org.apache.hadoop.hdfs.BlockReader;
import org.apache.hadoop.hdfs.DFSChecksumReadMetrics;
import org.apache.hadoop.hdfs.DFSClient;
import org.apache.hadoop.hdfs.ReadStatistics;
import org.apache.hadoop.log.LogThrottlingHelper;
import org.apache.hadoop.log.LogThrottlingHelper.LogAction;
import org.apache.hadoop.util.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;

public class IOUtilsClient {
  private static final Logger LOG = LoggerFactory.getLogger(IOUtilsClient.class);

  /**
   * Minimum period between the "dynamic" checksum/short-circuit diagnostic
   * log lines emitted by {@link #updateChecksumReadStatistics}. Reads can
   * happen at very high frequency, so this log is sampled rather than
   * emitted on every read; the throttling helper still tracks how many
   * reads and bytes were folded into each suppressed interval.
   */
  private static final long CHECKSUM_LOG_PERIOD_MS = 60_000;

  private static final LogThrottlingHelper CHECKSUM_LOG_THROTTLER =
      new LogThrottlingHelper(CHECKSUM_LOG_PERIOD_MS);

  /**
   * Close the Closeable objects and <b>ignore</b> any {@link IOException} or
   * null pointers. Must only be used for cleanup in exception handlers.
   *
   * @param log the log to record problems to at debug level. Can be null.
   * @param closeables the objects to close
   */
  public static void cleanupWithLogger(Logger log,
                                       Closeable... closeables) {
    for (Closeable c : closeables) {
      if (c != null) {
        try {
          c.close();
        } catch(Throwable e) {
          if (log != null && log.isDebugEnabled()) {
            log.debug("Exception in closing " + c, e);
          }
        }
      }
    }
  }

  public static void updateReadStatistics(ReadStatistics readStatistics,
                                      int nRead, BlockReader blockReader) {
    updateReadStatistics(readStatistics, nRead, blockReader.isShortCircuit(),
        blockReader.getNetworkDistance());
  }

  public static void updateReadStatistics(ReadStatistics readStatistics,
      int nRead, boolean isShortCircuit, int networkDistance) {
    if (nRead <= 0) {
      return;
    }

    if (isShortCircuit) {
      readStatistics.addShortCircuitBytes(nRead);
    } else if (networkDistance == 0) {
      readStatistics.addLocalBytes(nRead);
    } else {
      readStatistics.addRemoteBytes(nRead);
    }
  }

  /**
   * Update checksum-related read statistics and metrics for a completed
   * read, and emit a throttled, DEBUG-level diagnostic log line correlating:
   * <ul>
   *   <li>whether the owning stream was itself opened to verify or skip
   *   checksums (e.g. via {@code FileSystem#setVerifyChecksum(false)}, as
   *   HBase does when reading HFiles that carry their own checksums) --
   *   the caller's <i>intent</i>;</li>
   *   <li>whether the block reader that actually served this read verified
   *   or skipped checksums -- the <i>effective</i> behavior, which can
   *   diverge from intent (see {@link BlockReader#isVerifyChecksum()});
   *   and</li>
   *   <li>whether the read was short-circuited.</li>
   * </ul>
   * This gives the data needed to confirm HDFS is honoring a caller's
   * no-checksum configuration, and to quantify short-circuit / checksum
   * skipping efficiency via {@link DFSChecksumReadMetrics}.
   *
   * @param readStatistics per-stream statistics to update.
   * @param src the file path being read, used only for logging.
   * @param nRead number of bytes read.
   * @param streamVerifyChecksum whether the owning stream was opened with
   *                             checksum verification intended (true for a
   *                             normal ChecksumFileSystem-style stream,
   *                             false for a no-checksum stream).
   * @param blockReader the block reader that served this read.
   */
  public static void updateChecksumReadStatistics(
      ReadStatistics readStatistics, String src, int nRead,
      boolean streamVerifyChecksum, BlockReader blockReader) {
    if (nRead <= 0) {
      return;
    }

    boolean isShortCircuit = blockReader.isShortCircuit();
    boolean effectiveVerifyChecksum = blockReader.isVerifyChecksum();

    if (effectiveVerifyChecksum) {
      readStatistics.addChecksumVerifiedBytes(nRead);
    } else {
      readStatistics.addChecksumSkippedBytes(nRead);
    }
    DFSClient.getChecksumReadMetrics()
        .addRead(isShortCircuit, effectiveVerifyChecksum, nRead);

    if (LOG.isDebugEnabled()) {
      LogAction logAction =
          CHECKSUM_LOG_THROTTLER.record(Time.monotonicNow(), nRead);
      if (logAction.shouldLog()) {
        LOG.debug("Checksum/short-circuit read stats for {}: " +
                "streamOpenedAs={}, checksumVerified={}, shortCircuit={}, " +
                "bytesThisRead={}, readsSinceLastLog={}, " +
                "bytesSinceLastLog={}{}",
            src,
            streamVerifyChecksum ? "checksumFS" : "noChecksumFS",
            effectiveVerifyChecksum, isShortCircuit, nRead,
            logAction.getCount(), Math.round(logAction.getStats(0).getSum()),
            streamVerifyChecksum != effectiveVerifyChecksum
                ? " (MISMATCH: stream was opened as " +
                    (streamVerifyChecksum ? "checksumFS" : "noChecksumFS") +
                    " but checksum verification was " +
                    (effectiveVerifyChecksum ? "performed" : "skipped") + ")"
                : "");
      }
    }
  }
}
