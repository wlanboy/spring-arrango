package com.example.arrango.model.k8s;

import com.arangodb.springframework.annotation.Document;

/** v1 PersistentVolume (cluster-weit, Key = Name). */
@Document("persistent_volumes")
public class PersistentVolume extends K8sResource {

	private String capacity;

	private String storageClass;

	private String reclaimPolicy;

	private String phase;

	public PersistentVolume() {
	}

	public PersistentVolume(String namespace, String name) {
		super("PersistentVolume", namespace, name);
	}

	public String getCapacity() {
		return capacity;
	}

	public void setCapacity(String capacity) {
		this.capacity = capacity;
	}

	public String getStorageClass() {
		return storageClass;
	}

	public void setStorageClass(String storageClass) {
		this.storageClass = storageClass;
	}

	public String getReclaimPolicy() {
		return reclaimPolicy;
	}

	public void setReclaimPolicy(String reclaimPolicy) {
		this.reclaimPolicy = reclaimPolicy;
	}

	public String getPhase() {
		return phase;
	}

	public void setPhase(String phase) {
		this.phase = phase;
	}
}
