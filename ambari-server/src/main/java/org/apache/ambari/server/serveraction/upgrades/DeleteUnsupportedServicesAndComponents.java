/*
 *
 *  * Licensed to the Apache Software Foundation (ASF) under one
 *  * or more contributor license agreements.  See the NOTICE file
 *  * distributed with this work for additional information
 *  * regarding copyright ownership.  The ASF licenses this file
 *  * to you under the Apache License, Version 2.0 (the
 *  * "License"); you may not use this file except in compliance
 *  * with the License.  You may obtain a copy of the License at
 *  *
 *  *     http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package org.apache.ambari.server.serveraction.upgrades;

import static java.util.stream.Collectors.toList;
import static org.apache.commons.collections.CollectionUtils.union;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Predicate;

import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.actionmanager.HostRoleStatus;
import org.apache.ambari.server.agent.CommandReport;
import org.apache.ambari.server.controller.internal.DeleteHostComponentStatusMetaData;
import org.apache.ambari.server.orm.entities.RepositoryVersionEntity;
import org.apache.ambari.server.orm.entities.UpgradeEntity;
import org.apache.ambari.server.orm.entities.UpgradeHistoryEntity;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.ServiceComponent;
import org.apache.ambari.server.state.ServiceComponentSupport;
import org.apache.ambari.server.state.UpgradeContext;
import org.apache.commons.lang.StringUtils;

import com.google.inject.Inject;

/**
 * Upgrade Server Action that deletes the components and services which are no longer supported in the target stack.
 * The deletable component or service should be in deletable state (stopped) before executing this.
 */
public class DeleteUnsupportedServicesAndComponents extends AbstractUpgradeServerAction {
  @Inject
  private ServiceComponentSupport serviceComponentSupport;

  @Override
  public CommandReport execute(ConcurrentMap<String, Object> requestSharedDataContext) throws AmbariException, InterruptedException {
    Cluster cluster = getClusters().getCluster(getExecutionCommand().getClusterName());
    UpgradeContext upgradeContext = getUpgradeContext(cluster);
    Set<String> removedComponents = deleteUnsupportedComponents(cluster, upgradeContext.getRepositoryVersion());
    Set<String> removedServices = deleteUnsupportedServices(cluster, upgradeContext.getRepositoryVersion());
    return createCommandReport(0, HostRoleStatus.COMPLETED, "{}",
      "Removed services: " + StringUtils.join(union(removedComponents, removedServices), ", "), "");
  }

  private Set<String> deleteUnsupportedServices(Cluster cluster, RepositoryVersionEntity repoVersion) throws AmbariException {
    Set<String> servicesToBeRemoved = serviceComponentSupport.unsupportedServices(cluster, repoVersion.getStackName(), repoVersion.getStackVersion());
    for (String serviceName : servicesToBeRemoved) {
      cluster.deleteService(serviceName, new DeleteHostComponentStatusMetaData());
      deleteUpgradeHistory(cluster, history -> serviceName.equals(history.getServiceName()));
    }
    return servicesToBeRemoved;
  }

  private Set<String> deleteUnsupportedComponents(Cluster cluster, RepositoryVersionEntity repoVersion) throws AmbariException {
    Set<String> deletedComponents = new HashSet<>();
    for (ServiceComponent component : serviceComponentSupport.unsupportedComponents(cluster, repoVersion.getStackName(), repoVersion.getStackVersion())) {
      cluster.getService(component.getServiceName()).deleteServiceComponent(component.getName(), new DeleteHostComponentStatusMetaData());
      deleteUpgradeHistory(cluster, history -> component.getName().equals(history.getComponentName()));
      deletedComponents.add(component.getName());
    }
    return deletedComponents;
  }

  private void deleteUpgradeHistory(Cluster cluster, Predicate<UpgradeHistoryEntity> predicate) {
    UpgradeEntity upgradeInProgress = cluster.getUpgradeInProgress();
    List<UpgradeHistoryEntity> removed = upgradeInProgress.getHistory().stream()
      .filter(each -> each != null && predicate.test(each))
      .collect(toList());
    upgradeInProgress.removeHistories(removed);
    m_upgradeDAO.merge(upgradeInProgress);
  }
}
