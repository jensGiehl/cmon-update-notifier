package de.agiehl;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.springframework.stereotype.Component;

@Component
public class StateStore {

    private final MonitorProperties properties;
    private boolean migrated;

    public StateStore(MonitorProperties properties) {
        this.properties = properties;
    }

    public Guard lock() {
        var database = databasePath();
        FileChannel channel = null;
        try {
            Files.createDirectories(database.getParent());
            channel = FileChannel.open(database.resolveSibling(database.getFileName() + ".run.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            var lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                throw new MonitorException("Eine andere Prüfung verwendet bereits diese H2-Datenbank.");
            }
            return new Guard(channel, lock);
        } catch (IOException | OverlappingFileLockException exception) {
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException ignored) {
                }
            }
            throw new MonitorException("H2-Prüfung kann nicht gesperrt werden (belegt oder nicht beschreibbar).");
        }
    }

    public Map<Section, SectionSnapshot> load() {
        try (var connection = connect()) {
            var dates = new EnumMap<Section, String>(Section.class);
            var projects = new EnumMap<Section, Map<String, Map<String, String>>>(Section.class);
            try (var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT section_id, last_updated FROM monitor_snapshot")) {
                while (rows.next()) {
                    var section = Section.valueOf(rows.getString("section_id"));
                    dates.put(section, rows.getString("last_updated"));
                    projects.put(section, new LinkedHashMap<>());
                }
            }
            try (var statement = connection.createStatement();
                 var rows = statement.executeQuery("""
                         SELECT section_id, project_name, stage_name, stage_value FROM project_status
                         ORDER BY section_id, project_name, stage_name
                         """)) {
                while (rows.next()) {
                    var section = Section.valueOf(rows.getString("section_id"));
                    var sectionProjects = projects.get(section);
                    if (sectionProjects == null) {
                        throw new MonitorException("H2-Datenbank enthält einen unvollständigen Projektstand.");
                    }
                    sectionProjects.computeIfAbsent(rows.getString("project_name"), name -> new LinkedHashMap<>())
                            .put(rows.getString("stage_name"), rows.getString("stage_value"));
                }
            }
            var state = new EnumMap<Section, SectionSnapshot>(Section.class);
            dates.forEach((section, date) -> state.put(section, new SectionSnapshot(date, projects.get(section))));
            return state;
        } catch (SQLException | IllegalArgumentException exception) {
            throw new MonitorException("H2-Stand kann nicht gelesen werden. Datenbank, Rechte und laufende Prozesse prüfen.");
        }
    }

    public void save(Section section, SectionSnapshot snapshot) {
        try (var connection = connect()) {
            connection.setAutoCommit(false);
            try {
                saveSnapshot(connection, section, snapshot);
                saveProjectStatuses(connection, section, snapshot);
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            throw new MonitorException("H2-Stand kann nicht gespeichert werden. Die Änderung wird beim nächsten Cron-Lauf erneut versucht.");
        }
    }

    private synchronized Connection connect() throws SQLException {
        var database = databasePath();
        try {
            Files.createDirectories(database.getParent());
        } catch (IOException exception) {
            throw new MonitorException("H2-Datenbankverzeichnis kann nicht angelegt werden. Verzeichnisrechte prüfen.");
        }
        var url = "jdbc:h2:file:" + database.toString().replace('\\', '/') + ";DB_CLOSE_ON_EXIT=FALSE";
        if (!migrated) {
            migrate(url);
            migrated = true;
        }
        return DriverManager.getConnection(url, "sa", "");
    }

    private Path databasePath() {
        properties.validateDatabasePath();
        return properties.databasePath().toAbsolutePath().normalize();
    }

    private void migrate(String url) {
        try {
            Flyway.configure()
                    .dataSource(url, "sa", "")
                    .defaultSchema("PUBLIC")
                    .locations("classpath:db/migration")
                    .encoding("UTF-8")
                    .baselineOnMigrate(true)
                    .baselineVersion("1")
                    .cleanDisabled(true)
                    .load()
                    .migrate();
        } catch (FlywayException exception) {
            throw new MonitorException("H2-Schema kann nicht mit Flyway migriert werden."
                    + " Datenbank, Migrationen, Rechte und laufende Prozesse prüfen.");
        }
    }

    private void saveSnapshot(Connection connection, Section section, SectionSnapshot snapshot) throws SQLException {
        try (var statement = connection.prepareStatement("""
                MERGE INTO monitor_snapshot (section_id, last_updated) KEY(section_id) VALUES (?, ?)
                """)) {
            statement.setString(1, section.name());
            statement.setString(2, snapshot.lastUpdated());
            statement.executeUpdate();
        }
    }

    private void saveProjectStatuses(Connection connection, Section section, SectionSnapshot snapshot) throws SQLException {
        try (var statement = connection.prepareStatement("DELETE FROM project_status WHERE section_id = ?")) {
            statement.setString(1, section.name());
            statement.executeUpdate();
        }
        try (var statement = connection.prepareStatement("""
                INSERT INTO project_status (section_id, project_name, stage_name, stage_value) VALUES (?, ?, ?, ?)
                """)) {
            for (var project : snapshot.projects().entrySet()) {
                for (var stage : project.getValue().entrySet()) {
                    statement.setString(1, section.name());
                    statement.setString(2, project.getKey());
                    statement.setString(3, stage.getKey());
                    statement.setString(4, stage.getValue());
                    statement.addBatch();
                }
            }
            statement.executeBatch();
        }
    }

    public record Guard(FileChannel channel, FileLock lock) implements AutoCloseable {
        @Override
        public void close() {
            try (channel) {
                lock.release();
            } catch (IOException exception) {
                throw new MonitorException("Sperre der H2-Prüfung konnte nicht freigegeben werden.");
            }
        }
    }
}
