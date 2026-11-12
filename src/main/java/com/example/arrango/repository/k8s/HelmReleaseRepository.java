package com.example.arrango.repository.k8s;

import com.arangodb.springframework.annotation.Query;
import com.arangodb.springframework.repository.ArangoRepository;
import com.example.arrango.model.k8s.HelmRelease;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface HelmReleaseRepository extends ArangoRepository<HelmRelease, String> {

	List<HelmRelease> findByNamespace(String namespace);

	List<HelmRelease> findByChart(String chart);

	/**
	 * Alles, was zu einem Release gehört: direkt verwaltete Ressourcen ({@code manages}) und deren
	 * Kinder ({@code owns}), also Release → Deployment → ReplicaSet → Pod.
	 */
	@Query("""
			FOR v, e, p IN 1..3 OUTBOUND CONCAT('helm_releases/', @namespace, '.', @name) manages, owns
			  OPTIONS { uniqueVertices: 'global', order: 'bfs' }
			  RETURN { id: v._id, kind: v.kind, namespace: v.namespace, name: v.name, depth: LENGTH(p.edges) }
			""")
	List<ResourceRef> resources(@Param("namespace") String namespace, @Param("name") String name);

	/** Alle ConfigMaps und Secrets, die die Pods eines Releases mounten oder per env lesen. */
	@Query("""
			FOR pod IN 1..3 OUTBOUND CONCAT('helm_releases/', @namespace, '.', @name) manages, owns
			  OPTIONS { uniqueVertices: 'global', order: 'bfs' }
			  FILTER IS_SAME_COLLECTION('pods', pod)
			  FOR cfg, e IN 1 OUTBOUND pod mounts, references
			    COLLECT id = cfg._id, kind = cfg.kind, ns = cfg.namespace, cfgName = cfg.name
			    RETURN { id, kind, namespace: ns, name: cfgName }
			""")
	List<ResourceRef> configDependencies(@Param("namespace") String namespace, @Param("name") String name);
}
