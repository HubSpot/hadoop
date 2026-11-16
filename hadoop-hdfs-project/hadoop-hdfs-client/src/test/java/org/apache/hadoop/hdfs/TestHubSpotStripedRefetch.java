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

import static org.junit.Assert.assertEquals;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.client.HdfsClientConfigKeys;
import org.apache.hadoop.hdfs.client.impl.DfsClientConf;
import org.junit.Test;

/** HubSpot Edit: the striped re-fetch wait. See HubSpotStripedRefetch. */
public class TestHubSpotStripedRefetch {

  private static Configuration conf(Long stripedWindow) {
    Configuration conf = new Configuration(false);
    // Pin values to this Configuration instead of re-reading files.
    conf.setLong(HubSpotStripedReadHedge.RELOAD_INTERVAL_MILLIS_KEY, 0);
    conf.setInt(HdfsClientConfigKeys.Retry.WINDOW_BASE_KEY, 3000);
    if (stripedWindow != null) {
      conf.setLong(HubSpotStripedRefetch.RETRY_WINDOW_BASE_KEY,
          stripedWindow);
    }
    return conf;
  }

  private static int window(Long stripedWindow) {
    Configuration conf = conf(stripedWindow);
    return HubSpotStripedRefetch.timeWindow(conf, new DfsClientConf(conf));
  }

  @Test
  public void testUnsetUsesReplicatedWindow() {
    assertEquals(3000, window(null));
  }

  @Test
  public void testNegativeUsesReplicatedWindow() {
    assertEquals(3000, window(-1L));
  }

  @Test
  public void testSetReplacesReplicatedWindow() {
    assertEquals(200, window(200L));
  }

  @Test
  public void testZeroMeansNoWait() {
    assertEquals(0, window(0L));
    for (int failures = 0; failures < 3; failures++) {
      assertEquals(0, HubSpotStripedRefetch.waitMs(0, failures, 0.99));
    }
  }

  @Test
  public void testWaitGrowsAsReplicatedReadsDo() {
    // First failure: 0..window.
    assertEquals(0, HubSpotStripedRefetch.waitMs(200, 0, 0.0));
    assertEquals(100, HubSpotStripedRefetch.waitMs(200, 0, 0.5));
    // Later failures: window * failures plus 0..window * (failures + 1).
    assertEquals(200, HubSpotStripedRefetch.waitMs(200, 1, 0.0));
    assertEquals(600, HubSpotStripedRefetch.waitMs(200, 1, 1.0));
    assertEquals(400 + 300, HubSpotStripedRefetch.waitMs(200, 2, 0.5));
  }
}
