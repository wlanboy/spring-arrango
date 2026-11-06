package com.example.arrango.health;

import com.arangodb.springframework.core.ArangoOperations;
import com.example.arrango.config.ArangoProperties;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.stereotype.Component;

@Component("arangodb")
public class ArangoHealthIndicator extends AbstractHealthIndicator {

	private final ArangoOperations operations;
	private final ArangoProperties properties;

	public ArangoHealthIndicator(ArangoOperations operations, ArangoProperties properties) {
		super("ArangoDB health check failed");
		this.operations = operations;
		this.properties = properties;
	}

	@Override
	protected void doHealthCheck(Health.Builder builder) {
		var version = operations.getVersion();
		boolean dbExists = operations.driver().db(properties.database()).exists();
		(dbExists ? builder.up() : builder.down())
				.withDetail("server", version.getServer())
				.withDetail("version", version.getVersion())
				.withDetail("database", properties.database())
				.withDetail("databaseExists", dbExists);
	}
}
