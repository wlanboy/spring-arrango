package com.example.arrango.model.k8s;

import com.arangodb.springframework.annotation.Edge;

/** Helm-Release → Ressource (Annotation {@code meta.helm.sh/release-name}). */
@Edge("manages")
public class Manages extends K8sEdge {

	public Manages() {
	}

	public Manages(String from, String to) {
		super(from, to);
	}
}
