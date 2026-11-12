package com.example.arrango.model.k8s;

import com.arangodb.springframework.annotation.Edge;

/** Pod → ConfigMap/Secret über Umgebungsvariablen ({@code env.valueFrom} oder {@code envFrom}). */
@Edge("references")
public class References extends K8sEdge {

	private String envVar;

	private String key;

	private boolean envFrom;

	public References() {
	}

	public References(String from, String to) {
		super(from, to);
	}

	public String getEnvVar() {
		return envVar;
	}

	public void setEnvVar(String envVar) {
		this.envVar = envVar;
	}

	public String getKey() {
		return key;
	}

	public void setKey(String key) {
		this.key = key;
	}

	public boolean isEnvFrom() {
		return envFrom;
	}

	public void setEnvFrom(boolean envFrom) {
		this.envFrom = envFrom;
	}
}
