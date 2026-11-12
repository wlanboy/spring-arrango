package com.example.arrango.model.k8s;

import com.arangodb.springframework.annotation.Document;

/** storage.k8s.io/v1 StorageClass (cluster-weit, Key = Name). */
@Document("storage_classes")
public class StorageClass extends K8sResource {

	private String provisioner;

	private String reclaimPolicy;

	private String volumeBindingMode;

	private boolean allowVolumeExpansion;

	/** Annotation {@code storageclass.kubernetes.io/is-default-class}. */
	private boolean defaultClass;

	public StorageClass() {
	}

	public StorageClass(String name) {
		super("StorageClass", null, name);
	}

	public String getProvisioner() {
		return provisioner;
	}

	public void setProvisioner(String provisioner) {
		this.provisioner = provisioner;
	}

	public String getReclaimPolicy() {
		return reclaimPolicy;
	}

	public void setReclaimPolicy(String reclaimPolicy) {
		this.reclaimPolicy = reclaimPolicy;
	}

	public String getVolumeBindingMode() {
		return volumeBindingMode;
	}

	public void setVolumeBindingMode(String volumeBindingMode) {
		this.volumeBindingMode = volumeBindingMode;
	}

	public boolean isAllowVolumeExpansion() {
		return allowVolumeExpansion;
	}

	public void setAllowVolumeExpansion(boolean allowVolumeExpansion) {
		this.allowVolumeExpansion = allowVolumeExpansion;
	}

	public boolean isDefaultClass() {
		return defaultClass;
	}

	public void setDefaultClass(boolean defaultClass) {
		this.defaultClass = defaultClass;
	}
}
