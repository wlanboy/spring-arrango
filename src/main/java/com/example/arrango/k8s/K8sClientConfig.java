package com.example.arrango.k8s;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

	private static final Logger log = LoggerFactory.getLogger(K8sClientConfig.class);

	@Bean(destroyMethod = "close")
	KubernetesClient kubernetesClient() {
		var client = new KubernetesClientBuilder().build();
		var config = client.getConfiguration();
		var context = config.getCurrentContext();
		log.info("Kubernetes-API {} (Kontext {})", config.getMasterUrl(),
				context != null ? context.getName() : "keiner, z. B. ServiceAccount im Pod");
		return client;
	}
}
