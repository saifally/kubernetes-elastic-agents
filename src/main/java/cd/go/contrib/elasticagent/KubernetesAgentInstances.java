/*
 * Copyright 2017 ThoughtWorks, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cd.go.contrib.elasticagent;

import static cd.go.contrib.elasticagent.KubernetesPlugin.LOG;
import static cd.go.contrib.elasticagent.executors.GetProfileMetadataExecutor.SPECIFIED_USING_POD_CONFIGURATION;
import static cd.go.contrib.elasticagent.utils.Util.getSimpleDateFormat;
import static java.text.MessageFormat.format;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

import org.apache.commons.lang3.StringUtils;
import org.joda.time.DateTime;
import org.joda.time.Period;

import cd.go.contrib.elasticagent.model.JobIdentifier;
import cd.go.contrib.elasticagent.requests.CreateAgentRequest;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.client.KubernetesClient;

public class KubernetesAgentInstances implements AgentInstances<KubernetesInstance> {
    private final ConcurrentHashMap<String, KubernetesInstance> instances = new ConcurrentHashMap<>();
    public Clock clock = Clock.DEFAULT;
    final Semaphore semaphore = new Semaphore(0, true);

    private KubernetesClientFactory factory;
    private KubernetesInstanceFactory kubernetesInstanceFactory;

    public KubernetesAgentInstances() {
        this(KubernetesClientFactory.instance(), new KubernetesInstanceFactory());
    }

    public KubernetesAgentInstances(KubernetesClientFactory factory) {
        this(factory, new KubernetesInstanceFactory());
    }

    public KubernetesAgentInstances(KubernetesClientFactory factory, KubernetesInstanceFactory kubernetesInstanceFactory) {
        this.factory = factory;
        this.kubernetesInstanceFactory = kubernetesInstanceFactory;
    }

    @Override
    public KubernetesInstance create(CreateAgentRequest request, ElasticProfileSettings settings, PluginRequest pluginRequest) {
        final Integer maxAllowedContainers = settings.getMaxPendingPods();
        synchronized (instances) {
            refreshAll(pluginRequest);
            doWithLockOnSemaphore(new SetupSemaphore(maxAllowedContainers, instances, semaphore));

            if (semaphore.tryAcquire()) {
                return createKubernetesInstance(request, settings, pluginRequest);
            } else {
                LOG.warn(format("Create Agent Request] The number of pending kubernetes pods is currently at the maximum permissible limit ({0}). Total kubernetes pods ({1}). Not creating any more containers.", maxAllowedContainers, instances.size()));
                return null;
            }
        }
    }

    private void doWithLockOnSemaphore(Runnable runnable) {
        synchronized (semaphore) {
            runnable.run();
        }
    }

    private KubernetesInstance createKubernetesInstance(CreateAgentRequest request, ElasticProfileSettings elasticProfileSettings, PluginRequest pluginRequest) {
        JobIdentifier jobIdentifier = request.jobIdentifier();
        if (isAgentCreatedForJob(jobIdentifier.getJobId())) {
            LOG.warn(format("[Create Agent Request] Request for creating an agent for Job Identifier [{0}] has already been scheduled. Skipping current request.", jobIdentifier));
            return null;
        }
        
        KubernetesClient client = factory.createClientForElasticProfile(elasticProfileSettings);
        KubernetesInstance instance = kubernetesInstanceFactory.create(request, elasticProfileSettings, client, pluginRequest, isUsingPodYaml(request));
        register(instance);

        return instance;
    }

    private boolean isAgentCreatedForJob(Long jobId) {
        for (KubernetesInstance instance : instances.values()) {
            if (instance.jobId().equals(jobId)) {
                return true;
            }
        }

        return false;
    }

    private boolean isUsingPodYaml(CreateAgentRequest request) {
        return Boolean.valueOf(request.properties().get(SPECIFIED_USING_POD_CONFIGURATION.getKey()));
    }

    @Override
    public void terminate(String agentId) {
        KubernetesInstance instance = instances.get(agentId);
        if (instance != null) {
        	KubernetesClient client = factory.createClientForElasticProfile(instance.getSettings());
            instance.terminate(client);
        } else {
            LOG.warn(format("Requested to terminate an instance that does not exist {0}.", agentId));
        }
        instances.remove(agentId);
    }

    @Override
    public void terminateUnregisteredInstances(Agents agents) throws Exception {
        KubernetesAgentInstances toTerminate = unregisteredAfterTimeout(agents);
        if (toTerminate.instances.isEmpty()) {
            return;
        }

        LOG.warn(format("Terminating instances that did not register {0}.", toTerminate.instances.keySet()));
        for (String agentId : toTerminate.instances.keySet()) {
            terminate(agentId);
        }
    }

    @Override
    public Agents instancesCreatedAfterTimeout(Agents agents) {
        ArrayList<Agent> oldAgents = new ArrayList<>();
        KubernetesInstance instance;
        
        for (Agent agent : agents.agents()) {
            instance = instances.get(agent.elasticAgentId());
            if (instance == null) {
                continue;
            }
            
            if (clock.now().isAfter(instance.createdAt().plus(new Period().withMinutes(instance.getSettings().getAutoRegisterTimeout())))) {
                oldAgents.add(agent);
            }
            instance = null;
        }
        return new Agents(oldAgents);
    }

    @Override
    public void refreshAll(PluginRequest pluginRequest) {
    	
    	Map<String,KubernetesClient> clientMap = new HashMap<>();
    	instances.values().forEach( instance -> {
	        LOG.debug("[refresh-pod-state] Syncing k8s elastic agent pod information.");
	        KubernetesClient client = factory.createClientForElasticProfile(instance.getSettings());
	        
	        if (!clientMap.containsKey(client.getNamespace())) {
		        clientMap.put(client.getNamespace(), client);
		        
		        LOG.debug("[refresh-pod-state] for Namespace ."+ client.getNamespace());
			    PodList list = client.pods().list();
			    LOG.debug("[refresh-pod-state] for Pods size ."+list.getItems().size());
			        
		    	for (Pod pod : list.getItems()) {
			        Map<String, String> podLabels = pod.getMetadata().getLabels();
			           if (podLabels != null) {
			             if (StringUtils.equals(Constants.KUBERNETES_POD_KIND_LABEL_VALUE, podLabels.get(Constants.KUBERNETES_POD_KIND_LABEL_KEY))) {
			                 register(kubernetesInstanceFactory.fromKubernetesPod(pod,instance.getSettings()));
	                     }
			           }
			        }
		         }
    	});
    	
    	LOG.debug(String.format("[refresh-pod-state] Pod information successfully synced. All(Running/Pending) pod count is %d.", instances.size()));
    }

    @Override
    public KubernetesInstance find(String agentId) {
        return instances.get(agentId);
    }

    public void register(KubernetesInstance instance) {
    	if(!instances.containsKey(instance.name())) {
	        instances.put(instance.name(), instance);
    	}
    }

    private KubernetesAgentInstances unregisteredAfterTimeout(Agents knownAgents) throws Exception {
        KubernetesAgentInstances unregisteredInstances = new KubernetesAgentInstances();
        KubernetesClient client;

        for (KubernetesInstance instance : instances.values()) {
            if (knownAgents.containsAgentWithId(instance.name())) {
                continue;
            }
            client = factory.createClientForElasticProfile(instance.getSettings());
            
            Pod pod = getPod(client, instance.name());
            if (pod == null) {
                LOG.debug(String.format("[server-ping] Pod with name %s is already deleted.", instance.name()));
                continue;
            }

            Date createdAt = getSimpleDateFormat().parse(pod.getMetadata().getCreationTimestamp());
            DateTime dateTimeCreated = new DateTime(createdAt);

            if (clock.now().isAfter(dateTimeCreated.plus(new Period().withMinutes(instance.getSettings().getAutoRegisterTimeout())))) {
                unregisteredInstances.register(kubernetesInstanceFactory.fromKubernetesPod(pod,instance.getSettings()));
            }
        }
        return unregisteredInstances;
    }

    private Pod getPod(KubernetesClient client, String instanceName) {
        try {
            return client.pods().withName(instanceName).get();
        } catch (Exception e) {
            LOG.warn(String.format("[server-ping] Failed to fetch pod[%s] information:", instanceName), e);
            return null;
        }
    }

    public boolean instanceExists(KubernetesInstance instance) {
        return instances.contains(instance);
    }
}

