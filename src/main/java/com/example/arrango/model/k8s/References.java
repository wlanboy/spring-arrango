package com.example.arrango.model.k8s;

import com.arangodb.springframework.annotation.Edge;

/** Pod → ConfigMap/Secret über Umgebungsvariablen ({@code env.valueFrom} oder {@code envFrom}). */
@Edge("references")
public class References extends K8sEdge {

	private String envVar;

	/** Key in der ConfigMap bzw. dem Secret ({@code valueFrom.*KeyRef.key}); {@code null} bei {@code envFrom}. */
	private String dataKey;

	private boolean envFrom;

	public References() {
	}

	public References(String from, String to) {
		super(from, to);
	}

	@Override
	public String identity() {
		return envFrom ? "envFrom" : envVar + "|" + dataKey;
	}

	public String getEnvVar() {
		return envVar;
	}

	public void setEnvVar(String envVar) {
		this.envVar = envVar;
	}

	public String getDataKey() {
		return dataKey;
	}

	public void setDataKey(String dataKey) {
		this.dataKey = dataKey;
	}

	public boolean isEnvFrom() {
		return envFrom;
	}

	public void setEnvFrom(boolean envFrom) {
		this.envFrom = envFrom;
	}
}
