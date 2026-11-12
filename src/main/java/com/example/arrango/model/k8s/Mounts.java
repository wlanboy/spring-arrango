package com.example.arrango.model.k8s;

import com.arangodb.springframework.annotation.Edge;

import java.util.List;

/** Pod → ConfigMap/Secret/PersistentVolumeClaim über ein Volume. */
@Edge("mounts")
public class Mounts extends K8sEdge {

	private String volumeName;

	private String source;

	private List<String> mountPaths;

	private boolean optional;

	public Mounts() {
	}

	public Mounts(String from, String to) {
		super(from, to);
	}

	@Override
	public String identity() {
		return volumeName + "|" + source;
	}

	public String getVolumeName() {
		return volumeName;
	}

	public void setVolumeName(String volumeName) {
		this.volumeName = volumeName;
	}

	public String getSource() {
		return source;
	}

	public void setSource(String source) {
		this.source = source;
	}

	public List<String> getMountPaths() {
		return mountPaths;
	}

	public void setMountPaths(List<String> mountPaths) {
		this.mountPaths = mountPaths;
	}

	public boolean isOptional() {
		return optional;
	}

	public void setOptional(boolean optional) {
		this.optional = optional;
	}
}
