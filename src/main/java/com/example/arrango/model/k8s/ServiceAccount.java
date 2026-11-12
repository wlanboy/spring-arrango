package com.example.arrango.model.k8s;

import com.arangodb.springframework.annotation.Document;

/** v1 ServiceAccount. */
@Document("service_accounts")
public class ServiceAccount extends K8sResource {

	public ServiceAccount() {
	}

	public ServiceAccount(String namespace, String name) {
		super("ServiceAccount", namespace, name);
	}
}
