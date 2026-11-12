package com.example.arrango.model.k8s;

import com.arangodb.springframework.annotation.ArangoId;
import com.arangodb.springframework.annotation.Field;
import org.springframework.data.annotation.Id;

/**
 * Basis aller Kanten. {@code _from}/{@code _to} sind Dokument-IDs wie {@code pods/istio-system.istiod-abc},
 * weil eine Kante (z. B. {@code manages}) auf beliebige Ressource-Typen zeigen kann.
 * {@code @From}/{@code @To} würden ein Entity-Feld eines festen Typs verlangen, daher das direkte Mapping per
 * {@code @Field}.
 * <p>
 * Der Key wird beim Import aus {@code _from}, {@code _to} und {@link #identity()} abgeleitet, damit dieselbe Kante
 * bei jedem Import dasselbe Dokument trifft.
 */
public abstract class K8sEdge {

	@Id
	private String key;

	@ArangoId
	private String id;

	@Field("_from")
	private String from;

	@Field("_to")
	private String to;

	/** Namespace des Start-Vertex; {@code null} bei cluster-weiten Ressourcen. */
	private String namespace;

	private String firstSeen;

	private String lastSeen;

	protected K8sEdge() {
	}

	protected K8sEdge(String from, String to) {
		this.from = from;
		this.to = to;
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

	public String getFrom() {
		return from;
	}

	public void setFrom(String from) {
		this.from = from;
	}

	public String getTo() {
		return to;
	}

	public void setTo(String to) {
		this.to = to;
	}

	/**
	 * Unterscheidet mehrere Kanten desselben Typs zwischen denselben Vertices (z. B. zwei Volumes auf dieselbe
	 * ConfigMap). Standard: keine weitere Unterscheidung.
	 */
	public String identity() {
		return "";
	}

	public String getNamespace() {
		return namespace;
	}

	public void setNamespace(String namespace) {
		this.namespace = namespace;
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
