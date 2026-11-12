package com.example.arrango.k8s;

import java.util.List;
import java.util.Map;

/**
 * Ergebnis eines Imports.
 *
 * @param importedAt  Zeitstempel des Laufs; steht in {@code lastSeen} aller Dokumente, die er gesehen hat
 * @param collections Änderungen pro Collection
 * @param failures    Ressourcentypen bzw. Namespaces, die nicht gelesen werden konnten; ihr alter Stand bleibt
 *                    unverändert in der Datenbank
 */
public record ImportResult(String importedAt, Map<String, Changes> collections, List<Failure> failures) {

	/**
	 * @param inserted neu angelegt
	 * @param updated  schon vorhanden und aktualisiert
	 * @param removed  nicht mehr im Cluster und daher entfernt
	 */
	public record Changes(int inserted, int updated, int removed) {
	}

	/**
	 * @param collection betroffene Vertex-Collection
	 * @param namespace  betroffener Namespace; {@code null}, wenn die ganze Collection betroffen ist
	 * @param error      Fehlermeldung, z. B. das 403 der API bei fehlendem RBAC-Recht
	 */
	public record Failure(String collection, String namespace, String error) {
	}
}
