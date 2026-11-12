# Spring Boot template
Spring 4 + webmvc + actuator + arrangodb client

Minimal Spring Boot service that connects to ArangoDB via Spring Data ArangoDB. It contains an example `Connection` document (collection `connections`) with a repository, an actuator health indicator for the ArangoDB connection, and a lean Tomcat configuration.

## Configuration
| Variable | Default |
|---|---|
| `ARANGODB_HOST` | `localhost` |
| `ARANGODB_PORT` | `8529` |
| `ARANGODB_USER` | `root` |
| `ARANGODB_PASSWORD` | _(empty)_ |
| `ARANGODB_DATABASE` | `arrango` |
| `SERVER_PORT` | `8080` |

Health: `GET /actuator/health`


## Kubernetes graph example
The app can read a Kubernetes cluster (current kubeconfig context, or the ServiceAccount when running in a pod) and store it as a graph in ArangoDB.

Imports are incremental, both on startup and through `POST /api/k8s/import`:

- Vertices and edges have stable keys and are upserted. Each document carries `firstSeen` (kept across imports) and `lastSeen` (timestamp of the last import that saw it).
- Only documents the current import no longer sees are removed, and only within its scope: with `K8S_IMPORT_NAMESPACES` set, other namespaces stay untouched. Cluster-scoped resources (namespaces, nodes, storage classes, PVs) are always in scope.
- Helm releases keep a `history` of all revisions ever imported (revision, status, chart version, app version, deploy time), including revisions whose secrets Helm has since pruned.

The import returns `inserted`/`updated`/`removed` per collection.

Vertex collections: `namespaces`, `helm_releases`, `deployments`, `stateful_sets`, `daemon_sets`, `replica_sets`, `cron_jobs`, `jobs` (status `Complete`/`Failed`/`Suspended`/`Running`), `pods`, `services`, `config_maps`, `secrets` (type and key names only, never values), `service_accounts`, `nodes`, `persistent_volume_claims`, `persistent_volumes`, `storage_classes` (with `defaultClass`).

| Edge collection | From → To | Source in Kubernetes |
|---|---|---|
| `contains` | Namespace → every namespaced resource | `metadata.namespace` |
| `manages` | HelmRelease → resource | annotation `meta.helm.sh/release-name` |
| `owns` | Deployment → ReplicaSet → Pod, DaemonSet/StatefulSet → Pod, CronJob → Job → Pod | `metadata.ownerReferences` |
| `mounts` | Pod → ConfigMap / Secret / PVC | `spec.volumes` (incl. projected), with volume name and mount paths |
| `references` | Pod → ConfigMap / Secret | `env.valueFrom`, `envFrom` |
| `selects` | Service → Pod | label selector |
| `scheduled_on` | Pod → Node | `spec.nodeName` |
| `uses_service_account` | Pod → ServiceAccount | `spec.serviceAccountName` |
| `binds` | PVC → PV | `spec.volumeName` |
| `uses_storage_class` | PVC / PV → StorageClass | `spec.storageClassName` |

All collections are part of the named graph `k8s`, so the ArangoDB web UI can render it (Graphs → k8s).

| Variable | Default | |
|---|---|---|
| `K8S_IMPORT_ON_STARTUP` | `true` | import once when the app is ready |
| `K8S_IMPORT_NAMESPACES` | _(all)_ | comma-separated list; namespaces, nodes, storage classes and PVs are always imported |

Endpoints:

```bash
curl -X POST localhost:8080/api/k8s/import                                   # sync the graph with the cluster
curl localhost:8080/api/k8s/releases                                         # Helm releases incl. chart, version and revision history
curl localhost:8080/api/k8s/releases/istio-system/istiod/resources           # release → deployment → replicaset → pod
curl localhost:8080/api/k8s/releases/istio-system/istiod/config              # ConfigMaps/Secrets used by the release's pods
curl localhost:8080/api/k8s/configmaps/kube-system/kube-proxy/consumers      # impact: which pods/workloads use it
curl localhost:8080/api/k8s/secrets/<ns>/<name>/consumers
curl localhost:8080/api/k8s/configmaps/unused                                # not mounted or referenced by any pod
curl localhost:8080/api/k8s/services/istio-system/istiod/backends            # pods behind a service
curl localhost:8080/api/k8s/services/without-backends                        # selector matches no pod
curl localhost:8080/api/k8s/nodes/load                                       # pods per node
curl "localhost:8080/api/k8s/path?from=services/istio-system.istiod&to=config_maps/istio-system.istio"
```

Document keys are `<namespace>.<name>` (cluster-scoped: `<name>`), so ids look like `pods/istio-system.istiod-f8b75d4cf-qswkc`. The AQL behind the endpoints is in [repository/k8s](src/main/java/com/example/arrango/repository/k8s).

"Unused" only covers mounts and env references. A ConfigMap that a controller reads through the API (for example `istio-system/istio`, read by istiod) still shows up as unused.

Running the import inside the cluster needs a ClusterRole with `get`/`list` on namespaces, nodes, persistentvolumes, `storage.k8s.io` storageclasses, namespaced pods, services, configmaps, secrets, serviceaccounts, persistentvolumeclaims and on `apps` deployments, statefulsets, daemonsets, replicasets and on `batch` jobs, cronjobs. Secrets are needed for Helm releases (`sh.helm.release.v1.*`). The `POST /api/k8s/import` endpoint has no authentication.
