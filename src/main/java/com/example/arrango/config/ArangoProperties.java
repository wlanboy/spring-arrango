package com.example.arrango.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "arangodb")
public record ArangoProperties(
		@DefaultValue("localhost") String host,
		@DefaultValue("8529") int port,
		@DefaultValue("root") String user,
		@DefaultValue("") String password,
		@DefaultValue("arrango") String database) {
}
