package com.example.arrango.model.k8s;

import com.arangodb.springframework.annotation.Document;

import java.util.List;

/** v1 Node (cluster-weit, Key = Name). */
@Document("nodes")
public class Node extends K8sResource {

	private String kubeletVersion;

	private String internalIp;

	private List<String> roles;

	public Node() {
	}

	public Node(String namespace, String name) {
		super("Node", namespace, name);
	}

	public String getKubeletVersion() {
		return kubeletVersion;
	}

	public void setKubeletVersion(String kubeletVersion) {
		this.kubeletVersion = kubeletVersion;
	}

	public String getInternalIp() {
		return internalIp;
	}

	public void setInternalIp(String internalIp) {
		this.internalIp = internalIp;
	}

	public List<String> getRoles() {
		return roles;
	}

	public void setRoles(List<String> roles) {
		this.roles = roles;
	}
}
