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
import org.apache.hadoop.net.DNSToSwitchMapping;
import org.apache.hadoop.net.ScriptBasedMapping;

/**
 * The {@link DNSToSwitchMapping} the NameNode (and later the NodeManagers)
 * should be pointed at via {@code net.topology.node.switch.mapping.impl}. It
 * dispatches to one of two backends, chosen purely by configuration so the
 * migration can be flipped on or rolled back without a config-class change:
 *
 * <ul>
 *   <li>{@link SidecarDNSToSwitchMapping} - the new HTTP sidecar path, when
 *       {@code net.topology.sidecar.enabled=true}.</li>
 *   <li>{@link ScriptBasedMapping} - the stock forked-script path, otherwise.</li>
 * </ul>
 *
 * <p>The flag defaults to {@code false} (stock script behavior) so enabling the
 * sidecar is an explicit, reversible opt-in. Both backends manage their own
 * cache, so this dispatcher holds none and forwards every call to the delegate.
 */
public class SwitchableDNSToSwitchMapping extends AbstractDNSToSwitchMapping {

  private static final Logger LOG =
      LoggerFactory.getLogger(SwitchableDNSToSwitchMapping.class);

  /** When true, resolve via the HTTP sidecar; otherwise via the script. */
  public static final String SIDECAR_ENABLED_KEY =
      "net.topology.sidecar.enabled";
  public static final boolean SIDECAR_ENABLED_DEFAULT = false;

  private AbstractDNSToSwitchMapping delegate;

  public SwitchableDNSToSwitchMapping() {
  }

  public SwitchableDNSToSwitchMapping(Configuration conf) {
    setConf(conf);
  }

  @Override
  public void setConf(Configuration conf) {
    super.setConf(conf);
    boolean useSidecar = conf != null
        && conf.getBoolean(SIDECAR_ENABLED_KEY, SIDECAR_ENABLED_DEFAULT);
    if (useSidecar) {
      delegate = new SidecarDNSToSwitchMapping(conf);
      LOG.info("Topology resolution ENABLED via HTTP sidecar ({}=true). "
          + "Delegate: {}", SIDECAR_ENABLED_KEY, delegate);
    } else {
      delegate = new ScriptBasedMapping(conf);
      LOG.info("Topology resolution using stock script mapping ({}=false). "
          + "Delegate: {}", SIDECAR_ENABLED_KEY, delegate);
    }
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
