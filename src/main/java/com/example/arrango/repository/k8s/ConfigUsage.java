package com.example.arrango.repository.k8s;

/**
 * Wer nutzt eine ConfigMap bzw. ein Secret und wie.
 *
 * @param workload Deployment/StatefulSet/DaemonSet des Pods, {@code null} bei nackten Pods
 * @param via      {@code mounts} oder {@code references}
 * @param detail   Volume-Name, Umgebungsvariable oder {@code envFrom}
 */
public record ConfigUsage(ResourceRef pod, ResourceRef workload, ResourceRef config, String via, String detail) {
}
