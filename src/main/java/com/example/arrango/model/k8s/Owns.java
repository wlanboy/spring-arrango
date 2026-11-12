package com.example.arrango.model.k8s;

import com.arangodb.springframework.annotation.Edge;

/** Owner → abhängige Ressource aus {@code metadata.ownerReferences}, z. B. Deployment → ReplicaSet → Pod. */
@Edge("owns")
public class Owns extends K8sEdge {

	private boolean controller;

	public Owns() {
	}

	public Owns(String from, String to) {
		super(from, to);
	}

	public boolean isController() {
		return controller;
	}

	public void setController(boolean controller) {
		this.controller = controller;
	}
}
