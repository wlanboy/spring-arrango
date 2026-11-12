package com.example.arrango.k8s;

import com.arangodb.ArangoDatabase;
import com.arangodb.entity.EdgeDefinition;
import com.arangodb.springframework.core.ArangoOperations;
import com.example.arrango.config.ArangoProperties;
import com.example.arrango.model.k8s.Binds;
import com.example.arrango.model.k8s.ConfigMap;
import com.example.arrango.model.k8s.Contains;
import com.example.arrango.model.k8s.CronJob;
import com.example.arrango.model.k8s.DaemonSet;
import com.example.arrango.model.k8s.Deployment;
import com.example.arrango.model.k8s.HelmRelease;
import com.example.arrango.model.k8s.HelmRevision;
import com.example.arrango.model.k8s.Job;
import com.example.arrango.model.k8s.K8sEdge;
import com.example.arrango.model.k8s.K8sResource;
import com.example.arrango.model.k8s.Manages;
import com.example.arrango.model.k8s.Mounts;
import com.example.arrango.model.k8s.Namespace;
import com.example.arrango.model.k8s.Node;
import com.example.arrango.model.k8s.Owns;
import com.example.arrango.model.k8s.PersistentVolume;
import com.example.arrango.model.k8s.PersistentVolumeClaim;
import com.example.arrango.model.k8s.Pod;
import com.example.arrango.model.k8s.References;
import com.example.arrango.model.k8s.ReplicaSet;
import com.example.arrango.model.k8s.ScheduledOn;
import com.example.arrango.model.k8s.Secret;
import com.example.arrango.model.k8s.SelectorRequirement;
import com.example.arrango.model.k8s.Selects;
import com.example.arrango.model.k8s.Service;
import com.example.arrango.model.k8s.ServiceAccount;
import com.example.arrango.model.k8s.StatefulSet;
import com.example.arrango.model.k8s.StorageClass;
import com.example.arrango.model.k8s.UsesServiceAccount;
import com.example.arrango.model.k8s.UsesStorageClass;
import io.fabric8.kubernetes.api.model.batch.v1.JobCondition;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.EnvFromSource;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.LabelSelector;
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
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntSupplier;
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

	private static final List<Class<? extends K8sResource>> VERTEX_TYPES = ResourceType.entityClasses();

	private static final List<Class<? extends K8sEdge>> EDGE_TYPES = List.of(
			Contains.class, Manages.class, Owns.class, Mounts.class, References.class, Selects.class,
			ScheduledOn.class, UsesServiceAccount.class, Binds.class, UsesStorageClass.class);

	private static final String DEFAULT_STORAGE_CLASS = "storageclass.kubernetes.io/is-default-class";

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

	/**
	 * Entfernt Vertices, die dieser Lauf nicht gesehen hat; cluster-weite Dokumente ({@code namespace == null}) immer.
	 * Namespaces, die der Lauf nicht lesen konnte ({@code @failedNamespaces}), bleiben stehen.
	 */
	private static final String REMOVE_STALE_VERTICES = """
			FOR d IN @@collection
			  FILTER d.lastSeen != @now
			  FILTER @allNamespaces OR d.namespace == null OR d.namespace IN @namespaces
			  FILTER d.namespace NOT IN @failedNamespaces
			  REMOVE d IN @@collection
			  RETURN 1
			""";

	/**
	 * Wie {@link #REMOVE_STALE_VERTICES} für Kanten. Kanten, deren Start oder Ziel in einer nicht lesbaren
	 * Collection ({@code @failedCollections}) oder einem nicht lesbaren Namespace einer Collection
	 * ({@code @failedScopes}, Form {@code <collection>/<namespace>}) liegt, bleiben stehen.
	 */
	private static final String REMOVE_STALE_EDGES = """
			FOR d IN @@collection
			  FILTER d.lastSeen != @now
			  FILTER @allNamespaces OR d.namespace == null OR d.namespace IN @namespaces
			  LET ends = [PARSE_IDENTIFIER(d._from).collection, PARSE_IDENTIFIER(d._to).collection]
			  FILTER LENGTH(INTERSECTION(ends, @failedCollections)) == 0
			  FILTER LENGTH(INTERSECTION(ends[* RETURN CONCAT(CURRENT, '/', d.namespace)], @failedScopes)) == 0
			  REMOVE d IN @@collection
			  RETURN 1
			""";

	/** ownerReferences.kind → Vertex-Collection. */
	private static final Map<String, String> OWNER_COLLECTIONS = Map.of(
			"Deployment", "deployments",
			"StatefulSet", "stateful_sets",
			"DaemonSet", "daemon_sets",
			"ReplicaSet", "replica_sets",
			"CronJob", "cron_jobs",
			"Job", "jobs");

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

	/**
	 * Läuft in einem eigenen Thread, damit ein langsamer oder nicht erreichbarer Cluster (fabric8 wiederholt
	 * fehlgeschlagene Requests mit Backoff) die Readiness nicht blockiert. Fehler werden nur geloggt: Die App soll
	 * auch ohne erreichbaren Cluster oder ArangoDB hochkommen, der Import lässt sich später über
	 * {@code POST /api/k8s/import} nachholen.
	 */
	@EventListener(ApplicationReadyEvent.class)
	void importOnStartup() {
		if (properties.onStartup()) {
			Thread.ofVirtual().name("k8s-startup-import").start(this::runStartupImport);
		}
	}

	private void runStartupImport() {
		try {
			var result = importCluster();
			log.info("Import aus Kubernetes-Kontext '{}': {}", contextName(), result.collections());
			result.failures().forEach(f -> log.warn("Nicht importiert: {}{}: {}", f.collection(),
					f.namespace() == null ? "" : " in " + f.namespace(), f.error()));
		}
		catch (RuntimeException e) {
			log.error("Import beim Start fehlgeschlagen, der Graph bleibt auf dem alten Stand", e);
		}
	}

	private String contextName() {
		var context = client.getConfiguration().getCurrentContext();
		return context != null ? context.getName() : client.getConfiguration().getMasterUrl();
	}

	public synchronized ImportResult importCluster() {
		prepareCollections();
		var snapshot = new Snapshot();

		// Namespaces immer vollständig: Sie sind cluster-weit und Start der contains-Kanten auch außerhalb des Scopes.
		load(snapshot, Namespace.class, true, null, () -> client.namespaces().list().getItems(),
				ClusterGraphImporter::toNamespace);
		load(snapshot, Node.class, true, null, () -> client.nodes().list().getItems(),
				ClusterGraphImporter::toNode);
		load(snapshot, StorageClass.class, true, null,
				() -> client.storage().v1().storageClasses().list().getItems(),
				ClusterGraphImporter::toStorageClass);
		var pvs = load(snapshot, PersistentVolume.class, true, null,
				() -> client.persistentVolumes().list().getItems(),
				ClusterGraphImporter::toPersistentVolume);

		importHelmReleases(snapshot);

		load(snapshot, Deployment.class, false,
				ns -> client.apps().deployments().inNamespace(ns).list().getItems(),
				() -> client.apps().deployments().inAnyNamespace().list().getItems(),
				ClusterGraphImporter::toDeployment);
		load(snapshot, StatefulSet.class, false,
				ns -> client.apps().statefulSets().inNamespace(ns).list().getItems(),
				() -> client.apps().statefulSets().inAnyNamespace().list().getItems(),
				ClusterGraphImporter::toStatefulSet);
		load(snapshot, DaemonSet.class, false,
				ns -> client.apps().daemonSets().inNamespace(ns).list().getItems(),
				() -> client.apps().daemonSets().inAnyNamespace().list().getItems(),
				ClusterGraphImporter::toDaemonSet);
		load(snapshot, ReplicaSet.class, false,
				ns -> client.apps().replicaSets().inNamespace(ns).list().getItems(),
				() -> client.apps().replicaSets().inAnyNamespace().list().getItems(),
				ClusterGraphImporter::toReplicaSet);
		load(snapshot, CronJob.class, false,
				ns -> client.batch().v1().cronjobs().inNamespace(ns).list().getItems(),
				() -> client.batch().v1().cronjobs().inAnyNamespace().list().getItems(),
				ClusterGraphImporter::toCronJob);
		load(snapshot, Job.class, false,
				ns -> client.batch().v1().jobs().inNamespace(ns).list().getItems(),
				() -> client.batch().v1().jobs().inAnyNamespace().list().getItems(),
				ClusterGraphImporter::toJob);
		load(snapshot, ConfigMap.class, false,
				ns -> client.configMaps().inNamespace(ns).list().getItems(),
				() -> client.configMaps().inAnyNamespace().list().getItems(),
				ClusterGraphImporter::toConfigMap);
		// Helm-Release-Secrets (owner=helm) gar nicht erst übertragen; die laufen über importHelmReleases.
		load(snapshot, Secret.class, false,
				ns -> client.secrets().inNamespace(ns).withoutLabel("owner", "helm").list().getItems(),
				() -> client.secrets().inAnyNamespace().withoutLabel("owner", "helm").list().getItems(),
				ClusterGraphImporter::toSecret);
		load(snapshot, ServiceAccount.class, false,
				ns -> client.serviceAccounts().inNamespace(ns).list().getItems(),
				() -> client.serviceAccounts().inAnyNamespace().list().getItems(),
				ClusterGraphImporter::toServiceAccount);
		var pvcs = load(snapshot, PersistentVolumeClaim.class, false,
				ns -> client.persistentVolumeClaims().inNamespace(ns).list().getItems(),
				() -> client.persistentVolumeClaims().inAnyNamespace().list().getItems(),
				ClusterGraphImporter::toPersistentVolumeClaim);
		var pods = load(snapshot, Pod.class, false,
				ns -> client.pods().inNamespace(ns).list().getItems(),
				() -> client.pods().inAnyNamespace().list().getItems(),
				ClusterGraphImporter::toPod);
		var services = load(snapshot, Service.class, false,
				ns -> client.services().inNamespace(ns).list().getItems(),
				() -> client.services().inAnyNamespace().list().getItems(),
				ClusterGraphImporter::toService);

		forEachSafely(snapshot, Pod.class, pods, p -> podEdges(p, snapshot));
		forEachSafely(snapshot, Service.class, services, s -> serviceEdges(s, pods, snapshot));
		forEachSafely(snapshot, PersistentVolumeClaim.class, pvcs, pvc -> {
			if (pvc.getSpec().getVolumeName() != null) {
				snapshot.edge(new Binds(id("persistent_volume_claims", pvc.getMetadata()),
						"persistent_volumes/" + pvc.getSpec().getVolumeName()));
			}
			storageClassEdge(id("persistent_volume_claims", pvc.getMetadata()), pvc.getSpec().getStorageClassName(),
					snapshot);
		});
		forEachSafely(snapshot, PersistentVolume.class, pvs, pv -> storageClassEdge(
				id("persistent_volumes", pv.getMetadata()), pv.getSpec().getStorageClassName(), snapshot));

		return snapshot.save();
	}

	// ---------------------------------------------------------------- Helm

	private void importHelmReleases(Snapshot snapshot) {
		var byRelease = fetch(snapshot, HelmRelease.class, false,
				ns -> client.secrets().inNamespace(ns).withLabel("owner", "helm").list().getItems(),
				() -> client.secrets().inAnyNamespace().withLabel("owner", "helm").list().getItems()).stream()
				.filter(s -> HELM_SECRET_TYPE.equals(s.getType()))
				.collect(Collectors.groupingBy(
						s -> K8sResource.keyOf(s.getMetadata().getNamespace(), s.getMetadata().getLabels().get("name")),
						LinkedHashMap::new, Collectors.toList()));

		forEachSafely(snapshot, HelmRelease.class, byRelease.values(), secrets -> {
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
		});
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

	private static Namespace toNamespace(io.fabric8.kubernetes.api.model.Namespace n) {
		var namespace = new Namespace(n.getMetadata().getName());
		namespace.setPhase(n.getStatus() == null ? null : n.getStatus().getPhase());
		return namespace;
	}

	private static StorageClass toStorageClass(io.fabric8.kubernetes.api.model.storage.StorageClass sc) {
		var storageClass = new StorageClass(sc.getMetadata().getName());
		storageClass.setProvisioner(sc.getProvisioner());
		storageClass.setReclaimPolicy(sc.getReclaimPolicy());
		storageClass.setVolumeBindingMode(sc.getVolumeBindingMode());
		storageClass.setAllowVolumeExpansion(Boolean.TRUE.equals(sc.getAllowVolumeExpansion()));
		var annotations = sc.getMetadata().getAnnotations();
		storageClass.setDefaultClass(annotations != null && "true".equals(annotations.get(DEFAULT_STORAGE_CLASS)));
		return storageClass;
	}

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
		deployment.setMatchExpressions(matchExpressions(d.getSpec().getSelector()));
		deployment.setImages(images(d.getSpec().getTemplate().getSpec()));
		return deployment;
	}

	private static StatefulSet toStatefulSet(io.fabric8.kubernetes.api.model.apps.StatefulSet s) {
		var statefulSet = new StatefulSet(s.getMetadata().getNamespace(), s.getMetadata().getName());
		statefulSet.setReplicas(orZero(s.getSpec().getReplicas()));
		statefulSet.setReadyReplicas(orZero(s.getStatus().getReadyReplicas()));
		statefulSet.setSelector(s.getSpec().getSelector().getMatchLabels());
		statefulSet.setMatchExpressions(matchExpressions(s.getSpec().getSelector()));
		statefulSet.setImages(images(s.getSpec().getTemplate().getSpec()));
		return statefulSet;
	}

	private static DaemonSet toDaemonSet(io.fabric8.kubernetes.api.model.apps.DaemonSet d) {
		var daemonSet = new DaemonSet(d.getMetadata().getNamespace(), d.getMetadata().getName());
		daemonSet.setDesired(orZero(d.getStatus().getDesiredNumberScheduled()));
		daemonSet.setReady(orZero(d.getStatus().getNumberReady()));
		daemonSet.setSelector(d.getSpec().getSelector().getMatchLabels());
		daemonSet.setMatchExpressions(matchExpressions(d.getSpec().getSelector()));
		daemonSet.setImages(images(d.getSpec().getTemplate().getSpec()));
		return daemonSet;
	}

	private static ReplicaSet toReplicaSet(io.fabric8.kubernetes.api.model.apps.ReplicaSet r) {
		var replicaSet = new ReplicaSet(r.getMetadata().getNamespace(), r.getMetadata().getName());
		replicaSet.setReplicas(orZero(r.getSpec().getReplicas()));
		replicaSet.setReadyReplicas(orZero(r.getStatus().getReadyReplicas()));
		return replicaSet;
	}

	private static CronJob toCronJob(io.fabric8.kubernetes.api.model.batch.v1.CronJob c) {
		var cronJob = new CronJob(c.getMetadata().getNamespace(), c.getMetadata().getName());
		var spec = c.getSpec();
		cronJob.setSchedule(spec.getSchedule());
		cronJob.setTimeZone(spec.getTimeZone());
		cronJob.setSuspend(Boolean.TRUE.equals(spec.getSuspend()));
		var status = c.getStatus();
		if (status != null) {
			cronJob.setLastScheduleTime(status.getLastScheduleTime());
			cronJob.setLastSuccessfulTime(status.getLastSuccessfulTime());
			cronJob.setActiveJobs(status.getActive() == null ? 0 : status.getActive().size());
		}
		cronJob.setImages(images(spec.getJobTemplate().getSpec().getTemplate().getSpec()));
		return cronJob;
	}

	private static Job toJob(io.fabric8.kubernetes.api.model.batch.v1.Job j) {
		var job = new Job(j.getMetadata().getNamespace(), j.getMetadata().getName());
		job.setCompletions(j.getSpec().getCompletions());
		job.setParallelism(j.getSpec().getParallelism());
		job.setImages(images(j.getSpec().getTemplate().getSpec()));
		var status = j.getStatus();
		if (status != null) {
			job.setActive(orZero(status.getActive()));
			job.setSucceeded(orZero(status.getSucceeded()));
			job.setFailed(orZero(status.getFailed()));
			job.setStartTime(status.getStartTime());
			job.setCompletionTime(status.getCompletionTime());
		}
		job.setStatus(jobStatus(j));
		return job;
	}

	/** Terminale Conditions zuerst; ohne sie läuft der Job (oder wartet auf seinen ersten Pod). */
	private static String jobStatus(io.fabric8.kubernetes.api.model.batch.v1.Job j) {
		var conditions = j.getStatus() == null || j.getStatus().getConditions() == null
				? List.<JobCondition>of() : j.getStatus().getConditions();
		for (var type : List.of("Complete", "Failed", "Suspended")) {
			if (conditions.stream().anyMatch(c -> type.equals(c.getType()) && "True".equals(c.getStatus()))) {
				return type;
			}
		}
		return "Running";
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

	/** Leerer {@code storageClassName} heißt in Kubernetes ausdrücklich "keine Klasse". */
	private static void storageClassEdge(String fromId, String storageClassName, Snapshot snapshot) {
		if (storageClassName != null && !storageClassName.isEmpty()) {
			snapshot.edge(new UsesStorageClass(fromId, "storage_classes/" + storageClassName));
		}
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

	private static References envRef(String podId, String collection, String ns, String name, String envVar,
			String dataKey) {
		var ref = new References(podId, collection + "/" + K8sResource.keyOf(ns, name));
		ref.setEnvVar(envVar);
		ref.setDataKey(dataKey);
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

		var vertexCollections = VERTEX_TYPES.stream().map(t -> operations.collection(t).name()).toArray(String[]::new);
		var edgeDefinitions = EDGE_TYPES.stream()
				.map(t -> new EdgeDefinition()
						.collection(operations.collection(t).name())
						.from(vertexCollections)
						.to(vertexCollections))
				.toList();

		ArangoDatabase db = operations.driver().db(arangoProperties.database());
		var graph = db.graph(GRAPH);
		if (!graph.exists()) {
			db.createGraph(GRAPH, edgeDefinitions);
			return;
		}

		// Bestehenden Graph nachziehen, wenn Vertex- oder Edge-Typen dazugekommen sind. Nicht mehr benötigte
		// Edge-Definitionen bleiben stehen, damit kein Update Daten aus dem Graph entfernt.
		var existing = graph.getInfo().getEdgeDefinitions().stream()
				.collect(Collectors.toMap(EdgeDefinition::getCollection, Function.identity()));
		for (var wanted : edgeDefinitions) {
			var current = existing.get(wanted.getCollection());
			if (current == null) {
				log.info("Graph {}: Edge-Definition {} hinzugefügt", GRAPH, wanted.getCollection());
				graph.addEdgeDefinition(wanted);
			}
			else if (!Set.copyOf(current.getFrom()).containsAll(wanted.getFrom())
					|| !Set.copyOf(current.getTo()).containsAll(wanted.getTo())) {
				log.info("Graph {}: Edge-Definition {} um neue Vertex-Collections erweitert", GRAPH,
						wanted.getCollection());
				var merged = new EdgeDefinition()
						.collection(wanted.getCollection())
						.from(union(current.getFrom(), wanted.getFrom()))
						.to(union(current.getTo(), wanted.getTo()));
				graph.replaceEdgeDefinition(merged);
			}
		}
	}

	private static String[] union(Collection<String> a, Collection<String> b) {
		return Stream.concat(a.stream(), b.stream()).distinct().toArray(String[]::new);
	}

	/**
	 * Liest einen Ressourcentyp, übernimmt ihn in den Snapshot und gibt die gelesenen Objekte zurück. Fehler beim
	 * Lesen oder Abbilden brechen den Import nicht ab, sondern landen in {@link ImportResult#failures()}.
	 */
	private <T extends HasMetadata> List<T> load(Snapshot snapshot, Class<? extends K8sResource> type,
			boolean clusterScoped, Function<String, List<T>> perNamespace, Supplier<List<T>> all,
			Function<T, ? extends K8sResource> mapper) {
		var items = fetch(snapshot, type, clusterScoped, perNamespace, all);
		forEachSafely(snapshot, type, items, item -> snapshot.add(mapper.apply(item), item.getMetadata()));
		return items;
	}

	/** Liest einen Ressourcentyp, bei gesetzten Namespaces jeden Namespace für sich. */
	private <T> List<T> fetch(Snapshot snapshot, Class<?> type, boolean clusterScoped,
			Function<String, List<T>> perNamespace, Supplier<List<T>> all) {
		if (clusterScoped || properties.namespaces().isEmpty()) {
			return attempt(snapshot, type, null, all);
		}
		return properties.namespaces().stream()
				.flatMap(ns -> attempt(snapshot, type, ns, () -> perNamespace.apply(ns)).stream())
				.toList();
	}

	private <T> List<T> attempt(Snapshot snapshot, Class<?> type, String namespace, Supplier<List<T>> call) {
		try {
			return call.get();
		}
		catch (RuntimeException e) {
			snapshot.fail(type, namespace, e);
			return List.of();
		}
	}

	/** Ein fehlerhaftes Objekt sperrt nur seinen Namespace (bzw. bei cluster-weiten Objekten den Typ). */
	private static <T> void forEachSafely(Snapshot snapshot, Class<?> type, Collection<T> items, Consumer<T> action) {
		for (T item : items) {
			try {
				action.accept(item);
			}
			catch (RuntimeException e) {
				snapshot.fail(type, namespaceOf(item), e);
			}
		}
	}

	/**
	 * fabric8 verpackt die eigentliche Ursache (z. B. 403 Forbidden, Connection refused) in "Operation ... failed";
	 * deshalb hängt die Ursachenkette mit an.
	 */
	private static String describe(Throwable e) {
		var parts = new ArrayList<String>();
		parts.add(Objects.requireNonNullElse(e.getMessage(), e.getClass().getName()).strip());
		for (var cause = e.getCause(); cause != null && cause != cause.getCause(); cause = cause.getCause()) {
			var message = cause.getMessage();
			if (message == null || !parts.getFirst().contains(message.strip())) {
				parts.add(cause.getClass().getSimpleName() + (message == null ? "" : ": " + message.strip()));
			}
		}
		return String.join(" <- ", parts);
	}

	private static String namespaceOf(Object item) {
		if (item instanceof HasMetadata h) {
			return h.getMetadata().getNamespace();
		}
		if (item instanceof List<?> l && !l.isEmpty()) {
			return namespaceOf(l.getFirst());
		}
		return null;
	}

	private static String id(String collection, ObjectMeta meta) {
		return collection + "/" + K8sResource.keyOf(meta.getNamespace(), meta.getName());
	}

	private static List<SelectorRequirement> matchExpressions(LabelSelector selector) {
		if (selector == null || selector.getMatchExpressions() == null) {
			return List.of();
		}
		return selector.getMatchExpressions().stream()
				.map(e -> new SelectorRequirement(e.getKey(), e.getOperator(),
						e.getValues() == null ? List.of() : e.getValues()))
				.toList();
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
		private final List<ImportResult.Failure> failures = new ArrayList<>();

		void fail(Class<?> type, String namespace, RuntimeException e) {
			var collection = operations.collection(type).name();
			var message = describe(e);
			log.debug("Import von {} (Namespace {}) fehlgeschlagen", collection, namespace, e);
			failures.add(new ImportResult.Failure(collection, namespace, message));
		}

		/** Collections, die gar nicht gelesen werden konnten. */
		private Set<String> failedCollections() {
			return failures.stream().filter(f -> f.namespace() == null).map(ImportResult.Failure::collection)
					.collect(Collectors.toSet());
		}

		/** Namespaces einer Collection, die nicht gelesen werden konnten. */
		private List<String> failedNamespaces(String collection) {
			return failures.stream().filter(f -> f.collection().equals(collection) && f.namespace() != null)
					.map(ImportResult.Failure::namespace).distinct().toList();
		}

		/** {@code <collection>/<namespace>} aller nicht lesbaren Namespaces. */
		private List<String> failedScopes() {
			return failures.stream().filter(f -> f.namespace() != null)
					.map(f -> f.collection() + "/" + f.namespace()).distinct().toList();
		}

		void add(K8sResource resource, ObjectMeta meta) {
			if (meta != null) {
				resource.setUid(meta.getUid());
				resource.setLabels(meta.getLabels() == null ? Map.of() : meta.getLabels());
				resource.setCreatedAt(meta.getCreationTimestamp());
				ownerEdges(resource, meta);
				helmEdge(resource, meta);
			}
			documents.computeIfAbsent(resource.getClass(), k -> new ArrayList<>()).add(resource);
			var resourceId = operations.collection(resource.getClass()).name() + "/" + resource.getKey();
			namespaces.put(resourceId, resource.getNamespace());
			if (resource.getNamespace() != null) {
				edges.add(new Contains("namespaces/" + resource.getNamespace(), resourceId));
			}
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
				// Start cluster-weit (z. B. contains: Namespace → Pod): Namespace des Ziels, damit die Kante beim Aufräumen
				// im Scope ihres Ziels bleibt.
				var fromNamespace = namespaces.get(edge.getFrom());
				edge.setNamespace(fromNamespace != null ? fromNamespace : namespaces.get(edge.getTo()));
				resolved.computeIfAbsent(edge.getClass(), k -> new LinkedHashMap<>()).putIfAbsent(edge.getKey(), edge);
			}
			if (dropped > 0) {
				log.debug("{} Kanten ohne importiertes Ziel verworfen", dropped);
			}

			var failedCollections = failedCollections();
			for (var type : VERTEX_TYPES) {
				var extra = type == HelmRelease.class ? MERGE_HELM_HISTORY : "";
				var collection = operations.collection(type).name();
				sync(collection, documents.getOrDefault(type, List.of()), extra, now, changes,
						() -> failedCollections.contains(collection) ? 0 : remove(REMOVE_STALE_VERTICES, collection,
								now, Map.of("failedNamespaces", failedNamespaces(collection))));
			}
			for (var type : EDGE_TYPES) {
				var collection = operations.collection(type).name();
				sync(collection, resolved.getOrDefault(type, Map.of()).values(), "", now, changes,
						() -> remove(REMOVE_STALE_EDGES, collection, now,
								Map.of("failedCollections", failedCollections, "failedScopes", failedScopes())));
			}
			return new ImportResult(now, changes, List.copyOf(failures));
		}

		private int remove(String query, String collection, String now, Map<String, Object> extraParams) {
			var params = new HashMap<String, Object>(extraParams);
			params.put("now", now);
			params.put("@collection", collection);
			params.put("allNamespaces", properties.namespaces().isEmpty());
			params.put("namespaces", properties.namespaces());
			return operations.query(query, params, Integer.class).asListRemaining().size();
		}

		/**
		 * Upsert, danach {@code removeStale}: Erst nach dem Upsert trägt alles, was der Lauf gesehen hat,
		 * {@code lastSeen == now}.
		 */
		private void sync(String collection, Collection<?> docs, String extraMerge, String now,
				Map<String, ImportResult.Changes> changes, IntSupplier removeStale) {
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
			int removed = removeStale.getAsInt();
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
