package com.example.arrango.model.k8s;

import com.arangodb.springframework.annotation.Document;

import java.util.List;
import java.util.Map;

/** apps/v1 Deployment. */
@Document("deployments")
public class Deployment extends K8sResource {

	private int replicas;

	private int readyReplicas;

	/** {@code spec.selector.matchLabels}. */
	private Map<String, String> selector;

	/** {@code spec.selector.matchExpressions}; leer, wenn der Selector nur matchLabels hat. */
	private List<SelectorRequirement> matchExpressions;

	private List<String> images;

	public Deployment() {
	}

	public Deployment(String namespace, String name) {
		super("Deployment", namespace, name);
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

	public Map<String, String> getSelector() {
		return selector;
	}

	public void setSelector(Map<String, String> selector) {
		this.selector = selector;
	}

	public List<SelectorRequirement> getMatchExpressions() {
		return matchExpressions;
	}

	public void setMatchExpressions(List<SelectorRequirement> matchExpressions) {
		this.matchExpressions = matchExpressions;
	}

	public List<String> getImages() {
		return images;
	}

	public void setImages(List<String> images) {
		this.images = images;
	}
}
