package com.example.arrango.repository.k8s;

import com.arangodb.springframework.annotation.Query;
import com.arangodb.springframework.repository.ArangoRepository;
import com.example.arrango.model.k8s.Service;

import java.util.List;

public interface ServiceRepository extends ArangoRepository<Service, String> {

	List<Service> findByType(String type);

	/** Services mit Selector, die aktuell keinen Pod treffen (kaputter Selector oder Workload weg). */
	@Query("""
			FOR s IN services
			  FILTER s.selector != null AND LENGTH(ATTRIBUTES(s.selector)) > 0
			  FILTER LENGTH(FOR p IN 1 OUTBOUND s selects LIMIT 1 RETURN 1) == 0
			  RETURN s
			""")
	List<Service> withoutBackends();
}
