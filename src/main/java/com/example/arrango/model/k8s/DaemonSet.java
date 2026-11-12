package com.example.arrango.model.k8s;

import com.arangodb.springframework.annotation.Document;

import java.util.List;
import java.util.Map;

/** apps/v1 DaemonSet. */
@Document("daemon_sets")
public class DaemonSet extends K8sResource {

	private int desired;

	private int ready;

	/** {@code spec.selector.matchLabels}. */
	private Map<String, String> selector;

	/** {@code spec.selector.matchExpressions}; leer, wenn der Selector nur matchLabels hat. */
	private List<SelectorRequirement> matchExpressions;

	private List<String> images;

	public DaemonSet() {
	}

	public DaemonSet(String namespace, String name) {
		super("DaemonSet", namespace, name);
	}

	public int getDesired() {
		return desired;
	}

	public void setDesired(int desired) {
		this.desired = desired;
	}

	public int getReady() {
		return ready;
	}

	public void setReady(int ready) {
		this.ready = ready;
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
