package com.example.arrango;

import com.example.arrango.health.ArangoHealthIndicator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Die App muss ohne ArangoDB und ohne Cluster starten (z. B. in der CI): Port 1 ist garantiert nicht erreichbar,
 * Import und Migrationen beim Start sind aus. Die Repositories entstehen lazy (siehe {@code ArangoConfig}), erst
 * ein Zugriff scheitert.
 */
@SpringBootTest(properties = { "k8s.import.on-startup=false", "arangodb.migrations.on-startup=false",
		"arangodb.host=127.0.0.1", "arangodb.port=1" })
class ArrangoApplicationTests {

	@Autowired
	ArangoHealthIndicator arangoHealth;

	@Test
	void contextLoadsWithoutArangoDb() {
	}

	@Test
	void healthIsDownWithoutArangoDb() {
		assertThat(arangoHealth.health(true).getStatus()).isEqualTo(Status.DOWN);
	}
}
