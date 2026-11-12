package com.example.arrango.k8s;

import com.example.arrango.model.k8s.Node;
import com.example.arrango.model.k8s.Pod;
import com.example.arrango.repository.k8s.ChangeReport;
import com.example.arrango.repository.k8s.ConfigMapRepository;
import com.example.arrango.repository.k8s.HelmReleaseRepository;
import com.example.arrango.repository.k8s.NodeRepository;
import com.example.arrango.repository.k8s.PodRepository;
import com.example.arrango.repository.k8s.ResourceRef;
import com.example.arrango.repository.k8s.SecretRepository;
import com.example.arrango.repository.k8s.ServiceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Routing, 404/400-Regeln und Parameterweitergabe des Controllers; Abfragen und Importer sind gemockt. */
@WebMvcTest(K8sGraphController.class)
class K8sGraphControllerTest {

	@Autowired
	MockMvcTester mvc;

	@MockitoBean
	ClusterGraphImporter importer;
	@MockitoBean
	K8sQueries queries;
	@MockitoBean
	HelmReleaseRepository releases;
	@MockitoBean
	ConfigMapRepository configMaps;
	@MockitoBean
	SecretRepository secrets;
	@MockitoBean
	PodRepository pods;
	@MockitoBean
	ServiceRepository services;
	@MockitoBean
	NodeRepository nodes;

	@Test
	void importReturnsChangesAndFailures() {
		when(importer.importCluster()).thenReturn(new ImportResult("2026-11-12T08:00:00Z",
				Map.of("pods", new ImportResult.Changes(3, 1, 2)),
				List.of(new ImportResult.Failure("secrets", "kube-system", "403 Forbidden"))));

		var result = mvc.post().uri("/api/k8s/import").exchange();

		result.assertThat().hasStatusOk().bodyJson()
				.hasPathSatisfying("$.collections.pods.inserted", v -> v.assertThat().isEqualTo(3))
				.hasPathSatisfying("$.collections.pods.removed", v -> v.assertThat().isEqualTo(2))
				.hasPathSatisfying("$.failures[0].namespace", v -> v.assertThat().isEqualTo("kube-system"));
	}

	@Test
	void releaseResourcesAnswer404AsProblemDetailForUnknownRelease() {
		when(queries.exists(ResourceType.HELM_RELEASES, "shop", "web")).thenReturn(false);

		var result = mvc.get().uri("/api/k8s/releases/shop/web/resources").exchange();

		result.assertThat().hasStatus(HttpStatus.NOT_FOUND).bodyJson()
				.hasPathSatisfying("$.detail", v -> v.assertThat().isEqualTo("helm_releases/shop.web nicht gefunden"));
		verify(releases, never()).resources(anyString(), anyString());
	}

	@Test
	void releaseResourcesDelegateToRepository() {
		when(queries.exists(ResourceType.HELM_RELEASES, "shop", "web")).thenReturn(true);
		when(releases.resources("shop", "web"))
				.thenReturn(List.of(new ResourceRef("deployments/shop.web", "Deployment", "shop", "web", 1)));

		mvc.get().uri("/api/k8s/releases/shop/web/resources").exchange().assertThat().hasStatusOk().bodyJson()
				.hasPathSatisfying("$[0].id", v -> v.assertThat().isEqualTo("deployments/shop.web"));
	}

	@Test
	void consumersQueryTheMatchingCollection() {
		when(queries.exists(any(ResourceType.class), anyString(), anyString())).thenReturn(true);

		mvc.get().uri("/api/k8s/configmaps/shop/settings/consumers").exchange().assertThat().hasStatusOk();
		mvc.get().uri("/api/k8s/secrets/shop/db/consumers").exchange().assertThat().hasStatusOk();

		verify(configMaps).consumers("config_maps", "shop", "settings");
		verify(configMaps).consumers("secrets", "shop", "db");
	}

	@Test
	void fixedPathsWinOverGenericTypeRoutes() {
		mvc.get().uri("/api/k8s/configmaps/unused").exchange().assertThat().hasStatusOk();
		mvc.get().uri("/api/k8s/secrets/unused").exchange().assertThat().hasStatusOk();
		mvc.get().uri("/api/k8s/nodes/load").exchange().assertThat().hasStatusOk();
		mvc.get().uri("/api/k8s/services/without-backends").exchange().assertThat().hasStatusOk();

		verify(configMaps).unused();
		verify(secrets).unused();
		verify(nodes).load();
		verify(services).withoutBackends();
		verify(queries, never()).find(any(), any(), anyString());
	}

	@Test
	void pathRequiresBothEnds() {
		when(queries.exists("services/shop.web")).thenReturn(true);
		when(queries.exists("config_maps/shop.gone")).thenReturn(false);

		mvc.get().uri("/api/k8s/path?from=services/shop.web&to=config_maps/shop.gone").exchange().assertThat()
				.hasStatus(HttpStatus.NOT_FOUND).bodyJson()
				.hasPathSatisfying("$.detail", v -> v.assertThat().isEqualTo("config_maps/shop.gone nicht gefunden"));
		verify(pods, never()).path(anyString(), anyString());
	}

	@Test
	void listChecksNamespaceAndScope() {
		when(queries.exists(ResourceType.NAMESPACES, null, "shop")).thenReturn(true);
		doReturn(List.of(new Pod("shop", "web-0"))).when(queries).list(ResourceType.PODS, "shop");

		mvc.get().uri("/api/k8s/pods?namespace=shop").exchange().assertThat().hasStatusOk().bodyJson()
				.hasPathSatisfying("$[0].name", v -> v.assertThat().isEqualTo("web-0"));
		mvc.get().uri("/api/k8s/pods?namespace=nope").exchange().assertThat().hasStatus(HttpStatus.NOT_FOUND);
		mvc.get().uri("/api/k8s/nodes?namespace=shop").exchange().assertThat().hasStatus(HttpStatus.BAD_REQUEST);
		mvc.get().uri("/api/k8s/widgets").exchange().assertThat().hasStatus(HttpStatus.NOT_FOUND).bodyJson()
				.hasPathSatisfying("$.detail", v -> v.assertThat().isEqualTo("Unbekannter Typ: widgets"));
	}

	@Test
	void singleResourceRoutesRespectScope() {
		doReturn(Optional.of(new Node(null, "worker-1"))).when(queries).find(ResourceType.NODES, null, "worker-1");
		doReturn(Optional.empty()).when(queries).find(ResourceType.PODS, "shop", "gone");

		mvc.get().uri("/api/k8s/nodes/worker-1").exchange().assertThat().hasStatusOk().bodyJson()
				.hasPathSatisfying("$.name", v -> v.assertThat().isEqualTo("worker-1"));
		mvc.get().uri("/api/k8s/pods/web-0").exchange().assertThat().hasStatus(HttpStatus.NOT_FOUND);
		mvc.get().uri("/api/k8s/nodes/shop/worker-1").exchange().assertThat().hasStatus(HttpStatus.NOT_FOUND);
		mvc.get().uri("/api/k8s/pods/shop/gone").exchange().assertThat().hasStatus(HttpStatus.NOT_FOUND).bodyJson()
				.hasPathSatisfying("$.detail", v -> v.assertThat().isEqualTo("pods/shop.gone nicht gefunden"));
	}

	@Test
	void changesAcceptsDateAndOffsetTimestamps() {
		when(queries.changes(any(), any())).thenReturn(new ChangeReport("x", List.of(), List.of()));

		mvc.get().uri("/api/k8s/changes?since=2026-11-12").exchange().assertThat().hasStatusOk();
		mvc.get().uri("/api/k8s/changes").param("since", "2026-11-12T09:00:00+01:00").exchange().assertThat().hasStatusOk();
		mvc.get().uri("/api/k8s/changes?since=gestern").exchange().assertThat().hasStatus(HttpStatus.BAD_REQUEST);

		verify(queries).changes(Instant.parse("2026-11-12T00:00:00Z"), null);
		verify(queries).changes(Instant.parse("2026-11-12T08:00:00Z"), null);
	}
}
