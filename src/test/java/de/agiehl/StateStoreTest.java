package de.agiehl;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Map;
import java.util.Objects;
import org.h2.tools.RunScript;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StateStoreTest {

    @TempDir
    Path directory;

    @Test
    void migratesNewDatabaseAndDoesNotRepeatMigrationAfterRestart() throws Exception {
        var properties = TestFixtures.properties(directory);
        assertThat(new StateStore(properties).load()).isEmpty();
        assertThat(new StateStore(properties).load()).isEmpty();

        try (var connection = connect(properties);
             var statement = connection.createStatement();
             var rows = statement.executeQuery("""
                     SELECT "version", "type", "script", "checksum", "success" FROM "flyway_schema_history"
                     WHERE "type" = 'SQL' ORDER BY "installed_rank"
                     """)) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString("version")).isEqualTo("1");
            assertThat(rows.getString("type")).isEqualTo("SQL");
            assertThat(rows.getString("script")).isEqualTo("V1__create_project_status.sql");
            assertThat(rows.getObject("checksum")).isNotNull();
            assertThat(rows.getBoolean("success")).isTrue();
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString("version")).isEqualTo("2");
            assertThat(rows.getString("script")).isEqualTo("V2__create_weekly_status.sql");
            assertThat(rows.getObject("checksum")).isNotNull();
            assertThat(rows.getBoolean("success")).isTrue();
            assertThat(rows.next()).isFalse();
        }
    }

    @Test
    void adoptsLegacyDatabaseWithoutLosingPreviouslyKnownStatuses() throws Exception {
        var properties = TestFixtures.properties(directory);
        try (var connection = connect(properties);
             var reader = new InputStreamReader(Objects.requireNonNull(
                     getClass().getResourceAsStream("/legacy-h2-schema.sql")), StandardCharsets.UTF_8)) {
            RunScript.execute(connection, reader);
            try (var statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO monitor_snapshot VALUES ('CROWDFUNDING', 'old')");
                statement.executeUpdate("""
                        INSERT INTO project_status VALUES
                        ('CROWDFUNDING', 'Mordred 🎲', 'Shipping', 'In progress'),
                        ('CROWDFUNDING', 'Mordred 🎲', 'Delivery', '2026 Q4')
                        """);
            }
        }

        var previous = new SectionSnapshot("old", Map.of("Mordred 🎲",
                Map.of("Shipping", "In progress", "Delivery", "2026 Q4")));
        var store = new StateStore(properties);
        assertThat(store.load()).containsOnlyKeys(Section.CROWDFUNDING)
                .containsEntry(Section.CROWDFUNDING, previous);
        var current = new SectionSnapshot("new", Map.of("Mordred 🎲",
                Map.of("Shipping", "Completed", "Delivery", "2026 Q4")));
        store.save(Section.CROWDFUNDING, current);
        assertThat(new StateStore(properties).load()).containsEntry(Section.CROWDFUNDING, current);

        try (var connection = connect(properties);
             var statement = connection.createStatement();
             var rows = statement.executeQuery("""
                     SELECT "version", "type", "success" FROM "flyway_schema_history" WHERE "type" = 'BASELINE'
                     """)) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString("version")).isEqualTo("1");
            assertThat(rows.getString("type")).isEqualTo("BASELINE");
            assertThat(rows.getBoolean("success")).isTrue();
            assertThat(rows.next()).isFalse();
        }
    }

    @Test
    void failsOnMigrationChecksumMismatchWithoutChangingProjectStatuses() throws Exception {
        var properties = TestFixtures.properties(directory);
        var previous = new SectionSnapshot("old", Map.of("Mordred", Map.of("Shipping", "In progress")));
        new StateStore(properties).save(Section.CROWDFUNDING, previous);
        try (var connection = connect(properties);
             var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE \"flyway_schema_history\" SET \"checksum\" = \"checksum\" + 1");
        }

        assertThatThrownBy(() -> new StateStore(properties).load())
                .isInstanceOf(MonitorException.class).hasMessageContaining("Flyway migriert");
        try (var connection = connect(properties);
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT stage_value FROM project_status")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString("stage_value")).isEqualTo("In progress");
            assertThat(rows.next()).isFalse();
        }
    }

    @Test
    void replacesSectionIncludingRemovedProjectsWithoutChangingOtherSections() {
        var store = new StateStore(TestFixtures.properties(directory));
        var previous = new SectionSnapshot("old", Map.of(
                "O'Brien 🎲", Map.of("Shipping", "Not started", "Delivery", "2026 Q4"),
                "Removed", Map.of("Shipping", "Completed")));
        var preorder = new SectionSnapshot("old", Map.of("Other", Map.of("Delivery", "2027 Q1")));
        store.save(Section.CROWDFUNDING, previous);
        store.save(Section.PRE_ORDER, preorder);

        var current = new SectionSnapshot("new", Map.of("O'Brien 🎲", Map.of("Delivery", "2027 Q2")));
        store.save(Section.CROWDFUNDING, current);

        var reopened = new StateStore(TestFixtures.properties(directory));
        assertThat(reopened.load()).containsEntry(Section.CROWDFUNDING, current).containsEntry(Section.PRE_ORDER, preorder);
        assertThat(directory.resolve("cmon-state.mv.db")).exists();
    }

    @Test
    void rollsBackMetadataAndDeletedRowsWhenNewStatusesCannotBeInserted() throws Exception {
        var properties = TestFixtures.properties(directory);
        var store = new StateStore(properties);
        var previous = new SectionSnapshot("old", Map.of("Game", Map.of("Shipping", "Completed")));
        store.save(Section.CROWDFUNDING, previous);
        var url = "jdbc:h2:file:" + properties.databasePath().toAbsolutePath().toString().replace('\\', '/');
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE project_status ADD CONSTRAINT test_failure CHECK (stage_value <> 'force-error')");
        }

        var current = new SectionSnapshot("new", Map.of("Game", Map.of("Shipping", "force-error")));
        assertThatThrownBy(() -> store.save(Section.CROWDFUNDING, current))
                .isInstanceOf(MonitorException.class).hasMessageContaining("nicht gespeichert");

        assertThat(store.load()).containsOnlyKeys(Section.CROWDFUNDING).containsEntry(Section.CROWDFUNDING, previous);
    }

    private Connection connect(MonitorProperties properties) throws Exception {
        var url = "jdbc:h2:file:" + properties.databasePath().toAbsolutePath().toString().replace('\\', '/');
        return DriverManager.getConnection(url, "sa", "");
    }
}
