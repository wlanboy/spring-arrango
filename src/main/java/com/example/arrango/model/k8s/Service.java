package com.example.arrango.model.k8s;

import com.arangodb.springframework.annotation.Document;

import java.util.List;
import java.util.Map;

/** v1 Service. */
@Document("services")
public class Service extends K8sResource {

	private String type;

	private String clusterIp;

	private String externalName;

	private Map<String, String> selector;

	private List<String> ports;

	public Service() {
	}

	public Service(String namespace, String name) {
		super("Service", namespace, name);
	}

	public String getType() {
		return type;
	}

	public void setType(String type) {
		this.type = type;
	}

	public String getClusterIp() {
		return clusterIp;
	}

	public void setClusterIp(String clusterIp) {
		this.clusterIp = clusterIp;
	}

	public String getExternalName() {
		return externalName;
	}

	public void setExternalName(String externalName) {
		this.externalName = externalName;
	}

	public Map<String, String> getSelector() {
		return selector;
	}

	public void setSelector(Map<String, String> selector) {
		this.selector = selector;
	}

	public List<String> getPorts() {
		return ports;
	}

	public void setPorts(List<String> ports) {
		this.ports = ports;
	}
}
