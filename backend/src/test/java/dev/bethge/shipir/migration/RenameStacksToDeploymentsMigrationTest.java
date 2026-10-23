package dev.bethge.shipir.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V16 renames stacks to deployments. Uses its own throwaway database (not the shared Spring one) because it must
 * stop at V15, insert legacy data and only then apply V16.
 */
class RenameStacksToDeploymentsMigrationTest {

    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");
    static JdbcTemplate jdbc;

    static UUID host;
    static UUID user;
    static UUID stack;
    static UUID version;

    @BeforeAll
    static void migrateToV15InsertLegacyDataThenApplyV16() {
        postgres.start();
        var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).target("15").load().migrate();

        user = jdbc.queryForObject("""
                INSERT INTO users (username, password_hash, role) VALUES ('alice', 'x', 'ADMIN') RETURNING id
                """, UUID.class);
        host = jdbc.queryForObject("""
                INSERT INTO docker_hosts (name, connection_type, owner_user_id) VALUES ('local', 'LOCAL', ?) RETURNING id
                """, UUID.class, user);
        stack = jdbc.queryForObject("""
                INSERT INTO stacks (name, host_id, compose_file_path, created_by, status)
                VALUES ('web', ?, 'web/compose.yaml', ?, 'RUNNING') RETURNING id
                """, UUID.class, host, user);
        version = jdbc.queryForObject("""
                INSERT INTO stack_versions (stack_id, yaml_content, created_by, source)
                VALUES (?, 'services: {}', ?, 'MANUAL') RETURNING id
                """, UUID.class, stack, user);
        jdbc.update("UPDATE stacks SET current_version_id = ? WHERE id = ?", version, stack);

        Flyway.configure().dataSource(dataSource).load().migrate();
    }

    @AfterAll
    static void stop() {
        postgres.stop();
    }

    @Test
    void dataInsertedBeforeV16SurvivesUnderTheNewNames() {
        var row = jdbc.queryForMap("SELECT name, status, current_version_id, host_id FROM deployments WHERE id = ?", stack);
        assertThat(row).containsEntry("name", "web").containsEntry("status", "RUNNING")
                .containsEntry("current_version_id", version).containsEntry("host_id", host);
        assertThat(jdbc.queryForObject(
                "SELECT deployment_id FROM deployment_versions WHERE id = ?", UUID.class, version)).isEqualTo(stack);
    }

    @Test
    void oldTablesAndColumnsAreGone() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String.class);
        assertThat(tables).contains("deployments", "deployment_versions").doesNotContain("stacks", "stack_versions");
        Integer oldColumn = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = 'public' AND column_name = 'stack_id'
                """, Integer.class);
        assertThat(oldColumn).isZero();
    }

    @Test
    void constraintsAndIndexesAreRenamedAndNoStackNamesRemain() {
        List<String> constraints = jdbc.queryForList("""
                SELECT conname FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
                WHERE t.relname IN ('deployments', 'deployment_versions')
                """, String.class);
        assertThat(constraints).contains("deployments_pkey", "deployments_host_id_name_key",
                "deployments_git_columns_check", "deployment_versions_deployment_id_fkey",
                "deployment_versions_source_check");
        assertThat(constraints).noneMatch(name -> name.contains("stack"));
        List<String> indexes = jdbc.queryForList("""
                SELECT indexname FROM pg_indexes WHERE tablename IN ('deployments', 'deployment_versions')
                """, String.class);
        assertThat(indexes).contains("idx_deployment_versions_deployment_id", "idx_deployments_git_credential_id");
        assertThat(indexes).noneMatch(name -> name.contains("stack"));
    }

    @Test
    void foreignKeyRulesAreUnchanged() {
        List<String> rules = jdbc.queryForList("""
                SELECT conname || ':' || confdeltype::text FROM pg_constraint
                WHERE conname IN ('deployments_host_id_fkey', 'deployment_versions_deployment_id_fkey',
                                  'deployments_current_version_id_fkey', 'deployments_git_credential_id_fkey',
                                  'deployments_created_by_fkey')
                """, String.class);
        // a = no action, c = cascade, n = set null, r = restrict
        assertThat(rules).containsExactlyInAnyOrder(
                "deployments_host_id_fkey:c", "deployment_versions_deployment_id_fkey:c",
                "deployments_current_version_id_fkey:n", "deployments_git_credential_id_fkey:r",
                "deployments_created_by_fkey:a");
    }

    @Test
    void deletingHostStillCascadesToDeploymentsAndVersions() {
        UUID otherHost = jdbc.queryForObject("""
                INSERT INTO docker_hosts (name, connection_type, owner_user_id) VALUES ('doomed', 'LOCAL', ?) RETURNING id
                """, UUID.class, user);
        UUID d = jdbc.queryForObject("""
                INSERT INTO deployments (name, host_id, compose_file_path, created_by)
                VALUES ('gone', ?, 'gone/compose.yaml', ?) RETURNING id
                """, UUID.class, otherHost, user);
        jdbc.update("INSERT INTO deployment_versions (deployment_id, yaml_content, created_by) VALUES (?, 'x', ?)", d, user);
        jdbc.update("DELETE FROM docker_hosts WHERE id = ?", otherHost);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deployments WHERE id = ?", Integer.class, d)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM deployment_versions WHERE deployment_id = ?", Integer.class, d)).isZero();
    }
}
