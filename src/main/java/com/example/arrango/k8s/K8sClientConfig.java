package com.example.arrango.k8s;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Kubernetes-Client nach den üblichen fabric8-Regeln: im Pod der ServiceAccount, lokal
 * {@code ~/.kube/config} bzw. {@code KUBECONFIG} mit dem aktuellen Kontext.
 * Verbindet sich erst beim ersten Aufruf.
 */
@Configuration
@EnableConfigurationProperties(K8sImportProperties.class)
public class K8sClientConfig {

	@Bean(destroyMethod = "close")
	KubernetesClient kubernetesClient() {
		return new KubernetesClientBuilder().build();
	}
}
