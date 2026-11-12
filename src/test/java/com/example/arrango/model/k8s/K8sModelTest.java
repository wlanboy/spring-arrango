package com.example.arrango.model.k8s;

import com.arangodb.springframework.annotation.Document;
import com.arangodb.springframework.annotation.Edge;
import com.example.arrango.k8s.ResourceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class K8sModelTest {

	/** Alle Edge-Klassen; neue Kanten hier ergänzen. */
	private static final List<Class<? extends K8sEdge>> EDGES = List.of(Contains.class, Manages.class, Owns.class,
			Mounts.class, References.class, Selects.class, ScheduledOn.class, UsesServiceAccount.class, Binds.class,
			UsesStorageClass.class);

	@Test
	void keyIsNamespaceDotNameOrJustNameWhenClusterScoped() {
		assertThat(K8sResource.keyOf("kube-system", "coredns")).isEqualTo("kube-system.coredns");
		assertThat(K8sResource.keyOf(null, "worker-1")).isEqualTo("worker-1");
		assertThat(K8sResource.keyOf("", "worker-1")).isEqualTo("worker-1");
		assertThat(new Pod("shop", "web.v2-0").getKey()).isEqualTo("shop.web.v2-0");
	}

	/**
	 * Ein Feld {@code key} oder {@code id} in einer Unterklasse verdeckt {@code _key}/{@code _id} der Basisklasse:
	 * Der Converter schreibt dann kein {@code _key}, und {@code UPSERT} legt bei jedem Import neue Dokumente an.
	 */
	@ParameterizedTest
	@ValueSource(strings = { "key", "id" })
	void subclassesDoNotShadowKeyOrId(String field) {
		var classes = new java.util.ArrayList<Class<?>>(EDGES);
		classes.addAll(ResourceType.entityClasses());
		for (var type : classes) {
			assertThat(Arrays.stream(type.getDeclaredFields()).map(java.lang.reflect.Field::getName))
					.as(type.getSimpleName()).doesNotContain(field);
			for (var accessor : List.of("get", "set")) {
				var name = accessor + Character.toUpperCase(field.charAt(0)) + field.substring(1);
				assertThat(Arrays.stream(type.getDeclaredMethods()).map(java.lang.reflect.Method::getName))
						.as(type.getSimpleName()).doesNotContain(name);
			}
		}
	}

	@Test
	void edgeIdentityDistinguishesParallelEdges() {
		var volumeA = new Mounts("pods/a.p", "config_maps/a.c");
		volumeA.setVolumeName("one");
		volumeA.setSource("configMap");
		var volumeB = new Mounts("pods/a.p", "config_maps/a.c");
		volumeB.setVolumeName("two");
		volumeB.setSource("configMap");
		assertThat(volumeA.identity()).isNotEqualTo(volumeB.identity());

		var env = new References("pods/a.p", "secrets/a.s");
		env.setEnvVar("PASSWORD");
		env.setDataKey("password");
		var envFrom = new References("pods/a.p", "secrets/a.s");
		envFrom.setEnvFrom(true);
		assertThat(env.identity()).isEqualTo("PASSWORD|password");
		assertThat(envFrom.identity()).isEqualTo("envFrom");

		assertThat(new Owns("a", "b").identity()).isEmpty();
	}

	@Test
	void edgeCollectionsAreUniqueAndConcrete() {
		assertThat(EDGES).allSatisfy(e -> assertThat(Modifier.isAbstract(e.getModifiers())).isFalse());
		assertThat(EDGES.stream().map(e -> e.getAnnotation(Edge.class).value())).doesNotHaveDuplicates();
	}

	@ParameterizedTest
	@EnumSource(ResourceType.class)
	void resourceTypeRoundTripsPathAndCollection(ResourceType type) {
		assertThat(type.collection()).isEqualTo(type.entityClass().getAnnotation(Document.class).value());
		assertThat(ResourceType.fromPath(type.path())).contains(type);
		assertThat(ResourceType.fromCollection(type.collection())).contains(type);
	}

	@Test
	void resourceTypesAreUniqueAndScopedLikeKubernetes() {
		assertThat(Arrays.stream(ResourceType.values()).map(ResourceType::path)).doesNotHaveDuplicates();
		assertThat(Arrays.stream(ResourceType.values()).map(ResourceType::collection)).doesNotHaveDuplicates();
		assertThat(Arrays.stream(ResourceType.values()).filter(ResourceType::clusterScoped))
				.containsExactlyInAnyOrder(ResourceType.NAMESPACES, ResourceType.NODES,
						ResourceType.PERSISTENT_VOLUMES, ResourceType.STORAGE_CLASSES);
		assertThat(ResourceType.fromPath("widgets")).isEmpty();
	}
}
