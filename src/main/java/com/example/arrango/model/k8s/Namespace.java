package com.example.arrango.model.k8s;

import com.arangodb.springframework.annotation.Document;

/** v1 Namespace (cluster-weit, Key = Name). */
@Document("namespaces")
public class Namespace extends K8sResource {

	private String phase;

	public Namespace() {
	}

	public Namespace(String name) {
		super("Namespace", null, name);
	}

	public String getPhase() {
		return phase;
	}

	public void setPhase(String phase) {
		this.phase = phase;
	}
}
