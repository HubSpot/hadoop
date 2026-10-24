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

package com.hubspot.hadoop.net;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.net.AbstractDNSToSwitchMapping;
import org.apache.hadoop.net.CachedDNSToSwitchMapping;
import org.apache.hadoop.net.ScriptBasedMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves rack topology by GETting a co-located HTTP topology server instead of
 * forking the topology script. Mirrors {@link ScriptBasedMapping}: the
 * {@link CachedDNSToSwitchMapping} superclass owns the cache and the inner
 * {@link RawTopologyServerMapping} is a stateless request handler.
 */
public class TopologyServerDNSToSwitchMapping extends CachedDNSToSwitchMapping {

  public static final String SERVER_URL_KEY = "net.topology.server.url";
  public static final String SERVER_URL_DEFAULT =
      "http://127.0.0.1:50074/resolve";

  public static final String QUERY_PARAM_KEY =
      "net.topology.server.query.param";
  public static final String QUERY_PARAM_DEFAULT = "ip";

  public static final String CONNECT_TIMEOUT_KEY =
      "net.topology.server.connect.timeout.ms";
  public static final int CONNECT_TIMEOUT_DEFAULT = 1000;

  public static final String READ_TIMEOUT_KEY =
      "net.topology.server.read.timeout.ms";
  public static final int READ_TIMEOUT_DEFAULT = 2000;

  public static final String MAX_HOSTS_PER_REQUEST_KEY =
      "net.topology.server.max.hosts.per.request";
  public static final int MAX_HOSTS_PER_REQUEST_DEFAULT = 100;

  private final RawTopologyServerMapping rawServer;

  public TopologyServerDNSToSwitchMapping() {
    this(new RawTopologyServerMapping());
  }

  public TopologyServerDNSToSwitchMapping(Configuration conf) {
    this();
    setConf(conf);
  }

  private TopologyServerDNSToSwitchMapping(RawTopologyServerMapping rawServer) {
    super(rawServer);
    this.rawServer = rawServer;
  }

  @Override
  public Configuration getConf() {
    return rawServer.getConf();
  }

  @Override
  public void setConf(Configuration conf) {
    super.setConf(conf);
    rawServer.setConf(conf);
  }

  @Override
  public String toString() {
    return "topology-server-based mapping with " + rawServer;
  }

  /** Stateless handler fed into the cache superclass: one GET per chunk of hosts. */
  protected static class RawTopologyServerMapping
      extends AbstractDNSToSwitchMapping {

    private static final Logger LOG =
        LoggerFactory.getLogger(TopologyServerDNSToSwitchMapping.class);

    private String serverUrl;
    private String queryParam;
    private int connectTimeout;
    private int readTimeout;
    private int maxHostsPerRequest;

    @Override
    public void setConf(Configuration conf) {
      super.setConf(conf);
      if (conf == null) {
        return;
      }
      serverUrl = conf.get(SERVER_URL_KEY, SERVER_URL_DEFAULT);
      queryParam = conf.get(QUERY_PARAM_KEY, QUERY_PARAM_DEFAULT);
      connectTimeout = conf.getInt(CONNECT_TIMEOUT_KEY, CONNECT_TIMEOUT_DEFAULT);
      readTimeout = conf.getInt(READ_TIMEOUT_KEY, READ_TIMEOUT_DEFAULT);
      maxHostsPerRequest =
          conf.getInt(MAX_HOSTS_PER_REQUEST_KEY, MAX_HOSTS_PER_REQUEST_DEFAULT);
      LOG.info("RawTopologyServerMapping configured: url={}, queryParam={}, "
              + "connectTimeoutMs={}, readTimeoutMs={}, maxHostsPerRequest={}",
          serverUrl, queryParam, connectTimeout, readTimeout,
          maxHostsPerRequest);
    }

    @Override
    public List<String> resolve(List<String> names) {
      List<String> results = new ArrayList<String>(names.size());
      // Names are already IP-normalized by CachedDNSToSwitchMapping.
      for (int start = 0; start < names.size(); start += maxHostsPerRequest) {
        int end = Math.min(start + maxHostsPerRequest, names.size());
        List<String> paths = query(names.subList(start, end));
        // On any error return null (like the script path): nothing is cached and
        // the NameNode falls back to /default-rack, retried next call.
        if (paths == null) {
          return null;
        }
        results.addAll(paths);
      }
      return results;
    }

    private List<String> query(List<String> hosts) {
      String requestUrl = buildUrl(hosts);
      HttpURLConnection conn = null;
      try {
        conn = (HttpURLConnection) new URL(requestUrl).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(connectTimeout);
        conn.setReadTimeout(readTimeout);
        conn.setRequestProperty("Accept", "text/plain");

        int status = conn.getResponseCode();
        if (status != HttpURLConnection.HTTP_OK) {
          LOG.warn("Topology server {} returned HTTP {} for {} host(s)",
              serverUrl, status, hosts.size());
          return null;
        }
        List<String> paths = tokenize(readBody(conn.getInputStream()));
        if (paths.size() != hosts.size()) {
          LOG.warn("Topology server {} returned {} path(s) for {} host(s)",
              serverUrl, paths.size(), hosts.size());
          return null;
        }
        return paths;
      } catch (IOException e) {
        LOG.warn("Topology server {} lookup failed for {} host(s): {}",
            serverUrl, hosts.size(), e.toString());
        return null;
      } finally {
        if (conn != null) {
          conn.disconnect();
        }
      }
    }

    private String buildUrl(List<String> hosts) {
      StringBuilder sb = new StringBuilder(serverUrl);
      char sep = serverUrl.indexOf('?') >= 0 ? '&' : '?';
      for (String host : hosts) {
        sb.append(sep).append(queryParam).append('=').append(encode(host));
        sep = '&';
      }
      return sb.toString();
    }

    private static String encode(String value) {
      try {
        return URLEncoder.encode(value, "UTF-8");
      } catch (UnsupportedEncodingException e) {
        throw new IllegalStateException("UTF-8 unavailable", e);
      }
    }

    private static String readBody(InputStream in) throws IOException {
      StringBuilder sb = new StringBuilder();
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          sb.append(line).append(' ');
        }
      }
      return sb.toString();
    }

    private static List<String> tokenize(String body) {
      List<String> tokens = new ArrayList<String>();
      if (body != null) {
        for (String token : body.trim().split("\\s+")) {
          if (!token.isEmpty()) {
            tokens.add(token);
          }
        }
      }
      return tokens;
    }

    @Override
    public String toString() {
      return "topology server at " + serverUrl;
    }

    @Override
    public void reloadCachedMappings() {
    }

    @Override
    public void reloadCachedMappings(List<String> names) {
    }
  }
}
