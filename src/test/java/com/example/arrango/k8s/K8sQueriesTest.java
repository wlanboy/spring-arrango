package com.example.arrango.k8s;

import com.arangodb.ArangoCursor;
import com.arangodb.ArangoIterator;
import com.arangodb.springframework.core.ArangoOperations;
import com.example.arrango.model.k8s.Pod;
import com.example.arrango.repository.k8s.SeenRef;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class K8sQueriesTest {

	private final ArangoOperations operations = mock(ArangoOperations.class);
	private final K8sQueries queries = new K8sQueries(operations);

	@Test
	void existsResolvesCollectionOfDocumentId() {
		when(operations.exists("default.web-0", Pod.class)).thenReturn(true);

		assertThat(queries.exists("pods/default.web-0")).isTrue();
		assertThat(queries.exists("pods/default.other")).isFalse();
	}

	@Test
	void existsRejectsMalformedOrUnknownIds() {
		assertThat(queries.exists("default.web-0")).isFalse();
		assertThat(queries.exists("/default.web-0")).isFalse();
		assertThat(queries.exists("widgets/default.web-0")).isFalse();
		verify(operations, never()).exists(anyString(), eq(Pod.class));
	}

	@Test
	void findUsesNamespacedKey() {
		queries.find(ResourceType.PODS, "default", "web-0");
		queries.find(ResourceType.NODES, null, "worker-1");

		verify(operations).find("default.web-0", Pod.class);
		verify(operations).find("worker-1", com.example.arrango.model.k8s.Node.class);
	}

	@Test
	void listBindsCollectionAndOptionalNamespace() {
		ArangoCursor<Pod> empty = cursor(List.of());
		when(operations.query(anyString(), anyMap(), eq(Pod.class))).thenReturn(empty);

		queries.list(ResourceType.PODS, null);

		verify(operations).query(anyString(), argThat((Map<String, Object> p) -> "pods".equals(p.get("@collection"))
				&& p.containsKey("namespace") && p.get("namespace") == null), eq(Pod.class));
	}

	@Test
	void changesSplitsIntoAddedAndStaleByFirstSeen() {
		var since = Instant.parse("2026-11-12T08:00:00Z");
		var added = new SeenRef("pods/shop.new", "Pod", "shop", "new", "2026-11-12T09:00:00.123Z", "2026-11-12T09:00:00.123Z");
		var stale = new SeenRef("pods/shop.old", "Pod", "shop", "old", "2026-11-01T00:00:00Z", "2026-11-11T00:00:00Z");
		when(operations.query(anyString(), anyMap(), eq(SeenRef.class))).thenAnswer(inv -> {
			Map<String, Object> params = inv.getArgument(1);
			assertThat(params).containsEntry("since", since.toEpochMilli()).containsEntry("namespace", "shop");
			return cursor("pods".equals(params.get("@collection")) ? List.of(added, stale) : List.of());
		});

		var report = queries.changes(since, "shop");

		assertThat(report.since()).isEqualTo("2026-11-12T08:00:00Z");
		assertThat(report.added()).containsExactly(added);
		assertThat(report.stale()).containsExactly(stale);
		// eine Abfrage pro Vertex-Collection
		verify(operations, org.mockito.Mockito.times(ResourceType.values().length))
				.query(anyString(), anyMap(), eq(SeenRef.class));
	}

	@SuppressWarnings("unchecked")
	private static <T> ArangoCursor<T> cursor(List<T> items) {
		ArangoCursor<T> cursor = mock(ArangoCursor.class);
		when(cursor.asListRemaining()).thenReturn(items);
		when(cursor.iterator()).thenAnswer(inv -> {
			Iterator<T> it = items.iterator();
			return new ArangoIterator<T>() {
				@Override
				public boolean hasNext() {
					return it.hasNext();
				}

				@Override
				public T next() {
					return it.next();
				}
			};
		});
		return cursor;
	}
}
