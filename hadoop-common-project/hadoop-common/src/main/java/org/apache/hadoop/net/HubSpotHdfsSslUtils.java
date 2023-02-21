package org.apache.hadoop.net;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;

import javax.net.ssl.SSLSocket;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class HubSpotHdfsSslUtils {

  public static final Log LOG = LogFactory.getLog(HubSpotHdfsSslUtils.class);

  public static SocketAddress sslAddr(SocketAddress addr, Socket socket) {
    try {
      // We want to use the SSL port for our Hadoop services when:
      //  1. The socket factory specified by hadoop.rpc.socket.factory.class.* produced an SSL socket
      //  2. The address we're using is an InetSocketAddress, so it actually has a port. This should always be true.
      // See NetUtils#getDefaultSocketFactory() for code that collaborates with this.
      if (socket instanceof SSLSocket &&
          addr instanceof InetSocketAddress) {
        InetSocketAddress inetAddr = (InetSocketAddress) addr;
        int port = inetAddr.getPort();
        int newPort = translatePort(port);
        if (newPort != port) {
          InetSocketAddress newAddr = new InetSocketAddress(inetAddr.getHostString(), newPort);
          LOG.debug("Converted " + inetAddr + " to " + newAddr);
          return newAddr;
        }
      }
    } catch (Exception e) {
      LOG.warn("Unable to switch to SSL port for " + addr, e);
    }
    return addr;
  }

  private static int translatePort(int port) {
    // port mapping matches haproxy.pp in puppet-deploy
    if (port == 50010) {  // DataNode transfer
      return 50011;
    } else if (port == 50020) {  // DataNode IPC
      return 50021;
    } else if (port == 8020) {  // NameNode
      return 8017;
    } else if (port == 8021) {  // JobTracker
      return 8023;
    } else if (port == 8022) {  // JobTracker HA
      return 8024;
    } else {
      return port;
    }
  }
}
