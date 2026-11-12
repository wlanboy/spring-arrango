package com.example.arrango.k8s;

import com.arangodb.entity.EdgeDefinition;
import com.arangodb.entity.GraphEntity;
import com.example.arrango.config.ArangoProperties;
import com.example.arrango.model.k8s.ConfigMap;
import com.example.arrango.model.k8s.HelmRelease;
import com.example.arrango.model.k8s.HelmRevision;
import com.example.arrango.model.k8s.Job;
import com.example.arrango.model.k8s.K8sEdge;
import com.example.arrango.model.k8s.K8sResource;
import com.example.arrango.model.k8s.Mounts;
import com.example.arrango.model.k8s.Node;
import com.example.arrango.model.k8s.Owns;
import com.example.arrango.model.k8s.References;
import com.example.arrango.model.k8s.Secret;
import com.example.arrango.model.k8s.StorageClass;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.NodeBuilder;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.PersistentVolumeBuilder;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.ServiceAccountBuilder;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.apps.ReplicaSetBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.CronJobBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.api.model.storage.StorageClassBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Importer gegen einen fabric8-Mock-API-Server im CRUD-Modus; ArangoDB ist durch {@link ArangoRecorder} ersetzt.
 * Geprüft wird, welche Vertices und Kanten der Importer schreibt und mit welchem Scope er aufräumt.
 */
@EnableKubernetesMockClient(crud = true)
class ClusterGraphImporterTest {

	private static final String NS = "shop";

	KubernetesMockServer server;
	KubernetesClient client;

	private ArangoRecorder arango;

	@BeforeEach
	void setUp() {
		arango = new ArangoRecorder();
		create(new NamespaceBuilder().withMetadata(meta(null, NS)).build());
		create(new NamespaceBuilder().withMetadata(meta(null, "other")).build());
	}

	private ClusterGraphImporter importer(String... namespaces) {
		return new ClusterGraphImporter(client, arango.operations,
				new ArangoProperties("localhost", 8529, "root", "", "test"),
				new K8sImportProperties(false, List.of(namespaces)));
	}

	// ---------------------------------------------------------------- Vertices

	@Test
	void importsNamespacesAndNodesAsClusterScopedVertices() {
		create(new NodeBuilder()
				.withMetadata(new ObjectMetaBuilder().withName("worker-1")
						.addToLabels("node-role.kubernetes.io/control-plane", "")
						.addToLabels("kubernetes.io/hostname", "worker-1").build())
				.withNewStatus()
				.withNewNodeInfo().withKubeletVersion("v1.37.0").endNodeInfo()
				.addNewAddress().withType("Hostname").withAddress("worker-1").endAddress()
				.addNewAddress().withType("InternalIP").withAddress("10.0.0.5").endAddress()
				.endStatus()
				.build());

		importer().importCluster();

		assertThat(keys("namespaces")).containsExactlyInAnyOrder("shop", "other");
		List<Node> nodes = arango.docs("nodes");
		assertThat(nodes).singleElement().satisfies(n -> {
			assertThat(n.getKey()).isEqualTo("worker-1");
			assertThat(n.getNamespace()).isNull();
			assertThat(n.getKubeletVersion()).isEqualTo("v1.37.0");
			assertThat(n.getInternalIp()).isEqualTo("10.0.0.5");
			assertThat(n.getRoles()).containsExactly("control-plane");
		});
	}

	@Test
	void storesOnlyKeyNamesOfSecretsAndConfigMaps() {
		create(new SecretBuilder().withMetadata(meta(NS, "db")).withType("Opaque")
				.addToData("password", b64("s3cr3t")).addToData("user", b64("app")).build());
		create(new ConfigMapBuilder().withMetadata(meta(NS, "settings"))
				.addToData("b.yaml", "x: 1").addToData("a.yaml", "y: 2").build());

		importer().importCluster();

		List<Secret> secrets = arango.docs("secrets");
		assertThat(secrets).singleElement().satisfies(s -> {
			assertThat(s.getKey()).isEqualTo("shop.db");
			assertThat(s.getType()).isEqualTo("Opaque");
			assertThat(s.getDataKeys()).containsExactly("password", "user");
		});
		List<ConfigMap> configMaps = arango.docs("config_maps");
		assertThat(configMaps).singleElement().satisfies(c -> assertThat(c.getDataKeys()).containsExactly("a.yaml", "b.yaml"));
	}

	// ---------------------------------------------------------------- Kanten

	@Test
	void linksWorkloadChainViaOwnerReferencesAndNamespace() {
		create(deployment("web"));
		create(new ReplicaSetBuilder().withMetadata(owned(NS, "web-abc", "Deployment", "web"))
				.withNewSpec().withReplicas(1).endSpec().withNewStatus().withReadyReplicas(1).endStatus().build());
		create(pod("web-abc-1", "ReplicaSet", "web-abc", Map.of("app", "web")));

		importer().importCluster();

		assertThat(edges("owns")).containsExactlyInAnyOrder(
				"deployments/shop.web -> replica_sets/shop.web-abc",
				"replica_sets/shop.web-abc -> pods/shop.web-abc-1");
		assertThat(arango.<Owns>docs("owns")).allSatisfy(o -> assertThat(o.isController()).isTrue());
		assertThat(edges("contains")).contains(
				"namespaces/shop -> deployments/shop.web",
				"namespaces/shop -> pods/shop.web-abc-1");
		// contains startet cluster-weit; die Kante bekommt den Namespace ihres Ziels für den Aufräum-Scope.
		assertThat(arango.<K8sEdge>docs("contains")).allSatisfy(e -> assertThat(e.getNamespace()).isEqualTo(NS));
	}

	@Test
	void linksPodToConfigSecretsVolumesNodeAndServiceAccount() {
		create(new NodeBuilder().withMetadata(new ObjectMetaBuilder().withName("worker-1").withLabels(Map.of()).build())
				.withNewStatus().withNewNodeInfo().withKubeletVersion("v1").endNodeInfo().endStatus().build());
		create(new ServiceAccountBuilder().withMetadata(meta(NS, "web")).build());
		create(new ConfigMapBuilder().withMetadata(meta(NS, "settings")).build());
		create(new ConfigMapBuilder().withMetadata(meta(NS, "ca")).build());
		create(new SecretBuilder().withMetadata(meta(NS, "db")).build());
		create(new SecretBuilder().withMetadata(meta(NS, "token")).build());
		create(new PersistentVolumeClaimBuilder().withMetadata(meta(NS, "data")).withNewSpec().endSpec().build());
		create(new PodBuilder()
				.withMetadata(meta(NS, "web-0"))
				.withNewSpec()
				.withNodeName("worker-1")
				.withServiceAccountName("web")
				.addNewVolume().withName("settings").withNewConfigMap().withName("settings").endConfigMap().endVolume()
				.addNewVolume().withName("db").withNewSecret().withSecretName("db").endSecret().endVolume()
				.addNewVolume().withName("data").withNewPersistentVolumeClaim().withClaimName("data")
				.endPersistentVolumeClaim().endVolume()
				.addNewVolume().withName("bundle").withNewProjected()
				.addNewSource().withNewConfigMap().withName("ca").endConfigMap().endSource()
				.addNewSource().withNewSecret().withName("token").endSecret().endSource()
				.endProjected().endVolume()
				// fehlt im Cluster, ist aber optional: Kante wird verworfen
				.addNewVolume().withName("extra").withNewSecret().withSecretName("missing").withOptional(true)
				.endSecret().endVolume()
				.addToInitContainers(new ContainerBuilder().withName("init").withImage("busybox")
						.addNewVolumeMount().withName("settings").withMountPath("/init/config").endVolumeMount().build())
				.addToContainers(new ContainerBuilder().withName("app").withImage("nginx:1.27")
						.addNewVolumeMount().withName("settings").withMountPath("/etc/app").endVolumeMount()
						.addNewEnv().withName("DB_PASSWORD").withNewValueFrom().withNewSecretKeyRef("password", "db", false)
						.endValueFrom().endEnv()
						.addNewEnv().withName("MODE").withNewValueFrom().withNewConfigMapKeyRef("mode", "settings", false)
						.endValueFrom().endEnv()
						.addNewEnv().withName("PLAIN").withValue("1").endEnv()
						.addNewEnvFrom().withNewConfigMapRef("settings", false).endEnvFrom()
						.build())
				.endSpec()
				.withNewStatus().withPhase("Running").withPodIP("10.1.0.7").endStatus()
				.build());

		importer().importCluster();

		var pod = "pods/shop.web-0";
		assertThat(edges("scheduled_on")).containsExactly(pod + " -> nodes/worker-1");
		assertThat(edges("uses_service_account")).containsExactly(pod + " -> service_accounts/shop.web");

		List<Mounts> mounts = arango.docs("mounts");
		assertThat(mounts).extracting(Mounts::getTo, Mounts::getVolumeName, Mounts::getSource)
				.containsExactlyInAnyOrder(
						tuple("config_maps/shop.settings", "settings", "configMap"),
						tuple("secrets/shop.db", "db", "secret"),
						tuple("persistent_volume_claims/shop.data", "data", "persistentVolumeClaim"),
						tuple("config_maps/shop.ca", "bundle", "projected"),
						tuple("secrets/shop.token", "bundle", "projected"));
		assertThat(mounts).filteredOn(m -> m.getVolumeName().equals("settings")).singleElement()
				.satisfies(m -> assertThat(m.getMountPaths()).containsExactly("/init/config", "/etc/app"));

		List<References> refs = arango.docs("references");
		assertThat(refs).extracting(References::getTo, References::getEnvVar, References::getDataKey,
				References::isEnvFrom)
				.containsExactlyInAnyOrder(
						tuple("secrets/shop.db", "DB_PASSWORD", "password", false),
						tuple("config_maps/shop.settings", "MODE", "mode", false),
						tuple("config_maps/shop.settings", null, null, true));
		// Dokument-Key ist der Hash, nicht der Env-Key (Regression: References.key verdeckte K8sEdge.key)
		assertThat(refs).allSatisfy(r -> assertThat(r.getKey()).hasSize(32).isNotEqualTo(r.getDataKey()));
	}

	@Test
	void serviceSelectsMatchingPodsInItsNamespaceOnly() {
		create(pod("web-1", null, null, Map.of("app", "web", "tier", "front")));
		create(pod("api-1", null, null, Map.of("app", "api")));
		create(new PodBuilder().withMetadata(new ObjectMetaBuilder().withNamespace("other").withName("web-1")
						.addToLabels("app", "web").build())
				.withNewSpec().endSpec().withNewStatus().withPhase("Running").endStatus().build());
		create(new ServiceBuilder().withMetadata(meta(NS, "web"))
				.withNewSpec().addToSelector("app", "web").addNewPort().withPort(80).withProtocol("TCP").endPort().endSpec()
				.build());
		create(new ServiceBuilder().withMetadata(meta(NS, "external"))
				.withNewSpec().withType("ExternalName").withExternalName("example.org").endSpec().build());

		importer().importCluster();

		assertThat(edges("selects")).containsExactly("services/shop.web -> pods/shop.web-1");
		assertThat(keys("services")).containsExactlyInAnyOrder("shop.web", "shop.external");
	}

	@Test
	void linksStorageClassesVolumesAndClaims() {
		create(new StorageClassBuilder()
				.withMetadata(new ObjectMetaBuilder().withName("standard")
						.addToAnnotations("storageclass.kubernetes.io/is-default-class", "true").build())
				.withProvisioner("rancher.io/local-path").withReclaimPolicy("Delete").build());
		create(new PersistentVolumeBuilder().withMetadata(meta(null, "pv-1"))
				.withNewSpec().withStorageClassName("standard").addToCapacity("storage", new Quantity("1Gi")).endSpec()
				.build());
		create(new PersistentVolumeClaimBuilder().withMetadata(meta(NS, "data"))
				.withNewSpec().withStorageClassName("standard").withVolumeName("pv-1").endSpec().build());
		// "" heißt ausdrücklich: keine StorageClass
		create(new PersistentVolumeClaimBuilder().withMetadata(meta(NS, "static"))
				.withNewSpec().withStorageClassName("").endSpec().build());

		importer().importCluster();

		List<StorageClass> classes = arango.docs("storage_classes");
		assertThat(classes).singleElement().satisfies(sc -> {
			assertThat(sc.isDefaultClass()).isTrue();
			assertThat(sc.getProvisioner()).isEqualTo("rancher.io/local-path");
		});
		assertThat(edges("binds")).containsExactly("persistent_volume_claims/shop.data -> persistent_volumes/pv-1");
		assertThat(edges("uses_storage_class")).containsExactlyInAnyOrder(
				"persistent_volume_claims/shop.data -> storage_classes/standard",
				"persistent_volumes/pv-1 -> storage_classes/standard");
	}

	@Test
	void importsCronJobsAndJobsWithStatus() {
		create(new CronJobBuilder().withMetadata(meta(NS, "backup"))
				.withNewSpec().withSchedule("0 3 * * *").withSuspend(true)
				.withNewJobTemplate().withNewSpec().withNewTemplate().withNewSpec()
				.addNewContainer().withName("backup").withImage("backup:1").endContainer()
				.endSpec().endTemplate().endSpec().endJobTemplate()
				.endSpec().build());
		create(job("backup-1", "CronJob", "backup", "Failed"));
		create(job("migrate", null, null, null));
		create(pod("backup-1-xyz", "Job", "backup-1", Map.of()));

		importer().importCluster();

		List<Job> jobs = arango.docs("jobs");
		assertThat(jobs).extracting(Job::getName, Job::getStatus)
				.containsExactlyInAnyOrder(tuple("backup-1", "Failed"), tuple("migrate", "Running"));
		assertThat(edges("owns")).containsExactlyInAnyOrder(
				"cron_jobs/shop.backup -> jobs/shop.backup-1",
				"jobs/shop.backup-1 -> pods/shop.backup-1-xyz");
	}

	// ---------------------------------------------------------------- Helm

	@Test
	void buildsHelmReleaseFromRevisionSecretsAndLinksOnlyTopLevelResources() throws IOException {
		create(helmSecret("web", 1, "superseded", "1.0.0", false));
		create(helmSecret("web", 2, "deployed", "1.1.0", true));
		create(deployment("web"));
		// Der ReplicaSet-Controller kopiert die Helm-Annotationen; verwaltet wird trotzdem nur das Deployment.
		create(new ReplicaSetBuilder().withMetadata(new ObjectMetaBuilder(owned(NS, "web-abc", "Deployment", "web"))
						.addToAnnotations(helmAnnotations()).build())
				.withNewSpec().endSpec().withNewStatus().endStatus().build());

		importer().importCluster();

		List<HelmRelease> releases = arango.docs("helm_releases");
		assertThat(releases).singleElement().satisfies(r -> {
			assertThat(r.getKey()).isEqualTo("shop.web");
			assertThat(r.getRevision()).isEqualTo(2);
			assertThat(r.getStatus()).isEqualTo("deployed");
			assertThat(r.getChart()).isEqualTo("web-chart");
			assertThat(r.getChartVersion()).isEqualTo("1.1.0");
			assertThat(r.getAppVersion()).isEqualTo("2.0");
			assertThat(r.getHistory()).extracting(HelmRevision::getRevision, HelmRevision::getStatus,
							HelmRevision::getChartVersion)
					.containsExactly(tuple(2, "deployed", "1.1.0"), tuple(1, "superseded", "1.0.0"));
		});
		assertThat(edges("manages")).containsExactly("helm_releases/shop.web -> deployments/shop.web");
		// Helm-Release-Secrets landen nicht in secrets
		assertThat(arango.docs("secrets")).isEmpty();
	}

	// ---------------------------------------------------------------- Inkrementell und Scope

	@Test
	void secondImportUpdatesInsteadOfInsertingAndKeepsEdgeKeysStable() {
		create(pod("web-1", null, null, Map.of("app", "web")));
		create(new ServiceBuilder().withMetadata(meta(NS, "web")).withNewSpec().addToSelector("app", "web").endSpec()
				.build());
		var importer = importer();

		var first = importer.importCluster();
		var firstKeys = arango.<K8sEdge>docs("selects").stream().map(K8sEdge::getKey).toList();
		var second = importer.importCluster();

		assertThat(first.collections().get("pods")).isEqualTo(new ImportResult.Changes(1, 0, 0));
		assertThat(second.collections().get("pods")).isEqualTo(new ImportResult.Changes(0, 1, 0));
		assertThat(second.collections().get("selects")).isEqualTo(new ImportResult.Changes(0, 1, 0));
		assertThat(arango.<K8sEdge>docs("selects")).extracting(K8sEdge::getKey).isEqualTo(firstKeys);
		assertThat(firstKeys).singleElement().asString().hasSize(32).matches("[0-9a-f]+");
		assertThat(arango.removeParams.get("pods")).containsEntry("now", second.importedAt());
	}

	@Test
	void deduplicatesIdenticalEdges() {
		create(new ConfigMapBuilder().withMetadata(meta(NS, "settings")).build());
		create(new PodBuilder().withMetadata(meta(NS, "web-0")).withNewSpec()
				.addNewContainer().withName("a").addNewEnvFrom().withNewConfigMapRef("settings", false).endEnvFrom()
				.endContainer()
				.addNewContainer().withName("b").addNewEnvFrom().withNewConfigMapRef("settings", false).endEnvFrom()
				.endContainer()
				.endSpec().withNewStatus().withPhase("Running").endStatus().build());

		var result = importer().importCluster();

		assertThat(edges("references")).containsExactly("pods/shop.web-0 -> config_maps/shop.settings");
		assertThat(result.collections().get("references").inserted()).isEqualTo(1);
	}

	@Test
	void restrictsImportAndCleanupToConfiguredNamespaces() {
		create(pod("web-1", null, null, Map.of()));
		create(new PodBuilder().withMetadata(meta("other", "web-1")).withNewSpec().endSpec()
				.withNewStatus().withPhase("Running").endStatus().build());

		importer(NS).importCluster();

		assertThat(keys("pods")).containsExactly("shop.web-1");
		// Namespaces sind cluster-weit und immer vollständig
		assertThat(keys("namespaces")).containsExactlyInAnyOrder("shop", "other");
		assertThat(arango.removeParams.get("pods"))
				.containsEntry("allNamespaces", false)
				.containsEntry("namespaces", List.of(NS));
	}

	// ---------------------------------------------------------------- Fehler

	@Test
	void unreadableCollectionIsReportedAndNotCleanedUp() {
		server.expect().get().withPath("/apis/storage.k8s.io/v1/storageclasses")
				.andReturn(403, "{\"kind\":\"Status\",\"code\":403,\"message\":\"forbidden\"}").always();
		create(pod("web-1", null, null, Map.of()));

		var result = importer().importCluster();

		assertThat(result.failures()).singleElement().satisfies(f -> {
			assertThat(f.collection()).isEqualTo("storage_classes");
			assertThat(f.namespace()).isNull();
			assertThat(f.error()).contains("403");
		});
		assertThat(keys("pods")).containsExactly("shop.web-1");
		assertThat(arango.removeParams).doesNotContainKey("storage_classes").containsKey("pods");
		assertThat(arango.removeParams.get("uses_storage_class"))
				.extractingByKey("failedCollections").asInstanceOf(
						org.assertj.core.api.InstanceOfAssertFactories.collection(String.class))
				.containsExactly("storage_classes");
	}

	@Test
	void unreadableNamespaceKeepsItsOldStateOnly() {
		server.expect().get().withPath("/api/v1/namespaces/other/configmaps")
				.andReturn(403, "{\"kind\":\"Status\",\"code\":403,\"message\":\"forbidden\"}").always();
		create(new ConfigMapBuilder().withMetadata(meta(NS, "settings")).build());

		var result = importer(NS, "other").importCluster();

		assertThat(result.failures()).extracting(ImportResult.Failure::collection, ImportResult.Failure::namespace)
				.containsExactly(tuple("config_maps", "other"));
		assertThat(keys("config_maps")).containsExactly("shop.settings");
		assertThat(arango.removeParams.get("config_maps")).containsEntry("failedNamespaces", List.of("other"));
		assertThat(arango.removeParams.get("pods")).containsEntry("failedNamespaces", List.of());
		assertThat(arango.removeParams.get("mounts")).containsEntry("failedScopes", List.of("config_maps/other"));
	}

	// ---------------------------------------------------------------- Graph-Definition

	@Nested
	class NamedGraph {

		@Test
		void createsGraphWithAllEdgeCollectionsIfMissing() {
			when(arango.graph.exists()).thenReturn(false);

			importer().importCluster();

			verify(arango.database).createGraph(eq(ClusterGraphImporter.GRAPH), argThat(defs -> {
				var list = StreamSupport.stream(defs.spliterator(), false).toList();
				var names = list.stream().map(EdgeDefinition::getCollection).collect(Collectors.toSet());
				return names.containsAll(List.of("contains", "manages", "owns", "mounts", "references", "selects",
						"scheduled_on", "uses_service_account", "binds", "uses_storage_class"))
						&& list.stream().allMatch(d -> d.getFrom().contains("storage_classes")
								&& d.getTo().contains("namespaces"));
			}));
		}

		@Test
		void extendsExistingGraphWithoutDroppingDefinitions() {
			var owns = new EdgeDefinition().collection("owns").from("deployments").to("pods");
			var legacy = new EdgeDefinition().collection("legacy").from("pods").to("pods");
			var allVertices = ResourceType.entityClasses().stream().map(ArangoRecorder::collectionName)
					.toArray(String[]::new);
			var others = List.of("contains", "manages", "mounts", "references", "selects", "scheduled_on",
					"uses_service_account", "binds", "uses_storage_class").stream()
					.map(c -> new EdgeDefinition().collection(c).from(allVertices).to(allVertices)).toList();
			var info = mock(GraphEntity.class);
			when(info.getEdgeDefinitions()).thenReturn(
					java.util.stream.Stream.concat(java.util.stream.Stream.of(owns, legacy), others.stream()).toList());
			when(arango.graph.exists()).thenReturn(true);
			when(arango.graph.getInfo()).thenReturn(info);

			importer().importCluster();

			verify(arango.database, never()).createGraph(anyString(), any());
			verify(arango.graph, never()).addEdgeDefinition(any());
			verify(arango.graph).replaceEdgeDefinition(argThat((EdgeDefinition d) -> d.getCollection().equals("owns")
					&& d.getFrom().containsAll(List.of(allVertices)) && d.getTo().containsAll(List.of(allVertices))));
			verify(arango.graph, never()).replaceEdgeDefinition(argThat((EdgeDefinition d) ->
					!d.getCollection().equals("owns")));
		}

		@Test
		void addsMissingEdgeDefinition() {
			var info = mock(GraphEntity.class);
			when(info.getEdgeDefinitions()).thenReturn(List.of());
			when(arango.graph.exists()).thenReturn(true);
			when(arango.graph.getInfo()).thenReturn(info);

			importer().importCluster();

			verify(arango.graph).addEdgeDefinition(argThat(d -> d.getCollection().equals("uses_storage_class")));
		}
	}

	// ---------------------------------------------------------------- Fixtures

	private void create(HasMetadata resource) {
		client.resource(resource).create();
	}

	private List<String> keys(String collection) {
		return arango.<K8sResource>docs(collection).stream().map(K8sResource::getKey).toList();
	}

	private List<String> edges(String collection) {
		return arango.<K8sEdge>docs(collection).stream().map(e -> e.getFrom() + " -> " + e.getTo()).toList();
	}

	private static ObjectMeta meta(String namespace, String name) {
		return new ObjectMetaBuilder().withNamespace(namespace).withName(name).build();
	}

	private static ObjectMeta owned(String namespace, String name, String ownerKind, String ownerName) {
		return new ObjectMetaBuilder(meta(namespace, name))
				.addToOwnerReferences(new OwnerReferenceBuilder().withKind(ownerKind).withName(ownerName)
						.withApiVersion("v1").withUid(ownerName + "-uid").withController(true).build())
				.build();
	}

	private static Map<String, String> helmAnnotations() {
		return Map.of("meta.helm.sh/release-name", "web", "meta.helm.sh/release-namespace", NS);
	}

	private static io.fabric8.kubernetes.api.model.apps.Deployment deployment(String name) {
		return new DeploymentBuilder()
				.withMetadata(new ObjectMetaBuilder(meta(NS, name)).addToAnnotations(helmAnnotations()).build())
				.withNewSpec().withReplicas(2)
				.withNewSelector().addToMatchLabels("app", name)
				.addNewMatchExpression().withKey("tier").withOperator("In").withValues("front").endMatchExpression()
				.endSelector()
				.withNewTemplate().withNewSpec().addNewContainer().withName(name).withImage("nginx:1.27").endContainer()
				.endSpec().endTemplate()
				.endSpec()
				.withNewStatus().withReadyReplicas(1).endStatus()
				.build();
	}

	private static io.fabric8.kubernetes.api.model.Pod pod(String name, String ownerKind, String ownerName,
			Map<String, String> labels) {
		var meta = ownerKind == null ? meta(NS, name) : owned(NS, name, ownerKind, ownerName);
		meta.setLabels(labels);
		return new PodBuilder().withMetadata(meta)
				.withNewSpec().addNewContainer().withName("app").withImage("nginx:1.27").endContainer().endSpec()
				.withNewStatus().withPhase("Running").endStatus()
				.build();
	}

	private static io.fabric8.kubernetes.api.model.batch.v1.Job job(String name, String ownerKind, String ownerName,
			String terminalCondition) {
		var builder = new JobBuilder()
				.withMetadata(ownerKind == null ? meta(NS, name) : owned(NS, name, ownerKind, ownerName))
				.withNewSpec().withCompletions(1)
				.withNewTemplate().withNewSpec().addNewContainer().withName("job").withImage("job:1").endContainer()
				.endSpec().endTemplate()
				.endSpec();
		if (terminalCondition != null) {
			builder.withNewStatus().withFailed(1)
					.addNewCondition().withType(terminalCondition).withStatus("True").endCondition()
					.endStatus();
		}
		return builder.build();
	}

	/** Wie Helm 3: {@code data.release} = base64(base64([gzip](json))), Labels name/version/status/owner. */
	private static io.fabric8.kubernetes.api.model.Secret helmSecret(String release, int revision, String status,
			String chartVersion, boolean gzip) throws IOException {
		var json = """
				{"name":"%s","version":%d,"chart":{"metadata":{"name":"web-chart","version":"%s","appVersion":"2.0"}}}
				""".formatted(release, revision, chartVersion).getBytes(StandardCharsets.UTF_8);
		byte[] payload = json;
		if (gzip) {
			var out = new ByteArrayOutputStream();
			try (var gz = new GZIPOutputStream(out)) {
				gz.write(json);
			}
			payload = out.toByteArray();
		}
		var helmEncoded = Base64.getEncoder().encodeToString(payload);
		return new SecretBuilder()
				.withMetadata(new ObjectMetaBuilder()
						.withNamespace(NS).withName("sh.helm.release.v1." + release + ".v" + revision)
						.addToLabels("owner", "helm").addToLabels("name", release)
						.addToLabels("version", String.valueOf(revision)).addToLabels("status", status)
						.build())
				.withType("helm.sh/release.v1")
				.addToData("release", b64(helmEncoded))
				.build();
	}

	private static String b64(String value) {
		return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
	}
}
