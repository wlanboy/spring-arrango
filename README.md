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

## Migrations
On startup, before the Kubernetes import, the app runs every `Migration` bean whose version is not yet recorded in the `migrations` collection, in ascending version order. Each successful migration is stored there with `_key` = version, description, `appliedAt` and `durationMs`. A failing migration stops the app from starting and runs again on the next start, so migrations should be idempotent.

To add one, create a `@Component` that implements `com.example.arrango.migration.Migration` with a new version number. Example: `V1ConnectionNameIndex` adds a persistent index on `connections.name`.

| Variable | Default | |
|---|---|---|
| `ARANGODB_MIGRATIONS_ON_STARTUP` | `true` | run pending migrations on startup |

This covers a single instance. If several pods start at the same time, a migration can run twice; the second pod then fails on the duplicate `_key` and restarts.


## Kubernetes graph example
The app can read a Kubernetes cluster (current kubeconfig context, or the ServiceAccount when running in a pod) and store it as a graph in ArangoDB.

Imports are incremental, both on startup and through `POST /api/k8s/import`:

- Vertices and edges have stable keys and are upserted. Each document carries `firstSeen` (kept across imports) and `lastSeen` (timestamp of the last import that saw it).
- Only documents the current import no longer sees are removed, and only within its scope: with `K8S_IMPORT_NAMESPACES` set, other namespaces stay untouched. Cluster-scoped resources (namespaces, nodes, storage classes, PVs) are always in scope.
- Helm releases keep a `history` of all revisions ever imported (revision, status, chart version, app version, deploy time), including revisions whose secrets Helm has since pruned.

- A resource type the import cannot read (for example no RBAC permission on secrets, or the API server is unreachable) does not abort the import. It shows up in `failures` with collection, namespace (`null` = whole collection) and error, and its existing documents, plus the edges touching them, are kept as they are. With `K8S_IMPORT_NAMESPACES` set, each namespace is read separately, so a failure only blocks that one namespace.

The import returns `inserted`/`updated`/`removed` per collection and the list of `failures`.

Vertex collections: `namespaces`, `helm_releases`, `deployments`, `stateful_sets`, `daemon_sets` (these three with `selector` = matchLabels and `matchExpressions`), `replica_sets`, `cron_jobs`, `jobs` (status `Complete`/`Failed`/`Suspended`/`Running`), `pods`, `services`, `config_maps`, `secrets` (type and key names only, never values), `service_accounts`, `nodes`, `persistent_volume_claims`, `persistent_volumes`, `storage_classes` (with `defaultClass`).

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

All collections are part of the named graph `k8s`, so the ArangoDB web UI can render it (Graphs → k8s). If a newer version adds vertex or edge collections, the app extends the existing graph on the next import.

| Variable | Default | |
|---|---|---|
| `K8S_IMPORT_ON_STARTUP` | `true` | import once in the background when the app is ready; errors are logged and the app keeps running without the cluster or ArangoDB |
| `K8S_IMPORT_NAMESPACES` | _(all)_ | comma-separated list; namespaces, nodes, storage classes and PVs are always imported |

Endpoints:

```bash
curl -X POST localhost:8080/api/k8s/import                                   # sync the graph with the cluster

# list and detail for every vertex type
curl localhost:8080/api/k8s/pods                                             # all pods, sorted by namespace and name
curl "localhost:8080/api/k8s/pods?namespace=kube-system"                     # pods in one namespace
curl localhost:8080/api/k8s/pods/kube-system/coredns-559f6c778d-p5vfh        # one namespaced resource
curl localhost:8080/api/k8s/nodes/local-worker                               # one cluster-scoped resource

curl localhost:8080/api/k8s/releases                                         # Helm releases incl. chart, version and revision history
curl localhost:8080/api/k8s/releases/istio-system/istiod/resources           # release → deployment → replicaset → pod
curl localhost:8080/api/k8s/releases/istio-system/istiod/config              # ConfigMaps/Secrets used by the release's pods
curl localhost:8080/api/k8s/configmaps/kube-system/kube-proxy/consumers      # impact: which pods/workloads use it
curl localhost:8080/api/k8s/secrets/<ns>/<name>/consumers
curl localhost:8080/api/k8s/configmaps/unused                                # not mounted or referenced by any pod
curl localhost:8080/api/k8s/secrets/unused                                   # same for secrets
curl localhost:8080/api/k8s/services/istio-system/istiod/backends            # pods behind a service
curl localhost:8080/api/k8s/services/without-backends                        # selector matches no pod
curl localhost:8080/api/k8s/nodes/load                                       # pods per node
curl localhost:8080/api/k8s/unhealthy                                        # workloads with ready < desired, failed jobs, pods not running
curl localhost:8080/api/k8s/images                                           # every image in use, with pod count and namespaces
curl "localhost:8080/api/k8s/images/pods?image=registry.k8s.io/coredns/coredns"  # pods running an image
curl "localhost:8080/api/k8s/changes?since=2026-11-12T08:00:00Z"             # added since T, and not seen since T
curl "localhost:8080/api/k8s/path?from=services/istio-system.istiod&to=config_maps/istio-system.istio"
```

Type segments follow `kubectl get`: `namespaces`, `releases`, `deployments`, `statefulsets`, `daemonsets`, `replicasets`, `cronjobs`, `jobs`, `pods`, `services`, `configmaps`, `secrets`, `serviceaccounts`, `nodes`, `persistentvolumeclaims`, `persistentvolumes`, `storageclasses`. Cluster-scoped types (namespaces, nodes, PVs, storage classes) use `/<type>/<name>`; all others use `/<type>/<namespace>/<name>`.

`unhealthy`, `images`, `images/pods` and `changes` also take `?namespace=`.

- `unhealthy` lists Deployments and StatefulSets with `readyReplicas < replicas`, DaemonSets with `ready < desired`, Jobs with status `Failed`, and pods whose phase is neither `Running` nor `Succeeded`. Pods that are `Running` but failing their readiness probe are not included, because the import only stores the phase.
- `images/pods?image=` matches the full image reference, or the repository without tag/digest: `nginx` finds `nginx:1.27` and `nginx@sha256:…`.
- `changes?since=` takes an ISO-8601 instant or a date (`2026-11-12` = start of day UTC); a `+01:00` offset has to be URL-encoded (`%2B01:00`). `added` lists everything an import saw for the first time after `since`. `stale` lists documents that no import has seen since then; normally the import removes those, so what remains comes from namespaces or collections the import could not read or that are outside `K8S_IMPORT_NAMESPACES`. Removed resources are deleted from the graph, so deletions do not show up here.

A request for a resource or namespace that is not in the graph returns `404` instead of an empty list, and so does an unknown type. Errors are returned as Problem Details (`application/problem+json`) with the missing id in `detail`.

Document keys are `<namespace>.<name>` (cluster-scoped: `<name>`), so ids look like `pods/istio-system.istiod-f8b75d4cf-qswkc`. The AQL behind the endpoints is in [repository/k8s](src/main/java/com/example/arrango/repository/k8s).

"Unused" only covers mounts and env references. A ConfigMap or Secret that a controller reads through the API (for example `istio-system/istio`, read by istiod) still shows up as unused. The same goes for `imagePullSecrets` and TLS secrets used by Ingresses or gateways, which the import does not track. Helm release secrets and Kubernetes-managed tokens (`kubernetes.io/service-account-token`, `bootstrap.kubernetes.io/token`) are left out of `secrets/unused`.

Running the import inside the cluster needs a ClusterRole with `get`/`list` on namespaces, nodes, persistentvolumes, `storage.k8s.io` storageclasses, namespaced pods, services, configmaps, secrets, serviceaccounts, persistentvolumeclaims and on `apps` deployments, statefulsets, daemonsets, replicasets and on `batch` jobs, cronjobs. Secrets are needed for Helm releases (`sh.helm.release.v1.*`). The `POST /api/k8s/import` endpoint has no authentication.
