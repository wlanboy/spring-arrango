package com.example.arrango.model.k8s;

import com.arangodb.springframework.annotation.Document;

import java.util.List;

/** v1 Secret. Gespeichert werden nur Typ und Schlüssel, niemals die Werte. */
@Document("secrets")
public class Secret extends K8sResource {

	private String type;

	private List<String> dataKeys;

	public Secret() {
	}

	public Secret(String namespace, String name) {
		super("Secret", namespace, name);
	}

	public String getType() {
		return type;
	}

	public void setType(String type) {
		this.type = type;
	}

	public List<String> getDataKeys() {
		return dataKeys;
	}

	public void setDataKeys(List<String> dataKeys) {
		this.dataKeys = dataKeys;
	}
}
