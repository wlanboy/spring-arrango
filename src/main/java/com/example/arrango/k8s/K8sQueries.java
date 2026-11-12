package com.example.arrango.k8s;

import com.arangodb.springframework.core.ArangoOperations;
import com.example.arrango.model.k8s.K8sResource;
import com.example.arrango.model.k8s.Pod;
import com.example.arrango.repository.k8s.ChangeReport;
import com.example.arrango.repository.k8s.ImageUsage;
import com.example.arrango.repository.k8s.SeenRef;
import com.example.arrango.repository.k8s.UnhealthyReport;
import com.example.arrango.repository.k8s.WorkloadHealth;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Abfragen über mehrere oder beliebige Collections, die sich nicht an ein einzelnes Repository hängen lassen.
 * Alle Methoden mit {@code namespace}-Parameter filtern nur, wenn er nicht {@code null} ist.
 */
@Component
public class K8sQueries {

	private static final String LIST = """
			FOR d IN @@collection
			  FILTER @namespace == null OR d.namespace == @namespace
			  SORT d.namespace, d.name
			  RETURN d
			""";

	/** {@code firstSeen}/{@code lastSeen} haben je nach JVM unterschiedlich viele Nachkommastellen, daher als Zahl. */
	private static final String SEEN = """
			FOR d IN @@collection
			  FILTER d.lastSeen != null
			  FILTER @namespace == null OR d.namespace == @namespace
			  FILTER DATE_TIMESTAMP(d.firstSeen) >= @since OR DATE_TIMESTAMP(d.lastSeen) < @since
			  SORT d.firstSeen DESC
			  RETURN { id: d._id, kind: d.kind, namespace: d.namespace, name: d.name,
			           firstSeen: d.firstSeen, lastSeen: d.lastSeen }
			""";

	private static final String UNHEALTHY_WORKLOADS = """
			FOR w IN UNION(
			    (FOR d IN deployments FILTER d.readyReplicas < d.replicas
			       RETURN { d, desired: d.replicas, ready: d.readyReplicas }),
			    (FOR s IN stateful_sets FILTER s.readyReplicas < s.replicas
			       RETURN { d: s, desired: s.replicas, ready: s.readyReplicas }),
			    (FOR ds IN daemon_sets FILTER ds.ready < ds.desired
			       RETURN { d: ds, desired: ds.desired, ready: ds.ready }),
			    (FOR j IN jobs FILTER j.status == 'Failed'
			       RETURN { d: j, desired: j.completions, ready: j.succeeded, status: j.status }))
			  FILTER @namespace == null OR w.d.namespace == @namespace
			  SORT w.d.namespace, w.d.kind, w.d.name
			  RETURN { id: w.d._id, kind: w.d.kind, namespace: w.d.namespace, name: w.d.name,
			           desired: w.desired, ready: w.ready, status: w.status }
			""";

	/** {@code Succeeded} ist ein normales Ende (Job-Pods) und zählt nicht als ungesund. */
	private static final String UNHEALTHY_PODS = """
			FOR p IN pods
			  FILTER p.phase NOT IN ['Running', 'Succeeded']
			  FILTER @namespace == null OR p.namespace == @namespace
			  SORT p.namespace, p.name
			  RETURN p
			""";

	private static final String IMAGES = """
			FOR p IN pods
			  FILTER @namespace == null OR p.namespace == @namespace
			  FOR image IN UNIQUE(p.images || [])
			    COLLECT img = image INTO group = p.namespace
			    SORT img
			    RETURN { image: img, podCount: LENGTH(group), namespaces: SORTED_UNIQUE(group) }
			""";

	/** Exakter Treffer oder Repository ohne Tag/Digest: {@code nginx} findet {@code nginx:1.27} und {@code nginx@sha256:…}. */
	private static final String PODS_BY_IMAGE = """
			FOR p IN pods
			  FILTER @namespace == null OR p.namespace == @namespace
			  FILTER LENGTH(FOR i IN p.images || []
			      FILTER i == @image OR STARTS_WITH(i, CONCAT(@image, ':')) OR STARTS_WITH(i, CONCAT(@image, '@'))
			      LIMIT 1 RETURN 1) > 0
			  SORT p.namespace, p.name
			  RETURN p
			""";

	private final ArangoOperations operations;

	public K8sQueries(ArangoOperations operations) {
		this.operations = operations;
	}

	public List<? extends K8sResource> list(ResourceType type, String namespace) {
		var params = params(namespace);
		params.put("@collection", type.collection());
		return operations.query(LIST, params, type.entityClass()).asListRemaining();
	}

	public Optional<? extends K8sResource> find(ResourceType type, String namespace, String name) {
		return operations.find(K8sResource.keyOf(namespace, name), type.entityClass());
	}

	public boolean exists(ResourceType type, String namespace, String name) {
		return operations.exists(K8sResource.keyOf(namespace, name), type.entityClass());
	}

	/** {@code id} in der Form {@code <collection>/<key>}, z. B. {@code pods/default.web-0}. */
	public boolean exists(String id) {
		int slash = id.indexOf('/');
		if (slash < 1) {
			return false;
		}
		return ResourceType.fromCollection(id.substring(0, slash))
				.map(t -> operations.exists(id.substring(slash + 1), t.entityClass()))
				.orElse(false);
	}

	public ChangeReport changes(Instant since, String namespace) {
		var added = new ArrayList<SeenRef>();
		var stale = new ArrayList<SeenRef>();
		var params = params(namespace);
		params.put("since", since.toEpochMilli());
		for (var type : ResourceType.values()) {
			params.put("@collection", type.collection());
			for (var ref : operations.query(SEEN, params, SeenRef.class)) {
				if (Instant.parse(ref.firstSeen()).isBefore(since)) {
					stale.add(ref);
				}
				else {
					added.add(ref);
				}
			}
		}
		return new ChangeReport(since.toString(), added, stale);
	}

	public UnhealthyReport unhealthy(String namespace) {
		var params = params(namespace);
		return new UnhealthyReport(
				operations.query(UNHEALTHY_WORKLOADS, params, WorkloadHealth.class).asListRemaining(),
				operations.query(UNHEALTHY_PODS, params, Pod.class).asListRemaining());
	}

	public List<ImageUsage> images(String namespace) {
		return operations.query(IMAGES, params(namespace), ImageUsage.class).asListRemaining();
	}

	public List<Pod> podsByImage(String image, String namespace) {
		var params = params(namespace);
		params.put("image", image);
		return operations.query(PODS_BY_IMAGE, params, Pod.class).asListRemaining();
	}

	/** {@link HashMap}, weil {@code namespace} {@code null} sein darf. */
	private static Map<String, Object> params(String namespace) {
		var params = new HashMap<String, Object>();
		params.put("namespace", namespace);
		return params;
	}
}
