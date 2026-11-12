package com.example.arrango.repository.k8s;

import com.example.arrango.model.k8s.Pod;

import java.util.List;

/** Workloads mit weniger bereiten als gewünschten Replicas und Pods, die weder laufen noch erfolgreich beendet sind. */
public record UnhealthyReport(List<WorkloadHealth> workloads, List<Pod> pods) {
}
