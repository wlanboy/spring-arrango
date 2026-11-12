package com.example.arrango.repository.k8s;

import java.util.List;

public record NodeLoad(String node, List<String> roles, int podCount, List<String> namespaces) {
}
