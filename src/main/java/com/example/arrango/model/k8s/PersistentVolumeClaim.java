package com.example.arrango.model.k8s;

import com.arangodb.springframework.annotation.Document;

/** v1 PersistentVolumeClaim. */
@Document("persistent_volume_claims")
public class PersistentVolumeClaim extends K8sResource {

	private String storageClass;

	private String requestedStorage;

	private String phase;

	private String volumeName;

	public PersistentVolumeClaim() {
	}

	public PersistentVolumeClaim(String namespace, String name) {
		super("PersistentVolumeClaim", namespace, name);
	}

	public String getStorageClass() {
		return storageClass;
	}

	public void setStorageClass(String storageClass) {
		this.storageClass = storageClass;
	}

	public String getRequestedStorage() {
		return requestedStorage;
	}

	public void setRequestedStorage(String requestedStorage) {
		this.requestedStorage = requestedStorage;
	}

	public String getPhase() {
		return phase;
	}

	public void setPhase(String phase) {
		this.phase = phase;
	}

	public String getVolumeName() {
		return volumeName;
	}

	public void setVolumeName(String volumeName) {
		this.volumeName = volumeName;
	}
}
