package com.example.arrango.model.k8s;

import com.arangodb.springframework.annotation.Edge;

/** PersistentVolumeClaim → PersistentVolume. */
@Edge("binds")
public class Binds extends K8sEdge {

	public Binds() {
	}

	public Binds(String from, String to) {
		super(from, to);
	}
}
