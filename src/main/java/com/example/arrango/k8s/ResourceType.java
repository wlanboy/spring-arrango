package com.example.arrango.k8s;

import com.arangodb.springframework.annotation.Document;
import com.example.arrango.model.k8s.ConfigMap;
import com.example.arrango.model.k8s.CronJob;
import com.example.arrango.model.k8s.DaemonSet;
import com.example.arrango.model.k8s.Deployment;
import com.example.arrango.model.k8s.HelmRelease;
import com.example.arrango.model.k8s.Job;
import com.example.arrango.model.k8s.K8sResource;
import com.example.arrango.model.k8s.Namespace;
import com.example.arrango.model.k8s.Node;
import com.example.arrango.model.k8s.PersistentVolume;
import com.example.arrango.model.k8s.PersistentVolumeClaim;
import com.example.arrango.model.k8s.Pod;
import com.example.arrango.model.k8s.ReplicaSet;
import com.example.arrango.model.k8s.Secret;
import com.example.arrango.model.k8s.Service;
import com.example.arrango.model.k8s.ServiceAccount;
import com.example.arrango.model.k8s.StatefulSet;
import com.example.arrango.model.k8s.StorageClass;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Alle Vertex-Typen des Graphs mit ihrem URL-Segment (Plural wie bei {@code kubectl get}) und der Angabe, ob sie
 * cluster-weit sind. Der Importer legt die Collections in dieser Reihenfolge an.
 */
public enum ResourceType {

	NAMESPACES("namespaces", Namespace.class, true),
	HELM_RELEASES("releases", HelmRelease.class, false),
	DEPLOYMENTS("deployments", Deployment.class, false),
	STATEFUL_SETS("statefulsets", StatefulSet.class, false),
	DAEMON_SETS("daemonsets", DaemonSet.class, false),
	REPLICA_SETS("replicasets", ReplicaSet.class, false),
	CRON_JOBS("cronjobs", CronJob.class, false),
	JOBS("jobs", Job.class, false),
	PODS("pods", Pod.class, false),
	SERVICES("services", Service.class, false),
	CONFIG_MAPS("configmaps", ConfigMap.class, false),
	SECRETS("secrets", Secret.class, false),
	SERVICE_ACCOUNTS("serviceaccounts", ServiceAccount.class, false),
	NODES("nodes", Node.class, true),
	PERSISTENT_VOLUME_CLAIMS("persistentvolumeclaims", PersistentVolumeClaim.class, false),
	PERSISTENT_VOLUMES("persistentvolumes", PersistentVolume.class, true),
	STORAGE_CLASSES("storageclasses", StorageClass.class, true);

	private final String path;
	private final Class<? extends K8sResource> entityClass;
	private final boolean clusterScoped;

	ResourceType(String path, Class<? extends K8sResource> entityClass, boolean clusterScoped) {
		this.path = path;
		this.entityClass = entityClass;
		this.clusterScoped = clusterScoped;
	}

	public String path() {
		return path;
	}

	public Class<? extends K8sResource> entityClass() {
		return entityClass;
	}

	public boolean clusterScoped() {
		return clusterScoped;
	}

	/** Name der ArangoDB-Collection aus {@code @Document}. */
	public String collection() {
		return entityClass.getAnnotation(Document.class).value();
	}

	public static Optional<ResourceType> fromPath(String path) {
		return Arrays.stream(values()).filter(t -> t.path.equals(path)).findFirst();
	}

	public static Optional<ResourceType> fromCollection(String collection) {
		return Arrays.stream(values()).filter(t -> t.collection().equals(collection)).findFirst();
	}

	public static List<Class<? extends K8sResource>> entityClasses() {
		return Arrays.stream(values()).<Class<? extends K8sResource>>map(ResourceType::entityClass).toList();
	}
}
