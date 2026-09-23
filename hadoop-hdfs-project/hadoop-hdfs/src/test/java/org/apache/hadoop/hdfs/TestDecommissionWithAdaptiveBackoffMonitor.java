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

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.server.blockmanagement
    .DatanodeAdminMonitorInterface;
import org.apache.hadoop.hdfs.server.blockmanagement
    .DatanodeAdminAdaptiveBackoffMonitor;
import org.junit.Test;

import java.io.IOException;

/**
 * HubSpot: runs the full decommission test suite against the adaptive
 * {@link DatanodeAdminAdaptiveBackoffMonitor} with adaptive pacing enabled, to
 * confirm decommission still completes end-to-end. Mirrors
 * {@link TestDecommissionWithBackoffMonitor}.
 */
public class TestDecommissionWithAdaptiveBackoffMonitor extends TestDecommission {

  @Override
  public void setup() throws IOException {
    super.setup();
    Configuration conf = getConf();
    conf.setClass(DFSConfigKeys.DFS_NAMENODE_DECOMMISSION_MONITOR_CLASS,
        DatanodeAdminAdaptiveBackoffMonitor.class,
        DatanodeAdminMonitorInterface.class);
    conf.setBoolean(
        DFSConfigKeys.DFS_NAMENODE_DECOMMISSION_BACKOFF_MONITOR_ADAPTIVE_ENABLED,
        true);
  }

  @Override
  @Test
  public void testBlocksPerInterval() {
    // Not valid for the backoff monitor family; overridden to a no-op, as in
    // TestDecommissionWithBackoffMonitor.
  }
}
