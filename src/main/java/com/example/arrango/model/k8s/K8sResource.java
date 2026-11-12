package com.example.arrango.model.k8s;

import com.arangodb.springframework.annotation.ArangoId;
import org.springframework.data.annotation.Id;

import java.util.Map;

/**
 * Gemeinsame Felder aller Kubernetes-Ressourcen im Graph.
 * Der Key ist {@code <namespace>.<name>} bzw. bei cluster-weiten Ressourcen nur {@code <name>},
 * damit ein erneuter Import dieselben Dokumente aktualisiert statt Duplikate anzulegen.
 */
public abstract class K8sResource {

	@Id
	private String key;

	@ArangoId
	private String id;

	private String kind;

	private String name;

	private String namespace;

	private String uid;

	private Map<String, String> labels;

	private String createdAt;

	/** Zeitpunkt des ersten Imports, der die Ressource gesehen hat; bleibt bei Updates erhalten. */
	private String firstSeen;

	/** Zeitpunkt des letzten Imports, der die Ressource gesehen hat. */
	private String lastSeen;

	protected K8sResource() {
	}

	protected K8sResource(String kind, String namespace, String name) {
		this.kind = kind;
		this.namespace = namespace;
		this.name = name;
		this.key = keyOf(namespace, name);
	}

	public static String keyOf(String namespace, String name) {
		return namespace == null || namespace.isEmpty() ? name : namespace + "." + name;
	}

	public String getKey() {
		return key;
	}

	public void setKey(String key) {
		this.key = key;
	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getKind() {
		return kind;
	}

	public void setKind(String kind) {
		this.kind = kind;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getNamespace() {
		return namespace;
	}

	public void setNamespace(String namespace) {
		this.namespace = namespace;
	}

	public String getUid() {
		return uid;
	}

	public void setUid(String uid) {
		this.uid = uid;
	}

	public Map<String, String> getLabels() {
		return labels;
	}

	public void setLabels(Map<String, String> labels) {
		this.labels = labels;
	}

	public String getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(String createdAt) {
		this.createdAt = createdAt;
	}

	public String getFirstSeen() {
		return firstSeen;
	}

	public void setFirstSeen(String firstSeen) {
		this.firstSeen = firstSeen;
	}

	public String getLastSeen() {
		return lastSeen;
	}

	public void setLastSeen(String lastSeen) {
		this.lastSeen = lastSeen;
	}
}
