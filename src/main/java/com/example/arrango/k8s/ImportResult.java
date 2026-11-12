package com.example.arrango.k8s;

import java.util.Map;

/**
 * Ergebnis eines Imports.
 *
 * @param importedAt  Zeitstempel des Laufs; steht in {@code lastSeen} aller Dokumente, die er gesehen hat
 * @param collections Änderungen pro Collection
 */
public record ImportResult(String importedAt, Map<String, Changes> collections) {

	/**
	 * @param inserted neu angelegt
	 * @param updated  schon vorhanden und aktualisiert
	 * @param removed  nicht mehr im Cluster und daher entfernt
	 */
	public record Changes(int inserted, int updated, int removed) {
	}
}
