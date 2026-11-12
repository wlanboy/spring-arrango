package com.example.arrango.model.k8s;

import com.arangodb.springframework.annotation.Edge;

/** Pod → ServiceAccount. */
@Edge("uses_service_account")
public class UsesServiceAccount extends K8sEdge {

	public UsesServiceAccount() {
	}

	public UsesServiceAccount(String from, String to) {
		super(from, to);
	}
}
