package com.example.arrango.repository.k8s;

import com.arangodb.springframework.annotation.Query;
import com.arangodb.springframework.repository.ArangoRepository;
import com.example.arrango.model.k8s.Pod;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PodRepository extends ArangoRepository<Pod, String> {

	List<Pod> findByNamespace(String namespace);

	List<Pod> findByNodeName(String nodeName);

	/** Pods, die ein Service per Label-Selector erreicht. */
	@Query("""
			FOR pod IN 1 OUTBOUND CONCAT('services/', @namespace, '.', @name) selects
			  RETURN pod
			""")
	List<Pod> backendsOfService(@Param("namespace") String namespace, @Param("name") String name);

	/**
	 * Kürzester Weg zwischen zwei beliebigen Ressourcen im Named Graph {@code k8s}, ungeachtet der
	 * Kantenrichtung. Beispiel: {@code services/istio-system.istiod} → {@code config_maps/istio-system.istio}.
	 */
	@Query("""
			FOR v IN ANY SHORTEST_PATH @from TO @to GRAPH 'k8s'
			  RETURN { id: v._id, kind: v.kind, namespace: v.namespace, name: v.name }
			""")
	List<ResourceRef> path(@Param("from") String from, @Param("to") String to);
}
