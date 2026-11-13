package com.example.arrango.migration;

import com.arangodb.ArangoCollection;
import com.arangodb.ArangoCursor;
import com.arangodb.ArangoDB;
import com.arangodb.ArangoDatabase;
import com.arangodb.springframework.core.ArangoOperations;
import com.example.arrango.config.ArangoProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MigrationRunnerTest {

	private final ArangoOperations operations = mock(ArangoOperations.class);
	private final ArangoDB driver = mock(ArangoDB.class);
	private final ArangoDatabase db = mock(ArangoDatabase.class);
	private final ArangoCollection history = mock(ArangoCollection.class);

	/** Versionen, die laut {@code migrations}-Collection schon gelaufen sind. */
	private final List<Integer> applied = new ArrayList<>();
	/** Versionen in der Reihenfolge, in der die Migrationen ausgeführt wurden. */
	private final List<Integer> executed = new ArrayList<>();

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		when(operations.driver()).thenReturn(driver);
		when(driver.db("test")).thenReturn(db);
		when(db.exists()).thenReturn(true);
		when(db.collection(MigrationRunner.COLLECTION)).thenReturn(history);
		when(history.exists()).thenReturn(true);
		when(db.query(anyString(), eq(Integer.class), anyMap())).thenAnswer(inv -> {
			ArangoCursor<Integer> cursor = mock(ArangoCursor.class);
			when(cursor.asListRemaining()).thenReturn(List.copyOf(applied));
			return cursor;
		});
		when(history.insertDocument(any())).thenAnswer(inv -> {
			Map<String, Object> doc = inv.getArgument(0);
			applied.add((Integer) doc.get("version"));
			return null;
		});
	}

	@Test
	void runsPendingMigrationsInVersionOrderAndRecordsThem() {
		var runner = runner(migration(2), migration(1));

		assertThat(runner.migrate()).containsExactly(1, 2);
		assertThat(executed).containsExactly(1, 2);
		assertThat(applied).containsExactly(1, 2);
	}

	@Test
	void skipsMigrationsAlreadyApplied() {
		applied.add(1);
		var runner = runner(migration(1), migration(2));

		assertThat(runner.migrate()).containsExactly(2);
		assertThat(executed).containsExactly(2);
	}

	@Test
	void secondRunDoesNothing() {
		var runner = runner(migration(1));
		runner.migrate();
		executed.clear();

		assertThat(runner.migrate()).isEmpty();
		assertThat(executed).isEmpty();
	}

	@Test
	void failedMigrationIsNotRecordedAndStopsLaterOnes() {
		var runner = runner(migration(1), failing(2), migration(3));

		assertThatThrownBy(runner::migrate).hasMessage("kaputt");
		assertThat(executed).containsExactly(1);
		assertThat(applied).containsExactly(1);
	}

	@Test
	void createsDatabaseAndHistoryCollectionWhenMissing() {
		when(db.exists()).thenReturn(false);
		when(history.exists()).thenReturn(false);

		runner(migration(1)).migrate();

		verify(driver).createDatabase("test");
		verify(history).create();
	}

	@Test
	void onStartupFalseSkipsMigrations() {
		new MigrationRunner(operations, arangoProperties(), new MigrationProperties(false), List.of(migration(1)))
				.run(null);

		assertThat(executed).isEmpty();
		verify(operations, never()).driver();
	}

	@Test
	void rejectsDuplicateVersions() {
		assertThatThrownBy(() -> runner(migration(1), migration(1)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("[1]");
	}

	private MigrationRunner runner(Migration... migrations) {
		return new MigrationRunner(operations, arangoProperties(), new MigrationProperties(true), List.of(migrations));
	}

	private static ArangoProperties arangoProperties() {
		return new ArangoProperties("localhost", 8529, "root", "", "test");
	}

	private Migration migration(int version) {
		return new Migration() {
			@Override
			public int version() {
				return version;
			}

			@Override
			public String description() {
				return "Migration " + version;
			}

			@Override
			public void migrate(ArangoDatabase database) {
				executed.add(version);
			}
		};
	}

	private Migration failing(int version) {
		return new Migration() {
			@Override
			public int version() {
				return version;
			}

			@Override
			public String description() {
				return "kaputt";
			}

			@Override
			public void migrate(ArangoDatabase database) {
				throw new IllegalStateException("kaputt");
			}
		};
	}
}
