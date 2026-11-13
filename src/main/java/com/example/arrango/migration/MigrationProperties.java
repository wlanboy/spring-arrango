package com.example.arrango.migration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param onStartup offene Migrationen beim Start ausführen; schlägt eine fehl, startet die App nicht
 */
@ConfigurationProperties(prefix = "arangodb.migrations")
public record MigrationProperties(@DefaultValue("true") boolean onStartup) {
}
