package io.superharness.persistence;

import static org.junit.jupiter.api.Assertions.*;

import com.mysql.cj.jdbc.MysqlDataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;

class MigrationsTest {
    @Test
    void upgradesFromCatalogAndReappliesCleanly() {
        try (var db = new MySQLContainer<>("mysql:8.4.13")) {
            db.start();
            var ds = new MysqlDataSource();
            ds.setURL(db.getJdbcUrl());
            ds.setUser(db.getUsername());
            ds.setPassword(db.getPassword());
            Flyway.configure().dataSource(ds).target("1").load().migrate();
            var flyway = Flyway.configure().dataSource(ds).load();
            assertTrue(flyway.migrate().migrationsExecuted > 0);
            assertEquals(0, flyway.migrate().migrationsExecuted);
            flyway.validate();
        }
    }

    @Test
    void recoversAPartiallyAppliedLedgerMigrationWithoutDiscardingExistingRecords()
            throws Exception {
        try (var db = new MySQLContainer<>("mysql:8.4.13")) {
            db.start();
            var ds = new MysqlDataSource();
            ds.setURL(db.getJdbcUrl());
            ds.setUser(db.getUsername());
            ds.setPassword(db.getPassword());
            Flyway.configure().dataSource(ds).target("1").load().migrate();
            try (var conn = ds.getConnection();
                    var statement = conn.createStatement()) {
                statement.execute("INSERT INTO tenants(id) VALUES('existing')");
                statement.execute("CREATE TABLE run_events(legacy_id BIGINT PRIMARY KEY)");
                statement.execute("INSERT INTO run_events VALUES(42)");
                var migration = Flyway.configure().dataSource(ds).load();
                assertThrows(org.flywaydb.core.api.FlywayException.class, migration::migrate);
                // In this isolated upgrade the application is stopped. Undo only the two verified
                // empty V2 tables.
                for (String table : java.util.List.of("conversation_gates", "agent_runs")) {
                    try (var count = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
                        assertTrue(count.next());
                        assertEquals(0, count.getLong(1));
                    }
                    statement.execute("DROP TABLE " + table);
                }
                statement.execute("RENAME TABLE run_events TO legacy_run_events");
                migration.repair();
                migration.migrate();
                migration.validate();
                try (var row =
                        statement.executeQuery(
                                "SELECT legacy_id FROM legacy_run_events JOIN tenants ON"
                                    + " tenants.id='existing'")) {
                    assertTrue(row.next());
                    assertEquals(42, row.getLong(1));
                }
            }
        }
    }
}
