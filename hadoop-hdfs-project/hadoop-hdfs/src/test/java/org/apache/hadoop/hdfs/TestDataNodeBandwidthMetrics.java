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

import static org.apache.hadoop.test.MetricsAsserts.getLongCounter;
import static org.apache.hadoop.test.MetricsAsserts.getMetrics;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.server.datanode.DataNode;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class TestDataNodeBandwidthMetrics {

  private static final int FILE_SIZE = 512;
  private static final int BLOCK_SIZE = 1024 * 1024;

  private MiniDFSCluster cluster;
  private DistributedFileSystem fs;

  @Before
  public void setUp() throws IOException {
    Configuration conf = new HdfsConfiguration();
    conf.setInt(DFSConfigKeys.DFS_REPLICATION_KEY, 1);
    conf.setLong(DFSConfigKeys.DFS_BLOCK_SIZE_KEY, BLOCK_SIZE);
    cluster = new MiniDFSCluster.Builder(conf).numDataNodes(1).build();
    cluster.waitActive();
    fs = cluster.getFileSystem();
  }

  @After
  public void tearDown() {
    if (cluster != null) {
      cluster.shutdown();
    }
  }

  @Test
  public void itCountsBytesWrittenByClient() throws IOException {
    DataNode dn = cluster.getDataNodes().get(0);
    String metricsName = dn.getMetrics().name();

    byte[] data = new byte[FILE_SIZE];
    Path path = new Path("/test-write");
    FSDataOutputStream out = fs.create(path, (short) 1);
    out.write(data);
    out.close();

    long bytesWrittenByClient = getLongCounter("BytesWrittenByClient", getMetrics(metricsName));
    assertEquals(FILE_SIZE, bytesWrittenByClient);
  }

  @Test
  public void itCountsBytesReadByClient() throws IOException {
    byte[] data = new byte[FILE_SIZE];
    Path path = new Path("/test-read");
    FSDataOutputStream out = fs.create(path, (short) 1);
    out.write(data);
    out.close();

    DataNode dn = cluster.getDataNodes().get(0);
    String metricsName = dn.getMetrics().name();
    long before = getLongCounter("BytesReadByClient", getMetrics(metricsName));

    FSDataInputStream in = fs.open(path);
    byte[] buf = new byte[FILE_SIZE];
    in.readFully(buf);
    in.close();

    long delta = getLongCounter("BytesReadByClient", getMetrics(metricsName)) - before;
    assertTrue("Expected BytesReadByClient delta >= " + FILE_SIZE + " but was " + delta,
        delta >= FILE_SIZE);
  }
}
