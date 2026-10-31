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
 * V44 makes usernames unique ignoring case. Uses its own throwaway database because it must stop at V43, insert
 * names that differ only by case and only then apply V44.
 */
class UsernameUniqueMigrationTest {

    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");
    static JdbcTemplate jdbc;

    @BeforeAll
    static void migrateToV43InsertDuplicatesThenApplyV44() {
        postgres.start();
        var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).target("43").load().migrate();

        // 'Admin (2)' exists already (a numeric rename suffix would collide); 'L' + 49 x is a maximum-length name
        insert("Admin", "4 days");
        insert("admin", "3 days");
        insert("ADMIN", "2 days");
        insert("Admin (2)", "1 day");
        insert("bob", "0 days");
        insert("L" + "x".repeat(49), "4 days");
        insert("l" + "x".repeat(49), "3 days");

        Flyway.configure().dataSource(dataSource).load().migrate();
    }

    @AfterAll
    static void stop() {
        postgres.stop();
    }

    private static void insert(String name, String age) {
        jdbc.update("INSERT INTO users (username, password_hash, role, created_at) VALUES (?, 'x', 'VIEWER', now() - ?::interval)",
                name, age);
    }

    @Test
    void oldestKeepsItsNameAndTheOthersAreRenamedWithoutLosingRows() {
        List<String> names = jdbc.queryForList(
                "SELECT username FROM users WHERE lower(username) LIKE 'admin%' OR username = 'bob' ORDER BY created_at, username",
                String.class);
        assertThat(names).hasSize(5);
        assertThat(names).contains("Admin", "Admin (2)", "bob");
        assertThat(names).filteredOn(n -> n.startsWith("admin (") || n.startsWith("ADMIN (")).hasSize(2);
        assertThat(jdbc.queryForList("SELECT username FROM users", String.class).stream()
                .map(String::toLowerCase).distinct()).hasSize(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class));
    }

    @Test
    void renamedMaximumLengthNameStillFitsTheColumn() {
        List<String> names = jdbc.queryForList(
                "SELECT username FROM users WHERE lower(username) LIKE 'lxx%' ORDER BY created_at", String.class);
        assertThat(names).hasSize(2);
        assertThat(names).allSatisfy(n -> assertThat(n.length()).isLessThanOrEqualTo(50));
        assertThat(names.get(0)).isEqualTo("L" + "x".repeat(49));
        assertThat(names.get(1)).startsWith("l" + "x".repeat(38) + " (");
    }

    @Test
    void databaseNowRejectsUsernamesDifferingOnlyByCase() {
        assertThatThrownBy(() -> insert("BOB", "0 days")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void caseSensitiveConstraintIsGone() {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM pg_constraint WHERE conname = 'users_username_key'", Integer.class);
        assertThat(n).isZero();
    }
}
