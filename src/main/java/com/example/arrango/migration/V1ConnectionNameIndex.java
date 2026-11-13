package com.example.arrango.migration;

import com.arangodb.ArangoDatabase;
import com.arangodb.model.PersistentIndexOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/** Beispiel: Index auf {@code connections.name}, damit Suchen nach Namen nicht die ganze Collection lesen. */
@Component
class V1ConnectionNameIndex implements Migration {

	private static final Logger log = LoggerFactory.getLogger(V1ConnectionNameIndex.class);

	@Override
	public int version() {
		return 1;
	}

	@Override
	public String description() {
		return "Persistenter Index auf connections.name";
	}

	@Override
	public void migrate(ArangoDatabase db) {
		var connections = db.collection("connections");
		if (!connections.exists()) {
			log.info("Lege Collection connections an");
			connections.create();
		}
		// ensure* ist idempotent: Gibt es den Index schon, liefert ArangoDB ihn unverändert zurück.
		connections.ensurePersistentIndex(List.of("name"), new PersistentIndexOptions().name("idx_connections_name"));
	}
}
