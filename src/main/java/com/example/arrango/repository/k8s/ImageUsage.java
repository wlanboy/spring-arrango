package com.example.arrango.repository.k8s;

import java.util.List;

/** Ein Container-Image und die Pods, die damit laufen. */
public record ImageUsage(String image, int podCount, List<String> namespaces) {
}
