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

import static org.apache.hadoop.fs.CommonConfigurationKeysPublic.IPC_CLIENT_CONNECT_MAX_RETRIES_KEY;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Random;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.CommonConfigurationKeys;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.BlockReader;
import org.apache.hadoop.hdfs.client.impl.BlockReaderFactory;
import org.apache.hadoop.hdfs.ClientContext;
import org.apache.hadoop.hdfs.DFSClient;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.DFSTestUtil;
import org.apache.hadoop.hdfs.DFSUtilClient;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.hdfs.RemotePeerFactory;
import org.apache.hadoop.hdfs.client.HdfsClientConfigKeys;
import org.apache.hadoop.hdfs.client.impl.DfsClientConf;
import org.apache.hadoop.hdfs.net.Peer;
import org.apache.hadoop.hdfs.protocol.DatanodeID;
import org.apache.hadoop.hdfs.protocol.DatanodeInfo;
import org.apache.hadoop.hdfs.protocol.ExtendedBlock;
import org.apache.hadoop.hdfs.protocol.HdfsConstants;
import org.apache.hadoop.hdfs.protocol.LocatedBlock;
import org.apache.hadoop.hdfs.security.token.block.BlockTokenIdentifier;
import org.apache.hadoop.hdfs.security.token.block.BlockTokenSecretManager;
import org.apache.hadoop.hdfs.security.token.block.InvalidBlockTokenException;
import org.apache.hadoop.hdfs.security.token.block.SecurityTestUtil;
import org.apache.hadoop.hdfs.server.balancer.TestBalancer;
import org.apache.hadoop.hdfs.server.datanode.CachingStrategy;
import org.apache.hadoop.hdfs.server.namenode.NameNode;
import org.apache.hadoop.hdfs.server.protocol.NamenodeProtocols;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.net.ServerSocketUtil;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.test.GenericTestUtils;
import org.apache.log4j.Level;
import org.junit.Assert;
import org.junit.Test;

public class TestBlockTokenWithDFS {

  protected static int BLOCK_SIZE = 1024;
  protected static int FILE_SIZE = 2 * BLOCK_SIZE;
  private static final String FILE_TO_READ = "/fileToRead.dat";
  private static final String FILE_TO_WRITE = "/fileToWrite.dat";
  private static final String FILE_TO_APPEND = "/fileToAppend.dat";

  {
    GenericTestUtils.setLogLevel(DFSClient.LOG, Level.ALL);
  }

  public static byte[] generateBytes(int fileSize){
    Random r = new Random();
    byte[] rawData = new byte[fileSize];
    r.nextBytes(rawData);
    return rawData;
  }

  private void createFile(FileSystem fs, Path filename, byte[] expected) throws IOException {
    FSDataOutputStream out = fs.create(filename);
    out.write(expected);
    out.close();
  }

  // read a file using blockSeekTo()
  private boolean checkFile1(FSDataInputStream in, byte[] expected) {
    byte[] toRead = new byte[expected.length];
    int totalRead = 0;
    int nRead = 0;
    try {
      while ((nRead = in.read(toRead, totalRead, toRead.length - totalRead)) > 0) {
        totalRead += nRead;
      }
    } catch (IOException e) {
      return false;
    }
    assertEquals("Cannot read file.", toRead.length, totalRead);
    return checkFile(toRead, expected);
  }

  // read a file using fetchBlockByteRange()
  private boolean checkFile2(FSDataInputStream in, byte[] expected) {
    byte[] toRead = new byte[expected.length];
    try {
      assertEquals("Cannot read file", toRead.length, in.read(0, toRead, 0,
          toRead.length));
    } catch (IOException e) {
      return false;
    }
    return checkFile(toRead, expected);
  }

  private boolean checkFile(byte[] fileToCheck, byte[] expected) {
    if (fileToCheck.length != expected.length) {
      return false;
    }
    for (int i = 0; i < fileToCheck.length; i++) {
      if (fileToCheck[i] != expected[i]) {
        return false;
      }
    }
    return true;
  }

  // creates a file and returns a descriptor for writing to it
  private static FSDataOutputStream writeFile(FileSystem fileSys, Path name,
      short repl, long blockSize) throws IOException {
    FSDataOutputStream stm = fileSys.create(name, true, fileSys.getConf()
        .getInt(CommonConfigurationKeys.IO_FILE_BUFFER_SIZE_KEY, 4096), repl, blockSize);
    return stm;
  }

  // try reading a block using a BlockReader directly
  protected void tryRead(final Configuration conf, LocatedBlock lblock,
      boolean shouldSucceed) {
    InetSocketAddress targetAddr = null;
    IOException ioe = null;
    BlockReader blockReader = null;
    ExtendedBlock block = lblock.getBlock();
    try {
      DatanodeInfo[] nodes = lblock.getLocations();
      targetAddr = NetUtils.createSocketAddr(nodes[0].getXferAddr());

      blockReader = new BlockReaderFactory(new DfsClientConf(conf)).
          setFileName(BlockReaderFactory.getFileName(targetAddr,
                        "test-blockpoolid", block.getBlockId())).
          setBlock(block).
          setBlockToken(lblock.getBlockToken()).
          setInetSocketAddress(targetAddr).
          setStartOffset(0).
          setLength(0).
          setVerifyChecksum(true).
          setClientName("TestBlockTokenWithDFS").
          setDatanodeInfo(nodes[0]).
          setCachingStrategy(CachingStrategy.newDefaultStrategy()).
          setClientCacheContext(ClientContext.getFromConf(conf)).
          setConfiguration(conf).
          setRemotePeerFactory(new RemotePeerFactory() {
            @Override
            public Peer newConnectedPeer(InetSocketAddress addr,
                Token<BlockTokenIdentifier> blockToken, DatanodeID datanodeId)
                throws IOException {
              Peer peer = null;
              Socket sock = NetUtils.getDefaultSocketFactory(conf).createSocket();
              try {
                sock.connect(addr, HdfsConstants.READ_TIMEOUT);
                sock.setSoTimeout(HdfsConstants.READ_TIMEOUT);
                peer = DFSUtilClient.peerFromSocket(sock);
              } finally {
                if (peer == null) {
                  IOUtils.closeSocket(sock);
                }
              }
              return peer;
            }
          }).
          build();
    } catch (IOException ex) {
      ioe = ex;
    } finally {
      if (blockReader != null) {
        try {
          blockReader.close();
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }
    }
    if (shouldSucceed) {
      Assert.assertNotNull("OP_READ_BLOCK: access token is invalid, "
            + "when it is expected to be valid", blockReader);
    } else {
      Assert.assertNotNull("OP_READ_BLOCK: access token is valid, "
          + "when it is expected to be invalid", ioe);
      Assert.assertTrue(
          "OP_READ_BLOCK failed due to reasons other than access token: ",
          ioe instanceof InvalidBlockTokenException);
    }
  }

  // get a conf for testing
  protected Configuration getConf(int numDataNodes) {
    Configuration conf = new Configuration();
    conf.setBoolean(DFSConfigKeys.DFS_BLOCK_ACCESS_TOKEN_ENABLE_KEY, true);
    conf.setLong(DFSConfigKeys.DFS_BLOCK_SIZE_KEY, BLOCK_SIZE);
    conf.setInt("io.bytes.per.checksum", BLOCK_SIZE);
    conf.setInt(DFSConfigKeys.DFS_HEARTBEAT_INTERVAL_KEY, 1);
    conf.setInt(DFSConfigKeys.DFS_REPLICATION_KEY, numDataNodes);
    conf.setInt(IPC_CLIENT_CONNECT_MAX_RETRIES_KEY, 0);
    // Set short retry timeouts so this test runs faster
    conf.setInt(HdfsClientConfigKeys.Retry.WINDOW_BASE_KEY, 10);
    return conf;
  }

  /**
   * testing that APPEND operation can handle token expiration when
   * re-establishing pipeline is needed
   */
  @Test
  public void testAppend() throws Exception {
    MiniDFSCluster cluster = null;
    int numDataNodes = 2;
    Configuration conf = getConf(numDataNodes);

    try {
      cluster = new MiniDFSCluster.Builder(conf).numDataNodes(numDataNodes).build();
      cluster.waitActive();
      assertEquals(numDataNodes, cluster.getDataNodes().size());

      final NameNode nn = cluster.getNameNode();
      final BlockManager bm = nn.getNamesystem().getBlockManager();
      final BlockTokenSecretManager sm = bm.getBlockTokenSecretManager();

      // set a short token lifetime (1 second)
      SecurityTestUtil.setBlockTokenLifetime(sm, 1000L);
      Path fileToAppend = new Path(FILE_TO_APPEND);
      FileSystem fs = cluster.getFileSystem();
      byte[] expected = generateBytes(FILE_SIZE);
      // write a one-byte file
      FSDataOutputStream stm = writeFile(fs, fileToAppend,
          (short) numDataNodes, BLOCK_SIZE);
      stm.write(expected, 0, 1);
      stm.close();
      // open the file again for append
      stm = fs.append(fileToAppend);
      int mid = expected.length - 1;
      stm.write(expected, 1, mid - 1);
      stm.hflush();

      /*
       * wait till token used in stm expires
       */
      Token<BlockTokenIdentifier> token = DFSTestUtil.getBlockToken(stm);
      while (!SecurityTestUtil.isBlockTokenExpired(token)) {
        try {
          Thread.sleep(10);
        } catch (InterruptedException ignored) {
        }
      }

      // remove a datanode to force re-establishing pipeline
      cluster.stopDataNode(0);
      // append the rest of the file
      stm.write(expected, mid, expected.length - mid);
      stm.close();
      // check if append is successful
      FSDataInputStream in5 = fs.open(fileToAppend);
      assertTrue(checkFile1(in5, expected));
    } finally {
      if (cluster != null) {
        cluster.shutdown();
      }
    }
  }

  /**
   * testing that WRITE operation can handle token expiration when
   * re-establishing pipeline is needed
   */
  @Test
  public void testWrite() throws Exception {
    MiniDFSCluster cluster = null;
    int numDataNodes = 2;
    Configuration conf = getConf(numDataNodes);

    try {
      cluster = new MiniDFSCluster.Builder(conf).numDataNodes(numDataNodes).build();
      cluster.waitActive();
      assertEquals(numDataNodes, cluster.getDataNodes().size());

      final NameNode nn = cluster.getNameNode();
      final BlockManager bm = nn.getNamesystem().getBlockManager();
      final BlockTokenSecretManager sm = bm.getBlockTokenSecretManager();

      // set a short token lifetime (1 second)
      SecurityTestUtil.setBlockTokenLifetime(sm, 1000L);
      Path fileToWrite = new Path(FILE_TO_WRITE);
      FileSystem fs = cluster.getFileSystem();

      byte[] expected = generateBytes(FILE_SIZE);
      FSDataOutputStream stm = writeFile(fs, fileToWrite, (short) numDataNodes,
          BLOCK_SIZE);
      // write a partial block
      int mid = expected.length - 1;
      stm.write(expected, 0, mid);
      stm.hflush();

      /*
       * wait till token used in stm expires
       */
      Token<BlockTokenIdentifier> token = DFSTestUtil.getBlockToken(stm);
      while (!SecurityTestUtil.isBlockTokenExpired(token)) {
        try {
          Thread.sleep(10);
        } catch (InterruptedException ignored) {
        }
      }

      // remove a datanode to force re-establishing pipeline
      cluster.stopDataNode(0);
      // write the rest of the file
      stm.write(expected, mid, expected.length - mid);
      stm.close();
      // check if write is successful
      FSDataInputStream in4 = fs.open(fileToWrite);
      assertTrue(checkFile1(in4, expected));
    } finally {
      if (cluster != null) {
        cluster.shutdown();
      }
    }
  }

  @Test
  public void testRead() throws Exception {
    MiniDFSCluster cluster = null;
    int numDataNodes = 2;
    Configuration conf = getConf(numDataNodes);

    try {
      // prefer non-ephemeral port to avoid port collision on restartNameNode
      cluster = new MiniDFSCluster.Builder(conf)
          .nameNodePort(ServerSocketUtil.getPort(18020, 100))
          .nameNodeHttpPort(ServerSocketUtil.getPort(19870, 100))
          .numDataNodes(numDataNodes)
          .build();
      cluster.waitActive();
      assertEquals(numDataNodes, cluster.getDataNodes().size());
      doTestRead(conf, cluster, false);
    } finally {
      if (cluster != null) {
        cluster.shutdown();
      }
    }
  }

  /**
   * Tests migrating without downtime into enabling block access tokens.
   */
  @Test
  public void testReadWithMigration() throws Exception {
    MiniDFSCluster cluster = null;
    int numDataNodes = 2;
    Configuration conf = getConf(numDataNodes);
    //
    // At first, migration mode is enabled and access tokens disabled.
    // Namenodes will start up without access tokens, but DN's will be prepared to ignore
    // them if they receive them.
    //
    conf.setBoolean("dfs.block.access.token.migration.enabled", true);
    conf.setBoolean(DFSConfigKeys.DFS_BLOCK_ACCESS_TOKEN_ENABLE_KEY, false);

    try {
      // prefer non-ephemeral port to avoid port collision on restartNameNode
      cluster = new MiniDFSCluster.Builder(conf)
          .nameNodePort(ServerSocketUtil.getPort(18020, 100))
          .nameNodeHttpPort(ServerSocketUtil.getPort(19870, 100))
          .numDataNodes(numDataNodes)
          .build();
      cluster.waitActive();
      assertEquals(numDataNodes, cluster.getDataNodes().size());

      //
      // Now we enable access tokens on the namenodes. Previously, DataNodes would
      // fail with "Inconsistent configuration of block access tokens". Since
      // we have migration mode enabled, they are able to proceed with re-registry.
      //
      for (int i = 0; i < cluster.getNumNameNodes(); i++) {
        cluster.getConfiguration(i).setBoolean(DFSConfigKeys.DFS_BLOCK_ACCESS_TOKEN_ENABLE_KEY, true);
        cluster.restartNameNode(i);
      }
      cluster.triggerHeartbeats();

      //
      // Do a test read, which confirms that migration mode is working
      //

      doTestRead(conf, cluster, false);

      //
      // Now that access tokens are enabled on the NameNode without downtime, we can
      // turn off migration mode and rolling restart datanodes. When they start up,
      // they will register and get the block access tokens and use them going forward.
      //

      conf.setBoolean("dfs.block.access.token.migration.enabled", false);
      for (int idx = 0; idx < cluster.getDataNodes().size(); idx++) {
        cluster.stopDataNode(idx);
      }
      cluster.startDataNodes(conf, numDataNodes, true, null, null);
      cluster.waitActive();

      //
      // Do another test read which verifies that everything is working
      // with fully enabled block access tokens.
      //

      doTestRead(conf, cluster, false);
    } finally {
      if (cluster != null) {
        cluster.shutdown();
      }
    }
  }

  protected void doTestRead(Configuration conf, MiniDFSCluster cluster,
      boolean isStriped) throws Exception {
    // This test originally assumes that certain reads will fail and certain
    // block access tokens will be refreshed automatically. When migration mode
    // is enabled, the datanode just ignores any access tokens its sent -- It never
    // throws an InvalidBlockTokenException and thus certain reads below don't fail
    // and also the client will not automatically request updated access tokens.
    // So we use this migrationMode boolean to gate/invert some of the assertions below.
    boolean migrationMode = conf.getBoolean("dfs.block.access.token.migration.enabled", false);
    final int numDataNodes = cluster.getDataNodes().size();
    final NameNode nn = cluster.getNameNode();
    final NamenodeProtocols nnProto = nn.getRpcServer();
    final BlockManager bm = nn.getNamesystem().getBlockManager();
    final BlockTokenSecretManager sm = bm.getBlockTokenSecretManager();

    // set a short token lifetime (1 second) initially
    SecurityTestUtil.setBlockTokenLifetime(sm, 1000L);

    Path fileToRead = new Path(FILE_TO_READ);
    FileSystem fs = cluster.getFileSystem();
    byte[] expected = generateBytes(FILE_SIZE);
    createFile(fs, fileToRead, expected);

      /*
       * setup for testing expiration handling of cached tokens
       */

    // read using blockSeekTo(). Acquired tokens are cached in in1
    FSDataInputStream in1 = fs.open(fileToRead);
    assertTrue(checkFile1(in1,expected));
    // read using blockSeekTo(). Acquired tokens are cached in in2
    FSDataInputStream in2 = fs.open(fileToRead);
    assertTrue(checkFile1(in2,expected));
    // read using fetchBlockByteRange(). Acquired tokens are cached in in3
    FSDataInputStream in3 = fs.open(fileToRead);
    assertTrue(checkFile2(in3,expected));

      /*
       * testing READ interface on DN using a BlockReader
       */
    DFSClient client = null;
    try {
      client = new DFSClient(new InetSocketAddress("localhost",
          cluster.getNameNodePort()), conf);
    } finally {
      if (client != null) client.close();
    }
    List<LocatedBlock> locatedBlocks = nnProto.getBlockLocations(
        FILE_TO_READ, 0, FILE_SIZE).getLocatedBlocks();
    LocatedBlock lblock = locatedBlocks.get(0); // first block
    // verify token is not expired
    assertFalse(isBlockTokenExpired(lblock));
    // read with valid token, should succeed
    tryRead(conf, lblock, true);

      /*
       * wait till myToken and all cached tokens in in1, in2 and in3 expire
       */

    while (!isBlockTokenExpired(lblock)) {
      try {
        Thread.sleep(10);
      } catch (InterruptedException ignored) {
      }
    }

      /*
       * continue testing READ interface on DN using a BlockReader
       */

    // verify token is expired
    assertTrue(isBlockTokenExpired(lblock));
    // read should fail
    tryRead(conf, lblock, migrationMode);
    // use a valid new token
    bm.setBlockToken(lblock, BlockTokenIdentifier.AccessMode.READ);
    // read should succeed
    tryRead(conf, lblock, true);
    // use a token with wrong blockID
    long rightId = lblock.getBlock().getBlockId();
    long wrongId = rightId + 1;
    lblock.getBlock().setBlockId(wrongId);
    bm.setBlockToken(lblock, BlockTokenIdentifier.AccessMode.READ);
    lblock.getBlock().setBlockId(rightId);
    // read should fail
    tryRead(conf, lblock, migrationMode);
    // use a token with wrong access modes
    bm.setBlockToken(lblock, BlockTokenIdentifier.AccessMode.WRITE);
    // read should fail
    tryRead(conf, lblock, migrationMode);

    // set a long token lifetime for future tokens
    SecurityTestUtil.setBlockTokenLifetime(sm, 600 * 1000L);

      /*
       * testing that when cached tokens are expired, DFSClient will re-fetch
       * tokens transparently for READ.
       */

    // confirm all tokens cached in in1 are expired by now
    List<LocatedBlock> lblocks = DFSTestUtil.getAllBlocks(in1);
    for (LocatedBlock blk : lblocks) {
      assertTrue(isBlockTokenExpired(blk));
    }
    // verify blockSeekTo() is able to re-fetch token transparently
    in1.seek(0);
    assertTrue(checkFile1(in1, expected));

    // confirm all tokens cached in in2 are expired by now
    List<LocatedBlock> lblocks2 = DFSTestUtil.getAllBlocks(in2);
    for (LocatedBlock blk : lblocks2) {
      assertTrue(isBlockTokenExpired(blk));
    }
    // verify blockSeekTo() is able to re-fetch token transparently (testing
    // via another interface method)
    if (isStriped) {
      // striped block doesn't support seekToNewSource
      in2.seek(0);
    } else {
      assertTrue(in2.seekToNewSource(0));
    }
    assertTrue(checkFile1(in2,expected));

    // confirm all tokens cached in in3 are expired by now
    List<LocatedBlock> lblocks3 = DFSTestUtil.getAllBlocks(in3);
    for (LocatedBlock blk : lblocks3) {
      assertTrue(isBlockTokenExpired(blk));
    }
    // verify fetchBlockByteRange() is able to re-fetch token transparently
    assertTrue(checkFile2(in3,expected));

      /*
       * testing that after datanodes are restarted on the same ports, cached
       * tokens should still work and there is no need to fetch new tokens from
       * namenode. This test should run while namenode is down (to make sure no
       * new tokens can be fetched from namenode).
       */

    // restart datanodes on the same ports that they currently use
    assertTrue(cluster.restartDataNodes(true));
    cluster.waitActive();
    assertEquals(numDataNodes, cluster.getDataNodes().size());
    cluster.shutdownNameNode(0);

    // If migration mode is enabled, the block tokens will not have been refreshed
    // by in1.seek, because the DataNode will not have rejected them. Expect
    // the isBlockTokenExpired value to be true if migrationMode is enabled,
    // otherwise false meaning the client was able transparently refresh the token
    // when it was rejected by DN.
    lblocks = DFSTestUtil.getAllBlocks(in1);
    for (LocatedBlock blk : lblocks) {
      assertEquals(migrationMode, isBlockTokenExpired(blk));
    }

    // verify blockSeekTo() still works (forced to use cached tokens)
    in1.seek(0);
    assertTrue(checkFile1(in1,expected));

    // If migration mode is enabled, the block tokens will not have been refreshed
    // by in2.seekToNewSource, because the DataNode will not have rejected them. Expect
    // the isBlockTokenExpired value to be true if migrationMode is enabled,
    // otherwise false meaning the client was able transparently refresh the token
    // when it was rejected by DN.
    lblocks2 = DFSTestUtil.getAllBlocks(in2);
    for (LocatedBlock blk : lblocks2) {
      assertEquals(migrationMode, isBlockTokenExpired(blk));
    }

    // verify blockSeekTo() still works (forced to use cached tokens)
    if (isStriped) {
      in2.seek(0);
    } else {
      in2.seekToNewSource(0);
    }
    assertTrue(checkFile1(in2,expected));

    // If migration mode is enabled, the block tokens for in3 will not have been refreshed
    // by fetchBlockByteRange, because the DataNode will not have rejected them. Expect
    // the isBlockTokenExpired value to be true if migrationMode is enabled,
    // otherwise false meaning the client was able transparently refresh the token
    // when it was rejected by DN.
    lblocks3 = DFSTestUtil.getAllBlocks(in3);
    for (LocatedBlock blk : lblocks3) {
      assertEquals(migrationMode, isBlockTokenExpired(blk));
    }

    // verify fetchBlockByteRange() still works (forced to use cached tokens)
    assertTrue(checkFile2(in3,expected));

      /*
       * testing that when namenode is restarted, cached tokens should still
       * work and there is no need to fetch new tokens from namenode. Like the
       * previous test, this test should also run while namenode is down. The
       * setup for this test depends on the previous test.
       */

    // restart the namenode and then shut it down for test
    cluster.restartNameNode(0);
    cluster.shutdownNameNode(0);

    // verify blockSeekTo() still works (forced to use cached tokens)
    in1.seek(0);
    assertTrue(checkFile1(in1,expected));
    // verify again blockSeekTo() still works (forced to use cached tokens)
    if (isStriped) {
      in2.seek(0);
    } else {
      in2.seekToNewSource(0);
    }
    assertTrue(checkFile1(in2,expected));

    // verify fetchBlockByteRange() still works (forced to use cached tokens)
    assertTrue(checkFile2(in3,expected));

      /*
       * testing that after both namenode and datanodes got restarted (namenode
       * first, followed by datanodes), DFSClient can't access DN without
       * re-fetching tokens and is able to re-fetch tokens transparently. The
       * setup of this test depends on the previous test.
       */

    // restore the cluster and restart the datanodes for test
    cluster.restartNameNode(0);
    assertTrue(cluster.restartDataNodes(true));
    cluster.waitActive();
    assertEquals(numDataNodes, cluster.getDataNodes().size());

    // shutdown namenode so that DFSClient can't get new tokens from namenode
    cluster.shutdownNameNode(0);

    // verify blockSeekTo() fails if not migration mode (cached tokens become invalid)
    in1.seek(0);
    assertEquals(migrationMode, checkFile1(in1,expected));
    // verify fetchBlockByteRange() fails if not migration mode (cached tokens become invalid)
    assertEquals(migrationMode, checkFile2(in3,expected));

    // restart the namenode to allow DFSClient to re-fetch tokens
    cluster.restartNameNode(0);
    // verify blockSeekTo() works again (by transparently re-fetching
    // tokens from namenode)
    in1.seek(0);
    assertTrue(checkFile1(in1,expected));
    if (isStriped) {
      in2.seek(0);
    } else {
      in2.seekToNewSource(0);
    }
    assertTrue(checkFile1(in2,expected));
    // verify fetchBlockByteRange() works again (by transparently
    // re-fetching tokens from namenode)
    assertTrue(checkFile2(in3,expected));

      /*
       * testing that when datanodes are restarted on different ports, DFSClient
       * is able to re-fetch tokens transparently to connect to them
       */

    // restart datanodes on newly assigned ports
    assertTrue(cluster.restartDataNodes(false));
    cluster.waitActive();
    assertEquals(numDataNodes, cluster.getDataNodes().size());
    // verify blockSeekTo() is able to re-fetch token transparently
    in1.seek(0);
    assertTrue(checkFile1(in1,expected));
    // verify blockSeekTo() is able to re-fetch token transparently
    if (isStriped) {
      in2.seek(0);
    } else {
      in2.seekToNewSource(0);
    }
    assertTrue(checkFile1(in2,expected));
    // verify fetchBlockByteRange() is able to re-fetch token transparently
    assertTrue(checkFile2(in3,expected));

  }
  /**
   * Integration testing of access token, involving NN, DN, and Balancer
   */
  @Test
  public void testEnd2End() throws Exception {
    Configuration conf = new Configuration();
    conf.setBoolean(DFSConfigKeys.DFS_BLOCK_ACCESS_TOKEN_ENABLE_KEY, true);
    new TestBalancer().integrationTest(conf);
  }

  protected boolean isBlockTokenExpired(LocatedBlock lb) throws IOException {
    return SecurityTestUtil.isBlockTokenExpired(lb.getBlockToken());
  }
}
