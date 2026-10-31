package dev.bethge.shipir.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V42 makes team names unique ignoring case. Uses its own throwaway database because it must stop at V41, insert
 * names that differ only by case and only then apply V42.
 */
class TeamNameUniqueMigrationTest {

    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");
    static JdbcTemplate jdbc;

    @BeforeAll
    static void migrateToV41InsertDuplicatesThenApplyV42() {
        postgres.start();
        var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).target("41").load().migrate();

        // 'Ops (2)' exists already: a numeric rename suffix would collide with it
        jdbc.update("INSERT INTO teams (name, created_at) VALUES ('Ops', now() - interval '4 days')");
        jdbc.update("INSERT INTO teams (name, created_at) VALUES ('ops', now() - interval '3 days')");
        jdbc.update("INSERT INTO teams (name, created_at) VALUES ('OPS', now() - interval '2 days')");
        jdbc.update("INSERT INTO teams (name, created_at) VALUES ('Ops (2)', now() - interval '1 day')");
        jdbc.update("INSERT INTO teams (name, created_at) VALUES ('Dev', now())");

        Flyway.configure().dataSource(dataSource).load().migrate();
    }

    @AfterAll
    static void stop() {
        postgres.stop();
    }

    @Test
    void oldestKeepsItsNameAndTheOthersAreRenamedWithoutLosingRows() {
        List<String> names = jdbc.queryForList("SELECT name FROM teams ORDER BY created_at", String.class);
        assertThat(names).hasSize(5);
        assertThat(names.get(0)).isEqualTo("Ops");
        assertThat(names.get(1)).startsWith("ops (").isNotEqualTo("ops");
        assertThat(names.get(2)).startsWith("OPS (");
        assertThat(names.get(3)).isEqualTo("Ops (2)");
        assertThat(names.get(4)).isEqualTo("Dev");
        assertThat(names.stream().map(String::toLowerCase).distinct()).hasSize(5);
    }

    @Test
    void databaseNowRejectsNamesDifferingOnlyByCase() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO teams (name) VALUES ('dEv')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void caseSensitiveConstraintIsGone() {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM pg_constraint WHERE conname = 'teams_name_key'", Integer.class);
        assertThat(n).isZero();
    }
}
