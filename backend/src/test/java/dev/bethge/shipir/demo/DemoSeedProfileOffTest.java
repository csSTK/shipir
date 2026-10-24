package dev.bethge.shipir.demo;

import dev.bethge.shipir.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Without the 'demo' profile the app must not contain any demo seed rows (demo ids start with 0de00000). */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class DemoSeedProfileOffTest {

    private static final List<String> SEEDED_TABLES = List.of(
            "users", "docker_hosts", "deployments", "deployment_versions",
            "permissions", "notifications", "audit_log");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void noDemoRowsWithoutDemoProfile() {
        for (String table : SEEDED_TABLES) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM " + table + " WHERE id::text LIKE '0de00000-%'", Integer.class);
            assertThat(count).as("demo rows in " + table).isZero();
        }
    }

    @Test
    void demoSeedMigrationNotApplicationWithoutDemoProfile() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE script = 'R__demo_seed.sql'", Integer.class);
        assertThat(count).isZero();
    }
}
