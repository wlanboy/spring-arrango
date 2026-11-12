package com.example.arrango.k8s;

import com.example.arrango.model.k8s.ConfigMap;
import com.example.arrango.model.k8s.HelmRelease;
import com.example.arrango.model.k8s.Pod;
import com.example.arrango.model.k8s.Service;
import com.example.arrango.repository.k8s.ConfigMapRepository;
import com.example.arrango.repository.k8s.ConfigUsage;
import com.example.arrango.repository.k8s.HelmReleaseRepository;
import com.example.arrango.repository.k8s.NodeLoad;
import com.example.arrango.repository.k8s.NodeRepository;
import com.example.arrango.repository.k8s.PodRepository;
import com.example.arrango.repository.k8s.ResourceRef;
import com.example.arrango.repository.k8s.ServiceRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/k8s")
public class K8sGraphController {

	private final ClusterGraphImporter importer;
	private final HelmReleaseRepository releases;
	private final ConfigMapRepository configMaps;
	private final PodRepository pods;
	private final ServiceRepository services;
	private final NodeRepository nodes;

	public K8sGraphController(ClusterGraphImporter importer, HelmReleaseRepository releases,
			ConfigMapRepository configMaps, PodRepository pods, ServiceRepository services, NodeRepository nodes) {
		this.importer = importer;
		this.releases = releases;
		this.configMaps = configMaps;
		this.pods = pods;
		this.services = services;
		this.nodes = nodes;
	}

	@PostMapping("/import")
	public Map<String, Integer> importCluster() {
		return importer.importCluster();
	}

	@GetMapping("/releases")
	public Iterable<HelmRelease> releases() {
		return releases.findAll();
	}

	@GetMapping("/releases/{namespace}/{name}/resources")
	public List<ResourceRef> releaseResources(@PathVariable String namespace, @PathVariable String name) {
		return releases.resources(namespace, name);
	}

	@GetMapping("/releases/{namespace}/{name}/config")
	public List<ResourceRef> releaseConfig(@PathVariable String namespace, @PathVariable String name) {
		return releases.configDependencies(namespace, name);
	}

	@GetMapping("/configmaps/{namespace}/{name}/consumers")
	public List<ConfigUsage> configMapConsumers(@PathVariable String namespace, @PathVariable String name) {
		return configMaps.consumers("config_maps", namespace, name);
	}

	@GetMapping("/secrets/{namespace}/{name}/consumers")
	public List<ConfigUsage> secretConsumers(@PathVariable String namespace, @PathVariable String name) {
		return configMaps.consumers("secrets", namespace, name);
	}

	@GetMapping("/configmaps/unused")
	public List<ConfigMap> unusedConfigMaps() {
		return configMaps.unused();
	}

	@GetMapping("/services/{namespace}/{name}/backends")
	public List<Pod> serviceBackends(@PathVariable String namespace, @PathVariable String name) {
		return pods.backendsOfService(namespace, name);
	}

	@GetMapping("/services/without-backends")
	public List<Service> servicesWithoutBackends() {
		return services.withoutBackends();
	}

	@GetMapping("/nodes/load")
	public List<NodeLoad> nodeLoad() {
		return nodes.load();
	}

	/** z. B. {@code /api/k8s/path?from=services/istio-system.istiod&to=config_maps/istio-system.istio} */
	@GetMapping("/path")
	public List<ResourceRef> path(@RequestParam String from, @RequestParam String to) {
		return pods.path(from, to);
	}
}
