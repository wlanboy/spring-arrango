package com.example.arrango.migration;

import com.arangodb.ArangoDatabase;

/**
 * Ein Schritt am Datenbankschema oder an den Daten, der genau einmal pro Datenbank laufen soll.
 * <p>
 * Implementierungen sind Spring-Beans; der {@link MigrationRunner} findet sie selbst. Eine ausgerollte Migration
 * wird nicht mehr geändert, Korrekturen kommen als neue Version dazu.
 * <p>
 * ArangoDB kennt keine Transaktionen über Schemaänderungen. Bricht eine Migration mittendrin ab, wird sie beim
 * nächsten Start erneut ausgeführt. Sie sollte deshalb idempotent sein (z. B. nur anlegen, was fehlt).
 */
public interface Migration {

	/** Eindeutige Versionsnummer; Migrationen laufen aufsteigend sortiert. */
	int version();

	/** Kurzbeschreibung fürs Log und für den Eintrag in {@value MigrationRunner#COLLECTION}. */
	String description();

	void migrate(ArangoDatabase db);
}
