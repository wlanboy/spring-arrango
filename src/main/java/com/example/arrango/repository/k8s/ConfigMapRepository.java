package com.example.arrango.repository.k8s;

import com.arangodb.springframework.annotation.Query;
import com.arangodb.springframework.repository.ArangoRepository;
import com.example.arrango.model.k8s.ConfigMap;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ConfigMapRepository extends ArangoRepository<ConfigMap, String> {

	List<ConfigMap> findByNamespace(String namespace);

	/**
	 * Impact-Analyse: Welche Pods und Workloads müssen neu starten, wenn sich die ConfigMap (oder das Secret,
	 * siehe {@code collection}) ändert? Geht rückwärts über {@code mounts}/{@code references} zum Pod und
	 * über {@code owns} hoch bis zum Deployment/StatefulSet/DaemonSet.
	 */
	@Query("""
			FOR pod, e IN 1 INBOUND CONCAT(@collection, '/', @namespace, '.', @name) mounts, references
			  LET workload = FIRST(
			    FOR o IN 1..2 INBOUND pod owns
			      FILTER IS_SAME_COLLECTION('deployments', o) OR IS_SAME_COLLECTION('stateful_sets', o)
			          OR IS_SAME_COLLECTION('daemon_sets', o)
			      RETURN { id: o._id, kind: o.kind, namespace: o.namespace, name: o.name })
			  LET config = DOCUMENT(e._to)
			  RETURN {
			    pod: { id: pod._id, kind: pod.kind, namespace: pod.namespace, name: pod.name },
			    workload,
			    config: { id: config._id, kind: config.kind, namespace: config.namespace, name: config.name },
			    via: PARSE_IDENTIFIER(e._id).collection,
			    detail: e.volumeName || e.envVar || (e.envFrom ? 'envFrom' : null)
			  }
			""")
	List<ConfigUsage> consumers(@Param("collection") String collection, @Param("namespace") String namespace,
			@Param("name") String name);

	/** ConfigMaps, die von keinem Pod benutzt werden (Kandidaten zum Aufräumen). */
	@Query("""
			FOR cm IN config_maps
			  FILTER cm.name != 'kube-root-ca.crt'
			  FILTER LENGTH(FOR pod IN 1 INBOUND cm mounts, references LIMIT 1 RETURN 1) == 0
			  SORT cm.namespace, cm.name
			  RETURN cm
			""")
	List<ConfigMap> unused();
}
