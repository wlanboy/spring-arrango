package com.example.arrango.model.k8s;

import com.arangodb.springframework.annotation.Document;

/** apps/v1 ReplicaSet, gehört über {@code owns} zu einem Deployment. */
@Document("replica_sets")
public class ReplicaSet extends K8sResource {

	private int replicas;

	private int readyReplicas;

	public ReplicaSet() {
	}

	public ReplicaSet(String namespace, String name) {
		super("ReplicaSet", namespace, name);
	}

	public int getReplicas() {
		return replicas;
	}

	public void setReplicas(int replicas) {
		this.replicas = replicas;
	}

	public int getReadyReplicas() {
		return readyReplicas;
	}

	public void setReadyReplicas(int readyReplicas) {
		this.readyReplicas = readyReplicas;
	}
}
