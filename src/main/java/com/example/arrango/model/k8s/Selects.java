package com.example.arrango.model.k8s;

import com.arangodb.springframework.annotation.Edge;

/** Service → Pod, dessen Labels zum Selector des Service passen. */
@Edge("selects")
public class Selects extends K8sEdge {

	public Selects() {
	}

	public Selects(String from, String to) {
		super(from, to);
	}
}
