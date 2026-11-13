package com.example.arrango.migration;

import com.arangodb.ArangoDatabase;
import com.arangodb.springframework.core.ArangoOperations;
import com.example.arrango.config.ArangoProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Führt beim Start alle {@link Migration}s aus, die in der Datenbank noch nicht vermerkt sind.
 * <p>
 * Jede erfolgreiche Migration bekommt ein Dokument in {@value #COLLECTION} mit der Version als {@code _key}. Der
 * Runner läuft als {@link ApplicationRunner} und damit vor dem {@code ApplicationReadyEvent}, also auch vor dem
 * Kubernetes-Import.
 * <p>
 * Einfacher Fall: eine Instanz migriert. Starten mehrere Pods gleichzeitig, kann eine Migration doppelt laufen;
 * der zweite Eintrag scheitert dann am eindeutigen {@code _key} und bricht den Start dieses Pods ab.
 */
@Component
@EnableConfigurationProperties(MigrationProperties.class)
public class MigrationRunner implements ApplicationRunner, Ordered {

	static final String COLLECTION = "migrations";

	private static final Logger log = LoggerFactory.getLogger(MigrationRunner.class);

	private final ArangoOperations operations;
	private final ArangoProperties arangoProperties;
	private final MigrationProperties properties;
	private final List<Migration> migrations;

	public MigrationRunner(ArangoOperations operations, ArangoProperties arangoProperties,
			MigrationProperties properties, List<Migration> migrations) {
		this.operations = operations;
		this.arangoProperties = arangoProperties;
		this.properties = properties;
		this.migrations = migrations.stream().sorted(Comparator.comparingInt(Migration::version)).toList();
		checkUniqueVersions(this.migrations);
	}

	@Override
	public int getOrder() {
		return Ordered.HIGHEST_PRECEDENCE;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (properties.onStartup()) {
			migrate();
		}
	}

	/**
	 * Führt alle offenen Migrationen der Reihe nach aus und gibt deren Versionen zurück. Eine fehlschlagende
	 * Migration bricht ab; spätere laufen dann nicht und sie selbst wird beim nächsten Aufruf wiederholt.
	 */
	public synchronized List<Integer> migrate() {
		var db = database();
		var history = db.collection(COLLECTION);
		if (!history.exists()) {
			history.create();
		}

		Set<Integer> applied = new HashSet<>(
				db.query("FOR m IN @@collection RETURN m.version", Integer.class, Map.of("@collection", COLLECTION))
						.asListRemaining());

		var pending = migrations.stream().filter(m -> !applied.contains(m.version())).toList();
		if (pending.isEmpty()) {
			log.info("Datenbank {} ist aktuell (Version {})", arangoProperties.database(), applied.stream()
					.max(Integer::compare).map(String::valueOf).orElse("-"));
			return List.of();
		}

		for (var migration : pending) {
			log.info("Migration {}: {}", migration.version(), migration.description());
			var start = System.nanoTime();
			migration.migrate(db);
			history.insertDocument(Map.of(
					"_key", String.valueOf(migration.version()),
					"version", migration.version(),
					"description", migration.description(),
					"appliedAt", Instant.now().toString(),
					"durationMs", (System.nanoTime() - start) / 1_000_000));
		}
		return pending.stream().map(Migration::version).toList();
	}

	/** Spring Data legt die Datenbank erst beim ersten Repository-Zugriff an; die Migration kann früher kommen. */
	private ArangoDatabase database() {
		var db = operations.driver().db(arangoProperties.database());
		if (!db.exists()) {
			log.info("Lege Datenbank {} an", arangoProperties.database());
			operations.driver().createDatabase(arangoProperties.database());
		}
		return db;
	}

	private static void checkUniqueVersions(List<Migration> migrations) {
		var duplicates = migrations.stream()
				.collect(Collectors.groupingBy(Migration::version, Collectors.counting()))
				.entrySet().stream()
				.filter(e -> e.getValue() > 1)
				.map(Map.Entry::getKey)
				.toList();
		if (!duplicates.isEmpty()) {
			throw new IllegalStateException("Migrationsversionen mehrfach vergeben: " + duplicates);
		}
	}
}
