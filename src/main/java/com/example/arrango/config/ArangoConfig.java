package com.example.arrango.config;

import com.arangodb.ArangoDB;
import com.arangodb.springframework.annotation.EnableArangoRepositories;
import com.arangodb.springframework.config.ArangoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ArangoProperties.class)
@EnableArangoRepositories(basePackages = "com.example.arrango.repository")
public class ArangoConfig implements ArangoConfiguration {

	private final ArangoProperties properties;

	public ArangoConfig(ArangoProperties properties) {
		this.properties = properties;
	}

	@Override
	public ArangoDB.Builder arango() {
		return new ArangoDB.Builder()
				.host(properties.host(), properties.port())
				.user(properties.user())
				.password(properties.password());
	}

	@Override
	public String database() {
		return properties.database();
	}

	@Override
	public String[] getEntityBasePackages() {
		return new String[] { "com.example.arrango.model" };
	}
}
