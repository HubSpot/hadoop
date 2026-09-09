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
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.net.AbstractDNSToSwitchMapping;
import org.apache.hadoop.net.DNSToSwitchMapping;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.net.NetworkTopology;

/**
 * A {@link DNSToSwitchMapping} that resolves rack topology by making an HTTP
 * GET to a co-located sidecar instead of forking the topology script. This
 * moves the per-lookup memory cost (30-40 MiB per forked script) out of the
 * NameNode container and into a single long-lived sidecar process.
 *
 * <p>One GET is issued per host, exactly as the topology script would be handed
 * one argument at a time: the (optionally DNS-normalized) host/IP string is
 * passed through unchanged as a query parameter, and the sidecar returns the
 * network path (e.g. {@code /rack1}) as a plain-text body.
 *
 * <h3>Caching semantics</h3>
 * This class manages its own cache so it can distinguish the two failure modes
 * the way operators need:
 * <ul>
 *   <li><b>Processed request</b> (HTTP 200 with a non-blank body) &rarr; cached,
 *       <em>even when the body is {@code /default-rack}</em> because the sidecar
 *       processed the request and its own DNS lookup simply came up empty. That
 *       is a real answer, not an error.</li>
 *   <li><b>Connection failure</b> (sidecar unreachable, timeout, non-200, or
 *       blank body) &rarr; <em>never cached</em>. The host still resolves to
 *       {@code /default-rack} for this call so the NameNode keeps functioning,
 *       but the next request retries the sidecar.</li>
 * </ul>
 *
 * <p>Every class referenced here is part of the JDK or already on the NameNode
 * classpath, so this jar bundles no dependencies of its own.
 */
public class SidecarDNSToSwitchMapping extends AbstractDNSToSwitchMapping {

  private static final Logger LOG =
      LoggerFactory.getLogger(SidecarDNSToSwitchMapping.class);

  private static final Charset UTF8 = Charset.forName("UTF-8");

  /** Full URL of the sidecar resolve endpoint. */
  public static final String SIDECAR_URL_KEY = "net.topology.sidecar.url";
  public static final String SIDECAR_URL_DEFAULT =
      "http://127.0.0.1:8677/resolve";

  /** Query parameter name that carries the host/IP being resolved. */
  public static final String QUERY_PARAM_KEY = "net.topology.sidecar.query.param";
  public static final String QUERY_PARAM_DEFAULT = "ip";

  /** Connect timeout in milliseconds. */
  public static final String CONNECT_TIMEOUT_KEY =
      "net.topology.sidecar.connect.timeout.ms";
  public static final int CONNECT_TIMEOUT_DEFAULT = 2000;

  /** Read timeout in milliseconds. */
  public static final String READ_TIMEOUT_KEY =
      "net.topology.sidecar.read.timeout.ms";
  public static final int READ_TIMEOUT_DEFAULT = 5000;

  /** Whether resolved answers are cached at all. */
  public static final String CACHE_ENABLED_KEY =
      "net.topology.sidecar.cache.enabled";
  public static final boolean CACHE_ENABLED_DEFAULT = true;

  /**
   * Whether host names are DNS-normalized to IPs before being sent to the
   * sidecar. The stock cached script path normalizes, so this defaults to true
   * to keep the string handed to the sidecar identical to what the script saw.
   */
  public static final String NORMALIZE_KEY =
      "net.topology.sidecar.normalize.hostnames";
  public static final boolean NORMALIZE_DEFAULT = true;

  /** host/IP -> network path. Only processed answers ever land here. */
  private final Map<String, String> cache =
      new ConcurrentHashMap<String, String>();

  /** Tracks up/down so we log a single line on each state transition. */
  private final AtomicBoolean sidecarReachable = new AtomicBoolean(true);

  private String sidecarUrl;
  private String queryParam;
  private int connectTimeout;
  private int readTimeout;
  private boolean cacheEnabled;
  private boolean normalize;

  public SidecarDNSToSwitchMapping() {
  }

  public SidecarDNSToSwitchMapping(Configuration conf) {
    setConf(conf);
  }

  @Override
  public void setConf(Configuration conf) {
    super.setConf(conf);
    if (conf != null) {
      sidecarUrl = conf.get(SIDECAR_URL_KEY, SIDECAR_URL_DEFAULT);
      queryParam = conf.get(QUERY_PARAM_KEY, QUERY_PARAM_DEFAULT);
      connectTimeout = conf.getInt(CONNECT_TIMEOUT_KEY, CONNECT_TIMEOUT_DEFAULT);
      readTimeout = conf.getInt(READ_TIMEOUT_KEY, READ_TIMEOUT_DEFAULT);
      cacheEnabled = conf.getBoolean(CACHE_ENABLED_KEY, CACHE_ENABLED_DEFAULT);
      normalize = conf.getBoolean(NORMALIZE_KEY, NORMALIZE_DEFAULT);
    } else {
      sidecarUrl = SIDECAR_URL_DEFAULT;
      queryParam = QUERY_PARAM_DEFAULT;
      connectTimeout = CONNECT_TIMEOUT_DEFAULT;
      readTimeout = READ_TIMEOUT_DEFAULT;
      cacheEnabled = CACHE_ENABLED_DEFAULT;
      normalize = NORMALIZE_DEFAULT;
    }
    LOG.info("SidecarDNSToSwitchMapping configured: url={}, queryParam={}, "
            + "connectTimeoutMs={}, readTimeoutMs={}, cacheEnabled={}, "
            + "normalizeHostnames={}",
        sidecarUrl, queryParam, connectTimeout, readTimeout, cacheEnabled,
        normalize);
  }

  @Override
  public List<String> resolve(List<String> names) {
    List<String> results = new ArrayList<String>(names.size());
    if (names.isEmpty()) {
      return results;
    }

    // Match the stock cached-script path: resolve host names to IPs so the
    // string handed to the sidecar is the same one the script would have seen.
    List<String> keys = normalize ? NetUtils.normalizeHostNames(names) : names;

    int hits = 0;
    int processed = 0;
    int failures = 0;

    for (int i = 0; i < names.size(); i++) {
      String name = names.get(i);
      String key = keys.get(i);

      String cached = cacheEnabled ? cache.get(key) : null;
      if (cached != null) {
        hits++;
        results.add(cached);
        if (LOG.isDebugEnabled()) {
          LOG.debug("Cache hit: {} (from {}) -> {}", key, name, cached);
        }
        continue;
      }

      String rack = querySidecar(key);
      if (rack != null) {
        processed++;
        if (cacheEnabled) {
          cache.put(key, rack);
        }
        results.add(rack);
        if (LOG.isDebugEnabled()) {
          LOG.debug("Resolved and {}: {} (from {}) -> {}",
              cacheEnabled ? "cached" : "not cached (cache disabled)",
              key, name, rack);
        }
      } else {
        // Connection failure: fall back to default rack for this call but do
        // NOT cache, so the next request retries the sidecar.
        failures++;
        results.add(NetworkTopology.DEFAULT_RACK);
        LOG.warn("Sidecar lookup failed for {} (from {}); returning {} WITHOUT "
                + "caching so it is retried next time.",
            key, name, NetworkTopology.DEFAULT_RACK);
      }
    }

    LOG.info("Topology resolve of {} host(s): {} cache hit(s), {} newly "
            + "processed, {} failure(s). cacheSize={}",
        names.size(), hits, processed, failures, cache.size());
    return results;
  }

  /**
   * Issue a single GET to the sidecar for one host/IP.
   *
   * @return the resolved network path if the sidecar processed the request
   *         (HTTP 200 with a non-blank body), or {@code null} on any failure
   *         (unreachable, timeout, non-200, blank body). A {@code null} return
   *         is the signal to the caller not to cache.
   */
  private String querySidecar(String host) {
    long startNanos = System.nanoTime();
    HttpURLConnection conn = null;
    String requestUrl = null;
    try {
      requestUrl = buildUrl(host);
      URL url = new URL(requestUrl);
      conn = (HttpURLConnection) url.openConnection();
      conn.setRequestMethod("GET");
      conn.setConnectTimeout(connectTimeout);
      conn.setReadTimeout(readTimeout);
      conn.setRequestProperty("Accept", "text/plain");

      int status = conn.getResponseCode();
      long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

      if (status != HttpURLConnection.HTTP_OK) {
        LOG.warn("Sidecar {} returned HTTP {} for host {} in {} ms",
            requestUrl, status, host, elapsedMs);
        markUnreachable();
        return null;
      }

      String body = readBody(conn.getInputStream());
      String rack = firstToken(body);
      if (rack == null) {
        LOG.warn("Sidecar {} returned HTTP 200 but a blank body for host {} "
            + "in {} ms; treating as failure.", requestUrl, host, elapsedMs);
        markUnreachable();
        return null;
      }

      if (LOG.isDebugEnabled()) {
        LOG.debug("Sidecar {} resolved host {} -> {} (HTTP 200, {} ms)",
            requestUrl, host, rack, elapsedMs);
      }
      markReachable();
      return rack;
    } catch (IOException e) {
      long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
      LOG.warn("Failed to reach sidecar {} for host {} after {} ms: {}",
          requestUrl != null ? requestUrl : sidecarUrl, host, elapsedMs,
          e.toString());
      LOG.debug("Sidecar failure stack trace", e);
      markUnreachable();
      return null;
    } finally {
      if (conn != null) {
        conn.disconnect();
      }
    }
  }

  private String buildUrl(String host) throws UnsupportedEncodingException {
    String encoded = URLEncoder.encode(host, "UTF-8");
    char sep = sidecarUrl.indexOf('?') >= 0 ? '&' : '?';
    return sidecarUrl + sep + queryParam + '=' + encoded;
  }

  private static String readBody(InputStream in) throws IOException {
    BufferedReader reader = new BufferedReader(new InputStreamReader(in, UTF8));
    try {
      StringBuilder sb = new StringBuilder();
      String line;
      while ((line = reader.readLine()) != null) {
        sb.append(line).append(' ');
      }
      return sb.toString();
    } finally {
      reader.close();
    }
  }

  /** Returns the first whitespace-delimited token, or null if none. */
  private static String firstToken(String body) {
    if (body == null) {
      return null;
    }
    String trimmed = body.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    int ws = 0;
    while (ws < trimmed.length() && !Character.isWhitespace(trimmed.charAt(ws))) {
      ws++;
    }
    return trimmed.substring(0, ws);
  }

  private void markReachable() {
    if (sidecarReachable.compareAndSet(false, true)) {
      LOG.info("Sidecar {} is reachable again; topology resolution recovered.",
          sidecarUrl);
    }
  }

  private void markUnreachable() {
    if (sidecarReachable.compareAndSet(true, false)) {
      LOG.warn("Sidecar {} became unreachable; hosts will resolve to {} "
              + "(uncached) until it recovers.",
          sidecarUrl, NetworkTopology.DEFAULT_RACK);
    }
  }

  @Override
  public Map<String, String> getSwitchMap() {
    return new HashMap<String, String>(cache);
  }

  @Override
  public boolean isSingleSwitch() {
    return false;
  }

  @Override
  public String toString() {
    return "sidecar-based mapping to " + sidecarUrl;
  }

  @Override
  public void reloadCachedMappings() {
    int size = cache.size();
    cache.clear();
    LOG.info("Cleared sidecar topology cache ({} entries).", size);
  }

  @Override
  public void reloadCachedMappings(List<String> names) {
    if (names == null) {
      return;
    }
    List<String> keys = normalize ? NetUtils.normalizeHostNames(names) : names;
    for (String key : keys) {
      cache.remove(key);
    }
    LOG.info("Evicted {} host(s) from sidecar topology cache.", keys.size());
  }
}
