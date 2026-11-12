package com.example.arrango.k8s;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Minimaler Kubernetes-API-Server für Tests, nur {@code GET} auf Listen (mehr braucht der Importer nicht):
 * {@code /api/v1/[namespaces/<ns>/]<plural>} und {@code /apis/<group>/<version>/[namespaces/<ns>/]<plural>},
 * Label-Selector mit {@code k=v} und {@code k!=v}.
 * <p>
 * Läuft auf dem JDK-{@link HttpServer}, nicht auf fabric8s Mock-Server: Der zieht Vert.x 5 auf den Classpath,
 * der ArangoDB-Treiber braucht Vert.x 4.
 */
class FakeKubernetesApi implements AutoCloseable {

	private final HttpServer server;
	private final List<HasMetadata> resources = new CopyOnWriteArrayList<>();
	private final Map<String, Integer> failures = new HashMap<>();
	final KubernetesClient client;

	FakeKubernetesApi() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", this::handle);
		server.start();
		Config config = new ConfigBuilder(Config.empty())
				.withMasterUrl("http://127.0.0.1:" + server.getAddress().getPort())
				.withRequestRetryBackoffLimit(0)
				.build();
		client = new KubernetesClientBuilder().withConfig(config).build();
	}

	void add(HasMetadata resource) {
		resources.add(resource);
	}

	/** Antwortet auf {@code GET path} (ohne Query) immer mit {@code status}. */
	void fail(String path, int status) {
		failures.put(path, status);
	}

	@Override
	public void close() {
		client.close();
		server.stop(0);
	}

	private void handle(HttpExchange exchange) throws IOException {
		try (exchange) {
			var path = exchange.getRequestURI().getPath();
			if (!"GET".equals(exchange.getRequestMethod())) {
				respond(exchange, 405, status(405, "nur GET"));
				return;
			}
			if (failures.containsKey(path)) {
				int code = failures.get(path);
				respond(exchange, code, status(code, "Fake: " + path));
				return;
			}
			var request = parse(path);
			if (request == null) {
				respond(exchange, 404, status(404, "unbekannter Pfad " + path));
				return;
			}
			var selector = query(exchange).get("labelSelector");
			var items = resources.stream()
					.filter(r -> r.getApiVersion().equals(request.apiVersion()))
					.filter(r -> HasMetadata.getPlural(r.getClass()).equals(request.plural()))
					.filter(r -> request.namespace() == null || request.namespace().equals(r.getMetadata().getNamespace()))
					.filter(r -> matches(r, selector))
					.toList();
			var json = "{\"apiVersion\":\"" + request.apiVersion() + "\",\"kind\":\"List\",\"metadata\":{},\"items\":"
					+ client.getKubernetesSerialization().asJson(items) + "}";
			respond(exchange, 200, json);
		}
	}

	private record ListRequest(String apiVersion, String namespace, String plural) {
	}

	private static ListRequest parse(String path) {
		var segments = new ArrayList<>(Arrays.asList(path.replaceFirst("^/", "").split("/")));
		String apiVersion;
		if (segments.size() >= 3 && segments.get(0).equals("api")) {
			apiVersion = segments.get(1);
			segments.subList(0, 2).clear();
		}
		else if (segments.size() >= 4 && segments.get(0).equals("apis")) {
			apiVersion = segments.get(1) + "/" + segments.get(2);
			segments.subList(0, 3).clear();
		}
		else {
			return null;
		}
		if (segments.size() == 1) {
			return new ListRequest(apiVersion, null, segments.get(0));
		}
		if (segments.size() == 3 && segments.get(0).equals("namespaces")) {
			return new ListRequest(apiVersion, segments.get(1), segments.get(2));
		}
		return null;
	}

	private static boolean matches(HasMetadata resource, String selector) {
		if (selector == null || selector.isBlank()) {
			return true;
		}
		var labels = resource.getMetadata().getLabels() == null ? Map.<String, String>of()
				: resource.getMetadata().getLabels();
		for (var term : selector.split(",")) {
			if (term.contains("!=")) {
				var kv = term.split("!=", 2);
				if (kv[1].equals(labels.get(kv[0]))) {
					return false;
				}
			}
			else {
				var kv = term.split("==?", 2);
				if (!kv[1].equals(labels.get(kv[0]))) {
					return false;
				}
			}
		}
		return true;
	}

	private static Map<String, String> query(HttpExchange exchange) {
		var result = new HashMap<String, String>();
		var raw = exchange.getRequestURI().getRawQuery();
		if (raw != null) {
			for (var pair : raw.split("&")) {
				var kv = pair.split("=", 2);
				result.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
						kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
			}
		}
		return result;
	}

	private static String status(int code, String message) {
		return "{\"apiVersion\":\"v1\",\"kind\":\"Status\",\"status\":\"Failure\",\"code\":" + code
				+ ",\"message\":\"" + message + "\"}";
	}

	private static void respond(HttpExchange exchange, int code, String body) throws IOException {
		var bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(code, bytes.length);
		exchange.getResponseBody().write(bytes);
	}
}
