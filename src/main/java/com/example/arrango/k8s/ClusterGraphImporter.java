package com.example.arrango.k8s;

import com.arangodb.ArangoDatabase;
import com.arangodb.entity.EdgeDefinition;
import com.arangodb.springframework.core.ArangoOperations;
import com.example.arrango.config.ArangoProperties;
import com.example.arrango.model.k8s.Binds;
import com.example.arrango.model.k8s.ConfigMap;
import com.example.arrango.model.k8s.DaemonSet;
import com.example.arrango.model.k8s.Deployment;
import com.example.arrango.model.k8s.HelmRelease;
import com.example.arrango.model.k8s.HelmRevision;
import com.example.arrango.model.k8s.K8sEdge;
import com.example.arrango.model.k8s.K8sResource;
import com.example.arrango.model.k8s.Manages;
import com.example.arrango.model.k8s.Mounts;
import com.example.arrango.model.k8s.Node;
import com.example.arrango.model.k8s.Owns;
import com.example.arrango.model.k8s.PersistentVolume;
import com.example.arrango.model.k8s.PersistentVolumeClaim;
import com.example.arrango.model.k8s.Pod;
import com.example.arrango.model.k8s.References;
import com.example.arrango.model.k8s.ReplicaSet;
import com.example.arrango.model.k8s.ScheduledOn;
import com.example.arrango.model.k8s.Secret;
import com.example.arrango.model.k8s.Selects;
import com.example.arrango.model.k8s.Service;
import com.example.arrango.model.k8s.ServiceAccount;
import com.example.arrango.model.k8s.StatefulSet;
import com.example.arrango.model.k8s.UsesServiceAccount;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.EnvFromSource;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.VolumeProjection;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * Liest den aktuellen Zustand eines Kubernetes-Clusters und gleicht ihn mit dem Graph in ArangoDB ab.
 * <p>
 * Ein Import löscht nicht den alten Stand, sondern arbeitet inkrementell:
 * <ul>
 * <li>Vertices und Kanten haben stabile Keys und werden per {@code UPSERT} angelegt oder aktualisiert.
 * {@code firstSeen} bleibt erhalten, {@code lastSeen} bekommt den Zeitstempel des Laufs.</li>
 * <li>Entfernt wird nur, was der Lauf nicht mehr gesehen hat, und nur in seinem Scope: bei gesetztem
 * {@code k8s.import.namespaces} bleiben andere Namespaces unangetastet.</li>
 * <li>Helm-Releases sammeln ihre Revisionen in {@code history}, auch über die von Helm aufbewahrten Secrets
 * hinaus.</li>
 * </ul>
 */
@Component
public class ClusterGraphImporter {

	private static final Logger log = LoggerFactory.getLogger(ClusterGraphImporter.class);

	/** Named Graph über alle Collections; wird in {@code PodRepository#path} per Name referenziert. */
	public static final String GRAPH = "k8s";

	private static final String HELM_SECRET_TYPE = "helm.sh/release.v1";

	private static final List<Class<? extends K8sResource>> VERTEX_TYPES = List.of(
			HelmRelease.class, Deployment.class, StatefulSet.class, DaemonSet.class, ReplicaSet.class, Pod.class,
			Service.class, ConfigMap.class, Secret.class, ServiceAccount.class, Node.class,
			PersistentVolumeClaim.class, PersistentVolume.class);

	private static final List<Class<? extends K8sEdge>> EDGE_TYPES = List.of(
			Manages.class, Owns.class, Mounts.class, References.class, Selects.class, ScheduledOn.class,
			UsesServiceAccount.class, Binds.class);

	/**
	 * Legt neue Dokumente an oder ersetzt vorhandene. {@code firstSeen} überlebt das Ersetzen; {@code %s} ist Platz
	 * für typ-spezifische Felder, die mit dem alten Stand zusammengeführt werden.
	 */
	private static final String UPSERT = """
			FOR d IN @docs
			  UPSERT { _key: d._key }
			  INSERT MERGE(d, { firstSeen: @now, lastSeen: @now })
			  REPLACE MERGE(d, { firstSeen: OLD.firstSeen || @now, lastSeen: @now }%s)
			  IN @@collection
			  RETURN OLD == null
			""";

	/** Neue Revisionen gewinnen, ältere aus früheren Importen bleiben stehen. */
	private static final String MERGE_HELM_HISTORY = """
			, { history: (
			    FOR h IN APPEND(d.history, (FOR o IN OLD.history || [] FILTER o.revision NOT IN d.history[*].revision RETURN o))
			      SORT h.revision DESC
			      RETURN h) }""";

	/** Entfernt, was dieser Lauf nicht gesehen hat; cluster-weite Dokumente ({@code namespace == null}) immer. */
	private static final String REMOVE_STALE = """
			FOR d IN @@collection
			  FILTER d.lastSeen != @now
			  FILTER @allNamespaces OR d.namespace == null OR d.namespace IN @namespaces
			  REMOVE d IN @@collection
			  RETURN 1
			""";

	/** ownerReferences.kind → Vertex-Collection. */
	private static final Map<String, String> OWNER_COLLECTIONS = Map.of(
			"Deployment", "deployments",
			"StatefulSet", "stateful_sets",
			"DaemonSet", "daemon_sets",
			"ReplicaSet", "replica_sets");

	private final KubernetesClient client;
	private final ArangoOperations operations;
	private final ArangoProperties arangoProperties;
	private final K8sImportProperties properties;

	public ClusterGraphImporter(KubernetesClient client, ArangoOperations operations,
			ArangoProperties arangoProperties, K8sImportProperties properties) {
		this.client = client;
		this.operations = operations;
		this.arangoProperties = arangoProperties;
		this.properties = properties;
	}

	@EventListener(ApplicationReadyEvent.class)
	void importOnStartup() {
		if (properties.onStartup()) {
			log.info("Import aus Kubernetes-Kontext '{}': {}", client.getConfiguration().getCurrentContext().getName(),
					importCluster());
		}
	}

	public synchronized ImportResult importCluster() {
		prepareCollections();
		var snapshot = new Snapshot();

		list(ns -> client.nodes().list().getItems(), () -> client.nodes().list().getItems(), true)
				.forEach(n -> snapshot.add(toNode(n), n.getMetadata()));
		list(ns -> client.persistentVolumes().list().getItems(), () -> client.persistentVolumes().list().getItems(), true)
				.forEach(pv -> snapshot.add(toPersistentVolume(pv), pv.getMetadata()));

		importHelmReleases(snapshot);

		list(ns -> client.apps().deployments().inNamespace(ns).list().getItems(),
				() -> client.apps().deployments().inAnyNamespace().list().getItems(), false)
				.forEach(d -> snapshot.add(toDeployment(d), d.getMetadata()));
		list(ns -> client.apps().statefulSets().inNamespace(ns).list().getItems(),
				() -> client.apps().statefulSets().inAnyNamespace().list().getItems(), false)
				.forEach(s -> snapshot.add(toStatefulSet(s), s.getMetadata()));
		list(ns -> client.apps().daemonSets().inNamespace(ns).list().getItems(),
				() -> client.apps().daemonSets().inAnyNamespace().list().getItems(), false)
				.forEach(d -> snapshot.add(toDaemonSet(d), d.getMetadata()));
		list(ns -> client.apps().replicaSets().inNamespace(ns).list().getItems(),
				() -> client.apps().replicaSets().inAnyNamespace().list().getItems(), false)
				.forEach(r -> snapshot.add(toReplicaSet(r), r.getMetadata()));
		list(ns -> client.configMaps().inNamespace(ns).list().getItems(),
				() -> client.configMaps().inAnyNamespace().list().getItems(), false)
				.forEach(c -> snapshot.add(toConfigMap(c), c.getMetadata()));
		list(ns -> client.secrets().inNamespace(ns).list().getItems(),
				() -> client.secrets().inAnyNamespace().list().getItems(), false).stream()
				.filter(s -> !HELM_SECRET_TYPE.equals(s.getType()))
				.forEach(s -> snapshot.add(toSecret(s), s.getMetadata()));
		list(ns -> client.serviceAccounts().inNamespace(ns).list().getItems(),
				() -> client.serviceAccounts().inAnyNamespace().list().getItems(), false)
				.forEach(sa -> snapshot.add(toServiceAccount(sa), sa.getMetadata()));
		var pvcs = list(ns -> client.persistentVolumeClaims().inNamespace(ns).list().getItems(),
				() -> client.persistentVolumeClaims().inAnyNamespace().list().getItems(), false);
		pvcs.forEach(pvc -> snapshot.add(toPersistentVolumeClaim(pvc), pvc.getMetadata()));
		var pods = list(ns -> client.pods().inNamespace(ns).list().getItems(),
				() -> client.pods().inAnyNamespace().list().getItems(), false);
		pods.forEach(p -> snapshot.add(toPod(p), p.getMetadata()));
		var services = list(ns -> client.services().inNamespace(ns).list().getItems(),
				() -> client.services().inAnyNamespace().list().getItems(), false);
		services.forEach(s -> snapshot.add(toService(s), s.getMetadata()));

		pods.forEach(p -> podEdges(p, snapshot));
		services.forEach(s -> serviceEdges(s, pods, snapshot));
		pvcs.stream()
				.filter(pvc -> pvc.getSpec().getVolumeName() != null)
				.forEach(pvc -> snapshot.edge(new Binds(
						id("persistent_volume_claims", pvc.getMetadata()),
						"persistent_volumes/" + pvc.getSpec().getVolumeName())));

		return snapshot.save();
	}

	// ---------------------------------------------------------------- Helm

	private void importHelmReleases(Snapshot snapshot) {
		var byRelease = list(ns -> client.secrets().inNamespace(ns).withLabel("owner", "helm").list().getItems(),
				() -> client.secrets().inAnyNamespace().withLabel("owner", "helm").list().getItems(), false).stream()
				.filter(s -> HELM_SECRET_TYPE.equals(s.getType()))
				.collect(Collectors.groupingBy(
						s -> K8sResource.keyOf(s.getMetadata().getNamespace(), s.getMetadata().getLabels().get("name")),
						LinkedHashMap::new, Collectors.toList()));

		for (var secrets : byRelease.values()) {
			var history = secrets.stream()
					.map(this::toRevision)
					.sorted(Comparator.comparingInt(HelmRevision::getRevision).reversed())
					.toList();
			var latest = history.getFirst();
			var meta = secrets.stream().max(Comparator.comparingInt(ClusterGraphImporter::revision)).orElseThrow()
					.getMetadata();
			var release = new HelmRelease(meta.getNamespace(), meta.getLabels().get("name"));
			release.setRevision(latest.getRevision());
			release.setStatus(latest.getStatus());
			release.setChart(latest.getChart());
			release.setChartVersion(latest.getChartVersion());
			release.setAppVersion(latest.getAppVersion());
			release.setCreatedAt(meta.getCreationTimestamp());
			release.setLabels(Map.of());
			release.setHistory(history);
			snapshot.add(release, null);
		}
	}

	private HelmRevision toRevision(io.fabric8.kubernetes.api.model.Secret secret) {
		var revision = new HelmRevision();
		revision.setRevision(revision(secret));
		revision.setStatus(secret.getMetadata().getLabels().get("status"));
		revision.setDeployedAt(secret.getMetadata().getCreationTimestamp());
		decodeChart(secret).ifPresent(chart -> {
			revision.setChart(Objects.toString(chart.get("name"), null));
			revision.setChartVersion(Objects.toString(chart.get("version"), null));
			revision.setAppVersion(Objects.toString(chart.get("appVersion"), null));
		});
		return revision;
	}

	private static int revision(io.fabric8.kubernetes.api.model.Secret secret) {
		return Integer.parseInt(secret.getMetadata().getLabels().getOrDefault("version", "0"));
	}

	/**
	 * Helm speichert das Release als base64(gzip(json)); Kubernetes legt darüber noch einmal base64.
	 */
	@SuppressWarnings("unchecked")
	private Optional<Map<String, Object>> decodeChart(io.fabric8.kubernetes.api.model.Secret secret) {
		var data = secret.getData() == null ? null : secret.getData().get("release");
		if (data == null) {
			return Optional.empty();
		}
		try {
			byte[] helmPayload = Base64.getDecoder().decode(Base64.getDecoder().decode(data));
			boolean gzipped = helmPayload.length > 2 && helmPayload[0] == (byte) 0x1f && helmPayload[1] == (byte) 0x8b;
			InputStream in = gzipped
					? new GZIPInputStream(new ByteArrayInputStream(helmPayload))
					: new ByteArrayInputStream(helmPayload);
			String json;
			try (in) {
				json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			}
			Map<String, Object> release = client.getKubernetesSerialization().unmarshal(json, Map.class);
			var chart = (Map<String, Object>) release.get("chart");
			return Optional.ofNullable(chart).map(c -> (Map<String, Object>) c.get("metadata"));
		}
		catch (IOException | RuntimeException e) {
			log.warn("Helm-Release {} nicht lesbar: {}", secret.getMetadata().getName(), e.getMessage());
			return Optional.empty();
		}
	}

	// ---------------------------------------------------------------- Mapping

	private static Node toNode(io.fabric8.kubernetes.api.model.Node n) {
		var node = new Node(null, n.getMetadata().getName());
		node.setKubeletVersion(n.getStatus().getNodeInfo().getKubeletVersion());
		node.setInternalIp(n.getStatus().getAddresses().stream()
				.filter(a -> "InternalIP".equals(a.getType())).map(a -> a.getAddress()).findFirst().orElse(null));
		node.setRoles(n.getMetadata().getLabels().keySet().stream()
				.filter(l -> l.startsWith("node-role.kubernetes.io/"))
				.map(l -> l.substring("node-role.kubernetes.io/".length())).toList());
		return node;
	}

	private static PersistentVolume toPersistentVolume(io.fabric8.kubernetes.api.model.PersistentVolume pv) {
		var volume = new PersistentVolume(null, pv.getMetadata().getName());
		volume.setCapacity(quantity(pv.getSpec().getCapacity(), "storage"));
		volume.setStorageClass(pv.getSpec().getStorageClassName());
		volume.setReclaimPolicy(pv.getSpec().getPersistentVolumeReclaimPolicy());
		volume.setPhase(pv.getStatus() == null ? null : pv.getStatus().getPhase());
		return volume;
	}

	private static Deployment toDeployment(io.fabric8.kubernetes.api.model.apps.Deployment d) {
		var deployment = new Deployment(d.getMetadata().getNamespace(), d.getMetadata().getName());
		deployment.setReplicas(orZero(d.getSpec().getReplicas()));
		deployment.setReadyReplicas(orZero(d.getStatus().getReadyReplicas()));
		deployment.setSelector(d.getSpec().getSelector().getMatchLabels());
		deployment.setImages(images(d.getSpec().getTemplate().getSpec()));
		return deployment;
	}

	private static StatefulSet toStatefulSet(io.fabric8.kubernetes.api.model.apps.StatefulSet s) {
		var statefulSet = new StatefulSet(s.getMetadata().getNamespace(), s.getMetadata().getName());
		statefulSet.setReplicas(orZero(s.getSpec().getReplicas()));
		statefulSet.setReadyReplicas(orZero(s.getStatus().getReadyReplicas()));
		statefulSet.setSelector(s.getSpec().getSelector().getMatchLabels());
		statefulSet.setImages(images(s.getSpec().getTemplate().getSpec()));
		return statefulSet;
	}

	private static DaemonSet toDaemonSet(io.fabric8.kubernetes.api.model.apps.DaemonSet d) {
		var daemonSet = new DaemonSet(d.getMetadata().getNamespace(), d.getMetadata().getName());
		daemonSet.setDesired(orZero(d.getStatus().getDesiredNumberScheduled()));
		daemonSet.setReady(orZero(d.getStatus().getNumberReady()));
		daemonSet.setSelector(d.getSpec().getSelector().getMatchLabels());
		daemonSet.setImages(images(d.getSpec().getTemplate().getSpec()));
		return daemonSet;
	}

	private static ReplicaSet toReplicaSet(io.fabric8.kubernetes.api.model.apps.ReplicaSet r) {
		var replicaSet = new ReplicaSet(r.getMetadata().getNamespace(), r.getMetadata().getName());
		replicaSet.setReplicas(orZero(r.getSpec().getReplicas()));
		replicaSet.setReadyReplicas(orZero(r.getStatus().getReadyReplicas()));
		return replicaSet;
	}

	private static ConfigMap toConfigMap(io.fabric8.kubernetes.api.model.ConfigMap c) {
		var configMap = new ConfigMap(c.getMetadata().getNamespace(), c.getMetadata().getName());
		configMap.setDataKeys(keys(c.getData(), c.getBinaryData()));
		return configMap;
	}

	private static Secret toSecret(io.fabric8.kubernetes.api.model.Secret s) {
		var secret = new Secret(s.getMetadata().getNamespace(), s.getMetadata().getName());
		secret.setType(s.getType());
		secret.setDataKeys(keys(s.getData(), s.getStringData()));
		return secret;
	}

	private static ServiceAccount toServiceAccount(io.fabric8.kubernetes.api.model.ServiceAccount sa) {
		return new ServiceAccount(sa.getMetadata().getNamespace(), sa.getMetadata().getName());
	}

	private static PersistentVolumeClaim toPersistentVolumeClaim(io.fabric8.kubernetes.api.model.PersistentVolumeClaim p) {
		var claim = new PersistentVolumeClaim(p.getMetadata().getNamespace(), p.getMetadata().getName());
		claim.setStorageClass(p.getSpec().getStorageClassName());
		claim.setRequestedStorage(p.getSpec().getResources() == null ? null
				: quantity(p.getSpec().getResources().getRequests(), "storage"));
		claim.setPhase(p.getStatus() == null ? null : p.getStatus().getPhase());
		claim.setVolumeName(p.getSpec().getVolumeName());
		return claim;
	}

	private static Pod toPod(io.fabric8.kubernetes.api.model.Pod p) {
		var pod = new Pod(p.getMetadata().getNamespace(), p.getMetadata().getName());
		pod.setPhase(p.getStatus().getPhase());
		pod.setNodeName(p.getSpec().getNodeName());
		pod.setPodIp(p.getStatus().getPodIP());
		pod.setImages(images(p.getSpec()));
		return pod;
	}

	private static Service toService(io.fabric8.kubernetes.api.model.Service s) {
		var service = new Service(s.getMetadata().getNamespace(), s.getMetadata().getName());
		service.setType(s.getSpec().getType());
		service.setClusterIp(s.getSpec().getClusterIP());
		service.setExternalName(s.getSpec().getExternalName());
		service.setSelector(s.getSpec().getSelector());
		service.setPorts(s.getSpec().getPorts().stream()
				.map(p -> p.getPort() + "/" + p.getProtocol() + (p.getName() == null ? "" : " (" + p.getName() + ")"))
				.toList());
		return service;
	}

	// ---------------------------------------------------------------- Kanten

	private static void podEdges(io.fabric8.kubernetes.api.model.Pod p, Snapshot snapshot) {
		var meta = p.getMetadata();
		var ns = meta.getNamespace();
		var podId = id("pods", meta);
		var spec = p.getSpec();

		if (spec.getNodeName() != null) {
			snapshot.edge(new ScheduledOn(podId, "nodes/" + spec.getNodeName()));
		}
		if (spec.getServiceAccountName() != null) {
			snapshot.edge(new UsesServiceAccount(podId, "service_accounts/" + K8sResource.keyOf(ns, spec.getServiceAccountName())));
		}

		var containers = Stream.concat(spec.getInitContainers().stream(), spec.getContainers().stream()).toList();
		for (Volume volume : spec.getVolumes()) {
			var mountPaths = containers.stream()
					.flatMap(c -> c.getVolumeMounts().stream())
					.filter(m -> volume.getName().equals(m.getName()))
					.map(VolumeMount::getMountPath)
					.distinct()
					.toList();
			if (volume.getConfigMap() != null) {
				snapshot.edge(mount(podId, "config_maps", ns, volume.getConfigMap().getName(), volume, "configMap",
						mountPaths, volume.getConfigMap().getOptional()));
			}
			if (volume.getSecret() != null) {
				snapshot.edge(mount(podId, "secrets", ns, volume.getSecret().getSecretName(), volume, "secret",
						mountPaths, volume.getSecret().getOptional()));
			}
			if (volume.getPersistentVolumeClaim() != null) {
				snapshot.edge(mount(podId, "persistent_volume_claims", ns, volume.getPersistentVolumeClaim().getClaimName(),
						volume, "persistentVolumeClaim", mountPaths, false));
			}
			if (volume.getProjected() != null) {
				for (VolumeProjection source : volume.getProjected().getSources()) {
					if (source.getConfigMap() != null) {
						snapshot.edge(mount(podId, "config_maps", ns, source.getConfigMap().getName(), volume, "projected",
								mountPaths, source.getConfigMap().getOptional()));
					}
					if (source.getSecret() != null) {
						snapshot.edge(mount(podId, "secrets", ns, source.getSecret().getName(), volume, "projected",
								mountPaths, source.getSecret().getOptional()));
					}
				}
			}
		}

		for (Container container : containers) {
			for (EnvVar env : container.getEnv()) {
				var from = env.getValueFrom();
				if (from != null && from.getConfigMapKeyRef() != null) {
					snapshot.edge(envRef(podId, "config_maps", ns, from.getConfigMapKeyRef().getName(), env.getName(),
							from.getConfigMapKeyRef().getKey()));
				}
				if (from != null && from.getSecretKeyRef() != null) {
					snapshot.edge(envRef(podId, "secrets", ns, from.getSecretKeyRef().getName(), env.getName(),
							from.getSecretKeyRef().getKey()));
				}
			}
			for (EnvFromSource envFrom : container.getEnvFrom()) {
				if (envFrom.getConfigMapRef() != null) {
					snapshot.edge(envFromRef(podId, "config_maps", ns, envFrom.getConfigMapRef().getName()));
				}
				if (envFrom.getSecretRef() != null) {
					snapshot.edge(envFromRef(podId, "secrets", ns, envFrom.getSecretRef().getName()));
				}
			}
		}
	}

	private static void serviceEdges(io.fabric8.kubernetes.api.model.Service s,
			List<io.fabric8.kubernetes.api.model.Pod> pods, Snapshot snapshot) {
		var selector = s.getSpec().getSelector();
		if (selector == null || selector.isEmpty()) {
			return;
		}
		var serviceId = id("services", s.getMetadata());
		pods.stream()
				.filter(p -> p.getMetadata().getNamespace().equals(s.getMetadata().getNamespace()))
				.filter(p -> p.getMetadata().getLabels() != null
						&& p.getMetadata().getLabels().entrySet().containsAll(selector.entrySet()))
				.forEach(p -> snapshot.edge(new Selects(serviceId, id("pods", p.getMetadata()))));
	}

	private static Mounts mount(String podId, String collection, String ns, String name, Volume volume, String source,
			List<String> mountPaths, Boolean optional) {
		var mounts = new Mounts(podId, collection + "/" + K8sResource.keyOf(ns, name));
		mounts.setVolumeName(volume.getName());
		mounts.setSource(source);
		mounts.setMountPaths(mountPaths);
		mounts.setOptional(Boolean.TRUE.equals(optional));
		return mounts;
	}

	private static References envRef(String podId, String collection, String ns, String name, String envVar, String key) {
		var ref = new References(podId, collection + "/" + K8sResource.keyOf(ns, name));
		ref.setEnvVar(envVar);
		ref.setKey(key);
		return ref;
	}

	private static References envFromRef(String podId, String collection, String ns, String name) {
		var ref = new References(podId, collection + "/" + K8sResource.keyOf(ns, name));
		ref.setEnvFrom(true);
		return ref;
	}

	// ---------------------------------------------------------------- Infrastruktur

	private void prepareCollections() {
		// legt fehlende Collections an
		Stream.concat(VERTEX_TYPES.stream(), EDGE_TYPES.stream()).forEach(operations::collection);

		ArangoDatabase db = operations.driver().db(arangoProperties.database());
		if (!db.graph(GRAPH).exists()) {
			var vertexCollections = VERTEX_TYPES.stream().map(t -> operations.collection(t).name()).toArray(String[]::new);
			var edgeDefinitions = EDGE_TYPES.stream()
					.map(t -> new EdgeDefinition()
							.collection(operations.collection(t).name())
							.from(vertexCollections)
							.to(vertexCollections))
					.toList();
			db.createGraph(GRAPH, edgeDefinitions);
		}
	}

	private <T extends HasMetadata> List<T> list(Function<String, List<T>> perNamespace, Supplier<List<T>> all,
			boolean clusterScoped) {
		if (clusterScoped || properties.namespaces().isEmpty()) {
			return all.get();
		}
		return properties.namespaces().stream().flatMap(ns -> perNamespace.apply(ns).stream()).toList();
	}

	private static String id(String collection, ObjectMeta meta) {
		return collection + "/" + K8sResource.keyOf(meta.getNamespace(), meta.getName());
	}

	private static int orZero(Integer value) {
		return value == null ? 0 : value;
	}

	private static String quantity(Map<String, Quantity> values, String key) {
		return values == null || values.get(key) == null ? null : values.get(key).toString();
	}

	private static List<String> images(PodSpec spec) {
		return Stream.concat(spec.getInitContainers().stream(), spec.getContainers().stream())
				.map(Container::getImage).distinct().toList();
	}

	@SafeVarargs
	private static List<String> keys(Map<String, String>... maps) {
		return Stream.of(maps).filter(Objects::nonNull).flatMap(m -> m.keySet().stream()).sorted().toList();
	}

	/** Sammelt Vertices und Kanten eines Imports und gleicht sie am Ende gebündelt mit der Datenbank ab. */
	private final class Snapshot {

		private final Map<Class<?>, List<Object>> documents = new LinkedHashMap<>();
		/** Dokument-ID → Namespace ({@code null} bei cluster-weiten Ressourcen). */
		private final Map<String, String> namespaces = new HashMap<>();
		private final List<K8sEdge> edges = new ArrayList<>();

		void add(K8sResource resource, ObjectMeta meta) {
			if (meta != null) {
				resource.setUid(meta.getUid());
				resource.setLabels(meta.getLabels() == null ? Map.of() : meta.getLabels());
				resource.setCreatedAt(meta.getCreationTimestamp());
				ownerEdges(resource, meta);
				helmEdge(resource, meta);
			}
			documents.computeIfAbsent(resource.getClass(), k -> new ArrayList<>()).add(resource);
			namespaces.put(operations.collection(resource.getClass()).name() + "/" + resource.getKey(),
					resource.getNamespace());
		}

		void edge(K8sEdge edge) {
			edges.add(edge);
		}

		private void ownerEdges(K8sResource resource, ObjectMeta meta) {
			if (meta.getOwnerReferences() == null) {
				return;
			}
			var targetId = operations.collection(resource.getClass()).name() + "/" + resource.getKey();
			for (OwnerReference owner : meta.getOwnerReferences()) {
				var collection = OWNER_COLLECTIONS.get(owner.getKind());
				if (collection != null) {
					var owns = new Owns(collection + "/" + K8sResource.keyOf(meta.getNamespace(), owner.getName()), targetId);
					owns.setController(Boolean.TRUE.equals(owner.getController()));
					edges.add(owns);
				}
			}
		}

		private void helmEdge(K8sResource resource, ObjectMeta meta) {
			var annotations = meta.getAnnotations();
			if (annotations == null || !annotations.containsKey("meta.helm.sh/release-name")) {
				return;
			}
			// Controller kopieren die Annotationen weiter (Deployment → ReplicaSet); verwaltet wird nur der Owner.
			if (meta.getOwnerReferences() != null
					&& meta.getOwnerReferences().stream().anyMatch(o -> Boolean.TRUE.equals(o.getController()))) {
				return;
			}
			var releaseKey = K8sResource.keyOf(annotations.get("meta.helm.sh/release-namespace"),
					annotations.get("meta.helm.sh/release-name"));
			edges.add(new Manages("helm_releases/" + releaseKey,
					operations.collection(resource.getClass()).name() + "/" + resource.getKey()));
		}

		ImportResult save() {
			var now = Instant.now().toString();
			var changes = new LinkedHashMap<String, ImportResult.Changes>();

			// Kanten auf Ressourcen, die nicht importiert wurden (z. B. optionales Secret, das fehlt), werden verworfen.
			var resolved = new LinkedHashMap<Class<?>, Map<String, Object>>();
			int dropped = 0;
			for (K8sEdge edge : edges) {
				if (!namespaces.containsKey(edge.getFrom()) || !namespaces.containsKey(edge.getTo())) {
					dropped++;
					continue;
				}
				edge.setKey(edgeKey(edge));
				edge.setNamespace(namespaces.get(edge.getFrom()));
				resolved.computeIfAbsent(edge.getClass(), k -> new LinkedHashMap<>()).putIfAbsent(edge.getKey(), edge);
			}
			if (dropped > 0) {
				log.debug("{} Kanten ohne importiertes Ziel verworfen", dropped);
			}

			for (var type : VERTEX_TYPES) {
				var extra = type == HelmRelease.class ? MERGE_HELM_HISTORY : "";
				sync(type, documents.getOrDefault(type, List.of()), extra, now, changes);
			}
			for (var type : EDGE_TYPES) {
				sync(type, resolved.getOrDefault(type, Map.of()).values(), "", now, changes);
			}
			return new ImportResult(now, changes);
		}

		private void sync(Class<?> type, Collection<?> docs, String extraMerge, String now,
				Map<String, ImportResult.Changes> changes) {
			var collection = operations.collection(type).name();
			var json = docs.stream().map(operations.getConverter()::write).toList();

			int inserted = 0;
			int updated = 0;
			if (!json.isEmpty()) {
				for (Boolean isNew : operations.query(UPSERT.formatted(extraMerge),
						Map.of("docs", json, "now", now, "@collection", collection), Boolean.class)) {
					if (isNew) {
						inserted++;
					}
					else {
						updated++;
					}
				}
			}
			int removed = operations.query(REMOVE_STALE,
					Map.of("now", now, "@collection", collection, "allNamespaces", properties.namespaces().isEmpty(),
							"namespaces", properties.namespaces()),
					Integer.class).asListRemaining().size();
			changes.put(collection, new ImportResult.Changes(inserted, updated, removed));
		}
	}

	/** Stabiler Key aus Typ, Endpunkten und {@link K8sEdge#identity()}. */
	private static String edgeKey(K8sEdge edge) {
		try {
			var digest = MessageDigest.getInstance("SHA-256").digest(
					String.join("\n", edge.getClass().getSimpleName(), edge.getFrom(), edge.getTo(), edge.identity())
							.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest, 0, 16);
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
