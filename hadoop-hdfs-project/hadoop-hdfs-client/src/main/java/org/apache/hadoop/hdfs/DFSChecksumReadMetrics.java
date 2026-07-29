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

import org.apache.hadoop.classification.InterfaceAudience;

import java.util.concurrent.atomic.LongAdder;

/**
 * Process-wide client-side metrics correlating short-circuit reads with
 * checksum verification. This class has a number of metrics variables that
 * are publicly accessible, we can grab them from client side, like HBase,
 * to verify that a configured no-checksum read path is actually being
 * honored on the read path, and to quantify how much read traffic is
 * getting the full benefit of both short-circuit reads and skipped
 * checksums versus how much is missing one or both optimizations.
 */
@InterfaceAudience.Private
public class DFSChecksumReadMetrics {
  public final LongAdder shortCircuitChecksumSkippedBytes = new LongAdder();
  public final LongAdder shortCircuitChecksumSkippedOps = new LongAdder();
  public final LongAdder shortCircuitChecksumVerifiedBytes = new LongAdder();
  public final LongAdder shortCircuitChecksumVerifiedOps = new LongAdder();
  public final LongAdder remoteChecksumSkippedBytes = new LongAdder();
  public final LongAdder remoteChecksumSkippedOps = new LongAdder();
  public final LongAdder remoteChecksumVerifiedBytes = new LongAdder();
  public final LongAdder remoteChecksumVerifiedOps = new LongAdder();

  /**
   * Record a completed read of {@code bytes} bytes, classified by whether
   * it was a short-circuit read and whether checksum verification was
   * performed for it.
   */
  public void addRead(boolean isShortCircuit, boolean verifyChecksum,
      long bytes) {
    if (isShortCircuit) {
      if (verifyChecksum) {
        shortCircuitChecksumVerifiedBytes.add(bytes);
        shortCircuitChecksumVerifiedOps.increment();
      } else {
        shortCircuitChecksumSkippedBytes.add(bytes);
        shortCircuitChecksumSkippedOps.increment();
      }
    } else {
      if (verifyChecksum) {
        remoteChecksumVerifiedBytes.add(bytes);
        remoteChecksumVerifiedOps.increment();
      } else {
        remoteChecksumSkippedBytes.add(bytes);
        remoteChecksumSkippedOps.increment();
      }
    }
  }

  public long getShortCircuitChecksumSkippedBytes() {
    return shortCircuitChecksumSkippedBytes.longValue();
  }

  public long getShortCircuitChecksumSkippedOps() {
    return shortCircuitChecksumSkippedOps.longValue();
  }

  public long getShortCircuitChecksumVerifiedBytes() {
    return shortCircuitChecksumVerifiedBytes.longValue();
  }

  public long getShortCircuitChecksumVerifiedOps() {
    return shortCircuitChecksumVerifiedOps.longValue();
  }

  public long getRemoteChecksumSkippedBytes() {
    return remoteChecksumSkippedBytes.longValue();
  }

  public long getRemoteChecksumSkippedOps() {
    return remoteChecksumSkippedOps.longValue();
  }

  public long getRemoteChecksumVerifiedBytes() {
    return remoteChecksumVerifiedBytes.longValue();
  }

  public long getRemoteChecksumVerifiedOps() {
    return remoteChecksumVerifiedOps.longValue();
  }

  /**
   * @return Total bytes read via short-circuit, regardless of whether
   * checksums were verified.
   */
  public long getTotalShortCircuitBytes() {
    return getShortCircuitChecksumSkippedBytes()
        + getShortCircuitChecksumVerifiedBytes();
  }

  /**
   * @return Total bytes read for which checksum verification was skipped,
   * whether short-circuit or remote.
   */
  public long getTotalChecksumSkippedBytes() {
    return getShortCircuitChecksumSkippedBytes()
        + getRemoteChecksumSkippedBytes();
  }

  /**
   * @return Total bytes read for which checksums were verified, whether
   * short-circuit or remote.
   */
  public long getTotalChecksumVerifiedBytes() {
    return getShortCircuitChecksumVerifiedBytes()
        + getRemoteChecksumVerifiedBytes();
  }
}
