package com.example.arrango.model.k8s;

import com.arangodb.springframework.annotation.Document;

import java.util.List;

/** v1 Pod. */
@Document("pods")
public class Pod extends K8sResource {

	private String phase;

	private String nodeName;

	private String podIp;

	private List<String> images;

	public Pod() {
	}

	public Pod(String namespace, String name) {
		super("Pod", namespace, name);
	}

	public String getPhase() {
		return phase;
	}

	public void setPhase(String phase) {
		this.phase = phase;
	}

	public String getNodeName() {
		return nodeName;
	}

	public void setNodeName(String nodeName) {
		this.nodeName = nodeName;
	}

	public String getPodIp() {
		return podIp;
	}

	public void setPodIp(String podIp) {
		this.podIp = podIp;
	}

	public List<String> getImages() {
		return images;
	}

	public void setImages(List<String> images) {
		this.images = images;
	}
}
