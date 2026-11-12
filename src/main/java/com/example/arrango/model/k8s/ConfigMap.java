package com.example.arrango.model.k8s;

import com.arangodb.springframework.annotation.Document;

import java.util.List;

/** v1 ConfigMap. Gespeichert werden nur die Schlüssel, nicht die Werte. */
@Document("config_maps")
public class ConfigMap extends K8sResource {

	private List<String> dataKeys;

	public ConfigMap() {
	}

	public ConfigMap(String namespace, String name) {
		super("ConfigMap", namespace, name);
	}

	public List<String> getDataKeys() {
		return dataKeys;
	}

	public void setDataKeys(List<String> dataKeys) {
		this.dataKeys = dataKeys;
	}
}
