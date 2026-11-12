package com.example.arrango.repository.k8s;

import com.arangodb.springframework.annotation.Query;
import com.arangodb.springframework.repository.ArangoRepository;
import com.example.arrango.model.k8s.Node;

import java.util.List;

public interface NodeRepository extends ArangoRepository<Node, String> {

	/** Pods pro Node, gruppiert über die {@code scheduled_on}-Kanten. */
	@Query("""
			FOR n IN nodes
			  LET pods = (FOR p IN 1 INBOUND n scheduled_on RETURN p)
			  SORT n.name
			  RETURN { node: n.name, roles: n.roles, podCount: LENGTH(pods), namespaces: UNIQUE(pods[*].namespace) }
			""")
	List<NodeLoad> load();
}
