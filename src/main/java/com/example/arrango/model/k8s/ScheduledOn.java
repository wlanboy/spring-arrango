package com.example.arrango.model.k8s;

import com.arangodb.springframework.annotation.Edge;

/** Pod → Node, auf dem der Pod läuft. */
@Edge("scheduled_on")
public class ScheduledOn extends K8sEdge {

	public ScheduledOn() {
	}

	public ScheduledOn(String from, String to) {
		super(from, to);
	}
}
