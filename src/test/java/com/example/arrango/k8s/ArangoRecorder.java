package com.example.arrango.k8s;

import com.arangodb.ArangoCursor;
import com.arangodb.ArangoDB;
import com.arangodb.ArangoDatabase;
import com.arangodb.ArangoGraph;
import com.arangodb.ArangoIterator;
import com.arangodb.springframework.annotation.Document;
import com.arangodb.springframework.annotation.Edge;
import com.arangodb.springframework.core.ArangoOperations;
import com.arangodb.springframework.core.CollectionOperations;
import com.arangodb.springframework.core.convert.ArangoConverter;
import com.fasterxml.jackson.databind.node.POJONode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.mockito.ArgumentMatchers;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ArangoOperations}-Attrappe für den Importer: merkt sich, welche Dokumente per UPSERT geschrieben und mit
 * welchen Parametern die Aufräum-Queries aufgerufen wurden. Der Converter verpackt Entities unverändert in einen
 * {@link POJONode}, damit Tests die geschriebenen Objekte direkt prüfen können.
 * <p>
 * Die gespeicherten Keys pro Collection überleben mehrere Importe, sodass ein zweiter Lauf {@code updated} statt
 * {@code inserted} zählt. Das AQL selbst wird nicht ausgeführt.
 */
class ArangoRecorder {

	final ArangoOperations operations = mock(ArangoOperations.class);
	final ArangoDatabase database = mock(ArangoDatabase.class);
	final ArangoGraph graph = mock(ArangoGraph.class);

	/** Collection → Dokumente des letzten UPSERT. */
	final Map<String, List<Object>> upserted = new HashMap<>();
	/** Collection → Parameter der letzten Aufräum-Query. */
	final Map<String, Map<String, Object>> removeParams = new HashMap<>();

	private final Map<String, Set<String>> storedKeys = new HashMap<>();

	ArangoRecorder() {
		var converter = mock(ArangoConverter.class);
		when(converter.write(any())).thenAnswer(inv -> new POJONode(inv.getArgument(0)));
		when(operations.getConverter()).thenReturn(converter);

		when(operations.collection(ArgumentMatchers.<Class<?>>any())).thenAnswer(inv -> {
			var collection = mock(CollectionOperations.class);
			var name = collectionName(inv.getArgument(0));
			when(collection.name()).thenReturn(name);
			return collection;
		});

		var driver = mock(ArangoDB.class);
		when(operations.driver()).thenReturn(driver);
		when(driver.db(anyString())).thenReturn(database);
		when(database.graph(ClusterGraphImporter.GRAPH)).thenReturn(graph);

		when(operations.query(anyString(), anyMap(), ArgumentMatchers.<Class<Object>>any())).thenAnswer(inv -> {
			String query = inv.getArgument(0);
			Map<String, Object> params = inv.getArgument(1);
			var collection = (String) params.get("@collection");
			if (query.contains("UPSERT")) {
				return cursor(upsert(collection, (List<?>) params.get("docs")));
			}
			removeParams.put(collection, Map.copyOf(params.entrySet().stream()
					.filter(e -> e.getValue() != null)
					.collect(HashMap::new, (m, e) -> m.put(e.getKey(), e.getValue()), HashMap::putAll)));
			return cursor(List.of());
		});
	}

	@SuppressWarnings("unchecked")
	<T> List<T> docs(String collection) {
		return (List<T>) upserted.getOrDefault(collection, List.of());
	}

	private List<Boolean> upsert(String collection, List<?> docs) {
		var stored = storedKeys.computeIfAbsent(collection, k -> new HashSet<>());
		var objects = new ArrayList<>();
		var isNew = new ArrayList<Boolean>();
		for (Object doc : docs) {
			Object pojo = ((POJONode) doc).getPojo();
			objects.add(pojo);
			isNew.add(stored.add(keyOf(pojo)));
		}
		upserted.put(collection, objects);
		return isNew;
	}

	private static String keyOf(Object pojo) {
		if (pojo instanceof com.example.arrango.model.k8s.K8sResource r) {
			return r.getKey();
		}
		return ((com.example.arrango.model.k8s.K8sEdge) pojo).getKey();
	}

	static String collectionName(Class<?> type) {
		var document = type.getAnnotation(Document.class);
		if (document != null) {
			return document.value();
		}
		return type.getAnnotation(Edge.class).value();
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
