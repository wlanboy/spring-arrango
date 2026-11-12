package com.example.arrango.k8s;

import com.example.arrango.model.k8s.ConfigMap;
import com.example.arrango.model.k8s.K8sResource;
import com.example.arrango.model.k8s.Pod;
import com.example.arrango.model.k8s.Secret;
import com.example.arrango.model.k8s.Service;
import com.example.arrango.repository.k8s.ChangeReport;
import com.example.arrango.repository.k8s.ConfigMapRepository;
import com.example.arrango.repository.k8s.ConfigUsage;
import com.example.arrango.repository.k8s.HelmReleaseRepository;
import com.example.arrango.repository.k8s.ImageUsage;
import com.example.arrango.repository.k8s.NodeLoad;
import com.example.arrango.repository.k8s.NodeRepository;
import com.example.arrango.repository.k8s.PodRepository;
import com.example.arrango.repository.k8s.ResourceRef;
import com.example.arrango.repository.k8s.SecretRepository;
import com.example.arrango.repository.k8s.ServiceRepository;
import com.example.arrango.repository.k8s.UnhealthyReport;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Abfragen auf den Kubernetes-Graph. Bezieht sich ein Endpoint auf eine bestimmte Ressource oder einen Namespace,
 * der nicht im Graph steht, antwortet er mit 404 statt mit einer leeren Liste.
 */
@RestController
@RequestMapping("/api/k8s")
public class K8sGraphController {

	private final ClusterGraphImporter importer;
	private final K8sQueries queries;
	private final HelmReleaseRepository releases;
	private final ConfigMapRepository configMaps;
	private final SecretRepository secrets;
	private final PodRepository pods;
	private final ServiceRepository services;
	private final NodeRepository nodes;

	/** {@code @Lazy}, damit die Repositories erst beim ersten Request entstehen (siehe {@code ArangoConfig}). */
	public K8sGraphController(ClusterGraphImporter importer, K8sQueries queries,
			@Lazy HelmReleaseRepository releases, @Lazy ConfigMapRepository configMaps,
			@Lazy SecretRepository secrets, @Lazy PodRepository pods, @Lazy ServiceRepository services,
			@Lazy NodeRepository nodes) {
		this.importer = importer;
		this.queries = queries;
		this.releases = releases;
		this.configMaps = configMaps;
		this.secrets = secrets;
		this.pods = pods;
		this.services = services;
		this.nodes = nodes;
	}

	@PostMapping("/import")
	public ImportResult importCluster() {
		return importer.importCluster();
	}

	@GetMapping("/releases/{namespace}/{name}/resources")
	public List<ResourceRef> releaseResources(@PathVariable String namespace, @PathVariable String name) {
		requireExists(ResourceType.HELM_RELEASES, namespace, name);
		return releases.resources(namespace, name);
	}

	@GetMapping("/releases/{namespace}/{name}/config")
	public List<ResourceRef> releaseConfig(@PathVariable String namespace, @PathVariable String name) {
		requireExists(ResourceType.HELM_RELEASES, namespace, name);
		return releases.configDependencies(namespace, name);
	}

	@GetMapping("/configmaps/{namespace}/{name}/consumers")
	public List<ConfigUsage> configMapConsumers(@PathVariable String namespace, @PathVariable String name) {
		requireExists(ResourceType.CONFIG_MAPS, namespace, name);
		return configMaps.consumers(ResourceType.CONFIG_MAPS.collection(), namespace, name);
	}

	@GetMapping("/secrets/{namespace}/{name}/consumers")
	public List<ConfigUsage> secretConsumers(@PathVariable String namespace, @PathVariable String name) {
		requireExists(ResourceType.SECRETS, namespace, name);
		return configMaps.consumers(ResourceType.SECRETS.collection(), namespace, name);
	}

	@GetMapping("/configmaps/unused")
	public List<ConfigMap> unusedConfigMaps() {
		return configMaps.unused();
	}

	@GetMapping("/secrets/unused")
	public List<Secret> unusedSecrets() {
		return secrets.unused();
	}

	@GetMapping("/services/{namespace}/{name}/backends")
	public List<Pod> serviceBackends(@PathVariable String namespace, @PathVariable String name) {
		requireExists(ResourceType.SERVICES, namespace, name);
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
		for (var id : List.of(from, to)) {
			if (!queries.exists(id)) {
				throw notFound(id);
			}
		}
		return pods.path(from, to);
	}

	/** Workloads mit {@code ready < desired}, fehlgeschlagene Jobs und Pods, die weder laufen noch fertig sind. */
	@GetMapping("/unhealthy")
	public UnhealthyReport unhealthy(@RequestParam(required = false) String namespace) {
		return queries.unhealthy(requireNamespace(namespace));
	}

	/** Alle Images laufender Pods mit Anzahl der Pods und Namespaces. */
	@GetMapping("/images")
	public List<ImageUsage> images(@RequestParam(required = false) String namespace) {
		return queries.images(requireNamespace(namespace));
	}

	/** z. B. {@code /api/k8s/images/pods?image=nginx}; ohne Tag oder Digest passt jede Version. */
	@GetMapping("/images/pods")
	public List<Pod> podsByImage(@RequestParam String image, @RequestParam(required = false) String namespace) {
		return queries.podsByImage(image, requireNamespace(namespace));
	}

	/**
	 * Was seit {@code since} neu dazugekommen ist und was seitdem kein Import mehr gesehen hat.
	 * {@code since} als Zeitpunkt ({@code 2026-11-12T08:00:00Z}, {@code 2026-11-12T09:00:00+01:00}) oder Datum
	 * ({@code 2026-11-12}, Tagesbeginn UTC).
	 */
	@GetMapping("/changes")
	public ChangeReport changes(@RequestParam String since, @RequestParam(required = false) String namespace) {
		return queries.changes(parseSince(since), requireNamespace(namespace));
	}

	/** Liste eines Typs, z. B. {@code /api/k8s/pods?namespace=default}. */
	@GetMapping("/{type}")
	public List<? extends K8sResource> list(@PathVariable String type,
			@RequestParam(required = false) String namespace) {
		var resourceType = type(type);
		if (resourceType.clusterScoped() && namespace != null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, type + " sind cluster-weit, ohne namespace abfragen");
		}
		return queries.list(resourceType, requireNamespace(namespace));
	}

	/** Cluster-weite Ressource, z. B. {@code /api/k8s/nodes/worker-1}. */
	@GetMapping("/{type}/{name}")
	public K8sResource clusterScoped(@PathVariable String type, @PathVariable String name) {
		var resourceType = type(type);
		if (!resourceType.clusterScoped()) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND,
					type + " liegen in einem Namespace: /api/k8s/" + type + "/<namespace>/" + name);
		}
		return queries.find(resourceType, null, name).orElseThrow(() -> notFound(resourceType, null, name));
	}

	/** Ressource in einem Namespace, z. B. {@code /api/k8s/pods/kube-system/coredns-abc}. */
	@GetMapping("/{type}/{namespace}/{name}")
	public K8sResource namespaced(@PathVariable String type, @PathVariable String namespace,
			@PathVariable String name) {
		var resourceType = type(type);
		if (resourceType.clusterScoped()) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND,
					type + " sind cluster-weit: /api/k8s/" + type + "/<name>");
		}
		return queries.find(resourceType, namespace, name).orElseThrow(() -> notFound(resourceType, namespace, name));
	}

	private static ResourceType type(String path) {
		return ResourceType.fromPath(path)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unbekannter Typ: " + path));
	}

	private void requireExists(ResourceType type, String namespace, String name) {
		if (!queries.exists(type, namespace, name)) {
			throw notFound(type, namespace, name);
		}
	}

	/** Gibt {@code namespace} zurück, wenn er {@code null} ist oder im Graph steht, sonst 404. */
	private String requireNamespace(String namespace) {
		if (namespace != null) {
			requireExists(ResourceType.NAMESPACES, null, namespace);
		}
		return namespace;
	}

	private static ResponseStatusException notFound(ResourceType type, String namespace, String name) {
		return notFound(type.collection() + "/" + K8sResource.keyOf(namespace, name));
	}

	private static ResponseStatusException notFound(String id) {
		return new ResponseStatusException(HttpStatus.NOT_FOUND, id + " nicht gefunden");
	}

	private static Instant parseSince(String since) {
		try {
			if (since.length() == 10) {
				return LocalDate.parse(since).atStartOfDay(ZoneOffset.UTC).toInstant();
			}
			return OffsetDateTime.parse(since).toInstant();
		}
		catch (DateTimeParseException e) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"since ist kein ISO-8601-Zeitpunkt oder -Datum: " + since);
		}
	}
}
