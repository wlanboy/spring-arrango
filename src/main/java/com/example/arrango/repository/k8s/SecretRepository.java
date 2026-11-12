package com.example.arrango.repository.k8s;

import com.arangodb.springframework.annotation.Query;
import com.arangodb.springframework.repository.ArangoRepository;
import com.example.arrango.model.k8s.Secret;

import java.util.List;

public interface SecretRepository extends ArangoRepository<Secret, String> {

	/**
	 * Secrets, die von keinem Pod gemountet oder per env gelesen werden. Helm-Release-Secrets und Tokens, die
	 * Kubernetes selbst verwaltet, sind ausgenommen, weil sie nie über einen Pod benutzt werden.
	 */
	@Query("""
			FOR s IN secrets
			  FILTER s.type NOT IN ['helm.sh/release.v1', 'kubernetes.io/service-account-token',
			                        'bootstrap.kubernetes.io/token']
			  FILTER LENGTH(FOR pod IN 1 INBOUND s mounts, references LIMIT 1 RETURN 1) == 0
			  SORT s.namespace, s.name
			  RETURN s
			""")
	List<Secret> unused();
}
