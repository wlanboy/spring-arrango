package com.example.arrango.model.k8s;

import com.arangodb.springframework.annotation.Edge;

/** Namespace → jede Ressource in diesem Namespace. */
@Edge("contains")
public class Contains extends K8sEdge {

	public Contains() {
	}

	public Contains(String from, String to) {
		super(from, to);
	}
}
