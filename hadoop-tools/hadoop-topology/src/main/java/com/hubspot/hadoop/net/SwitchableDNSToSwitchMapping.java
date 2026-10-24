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

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.net.AbstractDNSToSwitchMapping;
import org.apache.hadoop.net.ScriptBasedMapping;

/**
 * The mapping pointed at by {@code net.topology.node.switch.mapping.impl}: forwards
 * to {@link TopologyServerDNSToSwitchMapping} when {@code net.topology.server.enabled}
 * is true, otherwise the stock {@link ScriptBasedMapping}.
 */
public class SwitchableDNSToSwitchMapping extends AbstractDNSToSwitchMapping {

  private static final Logger LOG =
      LoggerFactory.getLogger(SwitchableDNSToSwitchMapping.class);

  public static final String SERVER_ENABLED_KEY =
      "net.topology.server.enabled";
  public static final boolean SERVER_ENABLED_DEFAULT = false;

  private AbstractDNSToSwitchMapping delegate;

  public SwitchableDNSToSwitchMapping() {
  }

  public SwitchableDNSToSwitchMapping(Configuration conf) {
    setConf(conf);
  }

  @Override
  public void setConf(Configuration conf) {
    super.setConf(conf);
    boolean useServer = conf != null
        && conf.getBoolean(SERVER_ENABLED_KEY, SERVER_ENABLED_DEFAULT);
    if (useServer) {
      delegate = new TopologyServerDNSToSwitchMapping(conf);
    } else {
      delegate = new ScriptBasedMapping(conf);
    }
    LOG.info("Topology resolution via {} ({}={})",
        delegate, SERVER_ENABLED_KEY, useServer);
  }

  @Override
  public List<String> resolve(List<String> names) {
    return delegate.resolve(names);
  }

  @Override
  public void reloadCachedMappings() {
    delegate.reloadCachedMappings();
  }

  @Override
  public void reloadCachedMappings(List<String> names) {
    delegate.reloadCachedMappings(names);
  }

  @Override
  public boolean isSingleSwitch() {
    return isMappingSingleSwitch(delegate);
  }

  @Override
  public Map<String, String> getSwitchMap() {
    return delegate.getSwitchMap();
  }

  @Override
  public String toString() {
    return "switchable mapping delegating to " + delegate;
  }
}
