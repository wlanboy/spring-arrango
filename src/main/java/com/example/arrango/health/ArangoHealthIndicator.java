package com.example.arrango.health;

import com.arangodb.springframework.core.ArangoOperations;
import com.example.arrango.config.ArangoProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Loggt nur Zustandswechsel, nicht jeden Probe-Aufruf. Fehler beim Check selbst loggt zusätzlich
 * {@link AbstractHealthIndicator} mit Stacktrace.
 */
@Component("arangodb")
public class ArangoHealthIndicator extends AbstractHealthIndicator {

	private static final Logger log = LoggerFactory.getLogger(ArangoHealthIndicator.class);

	private final ArangoOperations operations;
	private final ArangoProperties properties;
	/** Ergebnis des letzten Checks; {@code null} vor dem ersten. */
	private final AtomicReference<Boolean> lastUp = new AtomicReference<>();

	public ArangoHealthIndicator(ArangoOperations operations, ArangoProperties properties) {
		super("ArangoDB health check failed");
		this.operations = operations;
		this.properties = properties;
	}

	@Override
	protected void doHealthCheck(Health.Builder builder) {
		boolean up = false;
		try {
			var version = operations.getVersion();
			boolean dbExists = operations.driver().db(properties.database()).exists();
			if (!dbExists) {
				log.debug("Datenbank {} existiert nicht", properties.database());
			}
			(dbExists ? builder.up() : builder.down())
					.withDetail("server", version.getServer())
					.withDetail("version", version.getVersion())
					.withDetail("database", properties.database())
					.withDetail("databaseExists", dbExists);
			up = dbExists;
		}
		finally {
			logTransition(up);
		}
	}

	private void logTransition(boolean up) {
		Boolean previous = lastUp.getAndSet(up);
		if (previous != null && previous == up) {
			return;
		}
		if (!up) {
			log.warn("ArangoDB {}:{} mit Datenbank {} nicht verfügbar", properties.host(), properties.port(),
					properties.database());
		}
		else if (previous != null) {
			log.info("ArangoDB {}:{} mit Datenbank {} wieder verfügbar", properties.host(), properties.port(),
					properties.database());
		}
	}
}
