package com.example.arrango.model.k8s;

import com.arangodb.springframework.annotation.Edge;

/** PersistentVolumeClaim / PersistentVolume → StorageClass ({@code spec.storageClassName}). */
@Edge("uses_storage_class")
public class UsesStorageClass extends K8sEdge {

	public UsesStorageClass() {
	}

	public UsesStorageClass(String from, String to) {
		super(from, to);
	}
}
