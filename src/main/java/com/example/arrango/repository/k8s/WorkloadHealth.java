package com.example.arrango.repository.k8s;

/**
 * Workload, der nicht vollständig bereit ist.
 *
 * @param desired gewünschte Replicas (DaemonSet: geplante Pods, Job: {@code completions})
 * @param ready   bereite Replicas (Job: erfolgreich beendete Pods)
 * @param status  nur bei Jobs: {@code Failed}
 */
public record WorkloadHealth(String id, String kind, String namespace, String name, Integer desired, int ready,
		String status) {
}
