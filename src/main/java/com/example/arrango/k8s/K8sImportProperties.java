package com.example.arrango.k8s;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * Steuerung des Kubernetes-Imports.
 *
 * @param onStartup  beim Start einmal den Cluster importieren
 * @param namespaces nur diese Namespaces importieren; leer = alle (Nodes und PVs sind immer dabei)
 */
@ConfigurationProperties(prefix = "k8s.import")
public record K8sImportProperties(
		@DefaultValue("false") boolean onStartup,
		@DefaultValue List<String> namespaces) {
}
