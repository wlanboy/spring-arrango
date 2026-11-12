package com.example.arrango.model.k8s;

import com.arangodb.springframework.annotation.ArangoId;
import com.arangodb.springframework.annotation.Field;
import org.springframework.data.annotation.Id;

/**
 * Basis aller Kanten. {@code _from}/{@code _to} sind Dokument-IDs wie {@code pods/istio-system.istiod-abc},
 * weil eine Kante (z. B. {@code manages}) auf beliebige Ressource-Typen zeigen kann.
 * {@code @From}/{@code @To} würden ein Entity-Feld eines festen Typs verlangen, daher das direkte Mapping per
 * {@code @Field}.
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
}
