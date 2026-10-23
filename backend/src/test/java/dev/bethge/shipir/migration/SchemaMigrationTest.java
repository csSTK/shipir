package dev.bethge.shipir.migration;

import dev.bethge.shipir.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the V1-V8 schema. Each test rolls back, so the shared Testcontainers DB stays clean.
 * A failing insert aborts the Postgres transaction, so each test asserts at most one violation, as its last statement.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
@Transactional
class SchemaMigrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void flywayAppliedVersions1To8Successfully() {
        List<String> versions = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class);
        assertThat(versions).containsExactly("1", "2", "3", "4", "5", "6", "7", "8");
    }

    @Test
    void tlsCertRefColumnIsRemovedFromDockerHosts() {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'docker_hosts' AND column_name = 'tls_cert_ref'
                """, Integer.class);
        assertThat(count).isZero();
    }

    @Test
    void tlsColumnsAreNullableByteaColumns() {
        List<Map<String, Object>> columns = jdbc.queryForList("""
                SELECT column_name, data_type, is_nullable FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'docker_hosts'
                  AND column_name IN ('tls_ca_encrypted', 'tls_cert_encrypted', 'tls_key_encrypted')
                """);
        assertThat(columns).hasSize(3);
        assertThat(columns).allSatisfy(column -> {
            assertThat(column.get("data_type")).isEqualTo("bytea");
            assertThat(column.get("is_nullable")).isEqualTo("YES");
        });
    }

    @Test
    void localHostWithoutHostPortOrTlsIsAccepted() {
        insertHost("local", "LOCAL", null, null, insertUser("alice"));
        assertThat(count("docker_hosts")).isEqualTo(1);
    }

    @Test
    void tcpHostWithAllTlsColumnsIsAccepted() {
        UUID owner = insertUser("alice");
        jdbc.update("""
                INSERT INTO docker_hosts (name, connection_type, host, port,
                    tls_ca_encrypted, tls_cert_encrypted, tls_key_encrypted, owner_user_id)
                VALUES ('tls', 'TCP', 'docker.local', 2376, ?, ?, ?, ?)
                """, new byte[] {1}, new byte[] {2}, new byte[] {3}, owner);
        assertThat(count("docker_hosts")).isEqualTo(1);
    }

    @Test
    void tcpPortBoundariesOneAndMaxAreAccepted() {
        UUID owner = insertUser("alice");
        insertHost("low", "TCP", "docker.local", 1, owner);
        insertHost("high", "TCP", "docker.local", 65535, owner);
        assertThat(count("docker_hosts")).isEqualTo(2);
    }

    @Test
    void invalidConnectionTypeIsRejected() {
        // The shape check also rejects any type other than LOCAL/TCP, and Postgres may report either,
        // so the test asserts the rejection itself rather than a specific constraint name.
        UUID owner = insertUser("alice");
        assertThatThrownBy(() -> insertHost("ssh", "SSH", "docker.local", 22, owner))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void tcpWithoutHostIsRejected() {
        UUID owner = insertUser("alice");
        assertThatThrownBy(() -> insertHost("tcp", "TCP", null, 2376, owner))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("docker_hosts_connection_shape_check");
    }

    @Test
    void tcpWithoutPortIsRejected() {
        UUID owner = insertUser("alice");
        assertThatThrownBy(() -> insertHost("tcp", "TCP", "docker.local", null, owner))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("docker_hosts_connection_shape_check");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 65536, -1})
    void tcpPortOutsideValidRangeIsRejected(int port) {
        UUID owner = insertUser("alice");
        assertThatThrownBy(() -> insertHost("tcp", "TCP", "docker.local", port, owner))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("docker_hosts_connection_shape_check");
    }

    @Test
    void localWithHostIsRejected() {
        UUID owner = insertUser("alice");
        assertThatThrownBy(() -> insertHost("local", "LOCAL", "docker.local", null, owner))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("docker_hosts_connection_shape_check");
    }

    @Test
    void localWithPortIsRejected() {
        UUID owner = insertUser("alice");
        assertThatThrownBy(() -> insertHost("local", "LOCAL", null, 2376, owner))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("docker_hosts_connection_shape_check");
    }

    @Test
    void localWithTlsMaterialIsRejected() {
        UUID owner = insertUser("alice");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO docker_hosts (name, connection_type, tls_ca_encrypted, owner_user_id)
                VALUES ('local', 'LOCAL', ?, ?)
                """, new byte[] {1}, owner))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("docker_hosts_connection_shape_check");
    }

    @ParameterizedTest
    @ValueSource(strings = {"tls_ca_encrypted", "tls_cert_encrypted", "tls_key_encrypted"})
    void partialTlsMaterialIsRejected(String presentColumn) {
        // Only one of the three TLS columns is set. The column name comes from the fixed list above.
        UUID owner = insertUser("alice");
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO docker_hosts (name, connection_type, host, port, " + presentColumn
                        + ", owner_user_id) VALUES ('tls', 'TCP', 'docker.local', 2376, ?, ?)",
                new byte[] {1}, owner))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("docker_hosts_tls_all_or_none_check");
    }

    @Test
    void duplicateDockerHostNameIsRejected() {
        UUID owner = insertUser("alice");
        insertHost("same", "LOCAL", null, null, owner);
        assertThatThrownBy(() -> insertHost("same", "LOCAL", null, null, owner))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("docker_hosts_name_key");
    }

    @Test
    void allTimestampColumnsAreTimestamptz() {
        List<String> wrong = jdbc.queryForList("""
                SELECT table_name || '.' || column_name FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name IN ('users','docker_hosts','stacks','stack_versions')
                  AND (column_name LIKE '%\\_at' ESCAPE '\\')
                  AND data_type <> 'timestamp with time zone'
                """, String.class);
        assertThat(wrong).isEmpty();

        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = 'public' AND data_type = 'timestamp with time zone'
                  AND table_name IN ('users','docker_hosts','stacks','stack_versions')
                """, Integer.class);
        assertThat(count).isEqualTo(6); // 2 users + 1 hosts + 2 stacks + 1 versions
    }

    @Test
    void usersPasswordChangedAtIsNullableTimestamptz() {
        Map<String, Object> column = jdbc.queryForMap("""
                SELECT data_type, is_nullable FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'users' AND column_name = 'password_changed_at'
                """);
        assertThat(column.get("data_type")).isEqualTo("timestamp with time zone");
        assertThat(column.get("is_nullable")).isEqualTo("YES");
    }

    @Test
    void userInsertedWithoutPasswordChangedAtHasNull() {
        UUID user = insertUser("alice");
        Timestamp passwordChangedAt = jdbc.queryForObject(
                "SELECT password_changed_at FROM users WHERE id = ?", Timestamp.class, user);
        assertThat(passwordChangedAt).isNull();
    }

    @Test
    void usernameIsUnique() {
        insertUser("alice");
        assertThatThrownBy(() -> insertUser("alice")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void stackNameIsUniquePerHostButNotAcrossHosts() {
        UUID user = insertUser("alice");
        UUID host1 = insertHost(user);
        UUID host2 = insertHost(user);
        insertStack(host1, user, "web");
        insertStack(host2, user, "web");
        assertThatThrownBy(() -> insertStack(host1, user, "web"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingHostCascadesToStacksAndVersions() {
        UUID user = insertUser("alice");
        UUID host = insertHost(user);
        UUID stack = insertStack(host, user, "web");
        insertVersion(stack, user);

        jdbc.update("DELETE FROM docker_hosts WHERE id = ?", host);

        assertThat(count("stacks")).isZero();
        assertThat(count("stack_versions")).isZero();
        assertThat(count("users")).isEqualTo(2); // alice + the seeded initial admin
    }

    @Test
    void deletingUserOwningHostFails() {
        UUID user = insertUser("alice");
        insertHost(user);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM users WHERE id = ?", user))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingUserWhoCreatedStackFails() {
        UUID owner = insertUser("owner");
        UUID creator = insertUser("creator");
        UUID host = insertHost(owner);
        insertStack(host, creator, "web");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM users WHERE id = ?", creator))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingUserWhoCreatedVersionFails() {
        UUID owner = insertUser("owner");
        UUID author = insertUser("author");
        UUID stack = insertStack(insertHost(owner), owner, "web");
        insertVersion(stack, author);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM users WHERE id = ?", author))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void passwordResetCodesHasExpectedColumnsAndTypes() {
        List<Map<String, Object>> columns = jdbc.queryForList("""
                SELECT column_name, data_type, is_nullable, column_default FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'password_reset_codes'
                """);
        Map<String, Map<String, Object>> byName = columns.stream()
                .collect(java.util.stream.Collectors.toMap(c -> (String) c.get("column_name"), c -> c));
        assertThat(byName.keySet()).containsExactlyInAnyOrder(
                "id", "user_id", "code_hash", "expires_at", "failed_attempts", "used_at", "created_at");

        assertThat(byName.get("id").get("data_type")).isEqualTo("uuid");
        assertThat(byName.get("user_id").get("data_type")).isEqualTo("uuid");
        assertThat(byName.get("user_id").get("is_nullable")).isEqualTo("NO");
        assertThat(byName.get("code_hash").get("data_type")).isEqualTo("character varying");
        assertThat(byName.get("code_hash").get("is_nullable")).isEqualTo("NO");
        assertThat(byName.get("expires_at").get("data_type")).isEqualTo("timestamp with time zone");
        assertThat(byName.get("expires_at").get("is_nullable")).isEqualTo("NO");
        assertThat(byName.get("failed_attempts").get("data_type")).isEqualTo("integer");
        assertThat(byName.get("failed_attempts").get("is_nullable")).isEqualTo("NO");
        assertThat(byName.get("used_at").get("data_type")).isEqualTo("timestamp with time zone");
        assertThat(byName.get("used_at").get("is_nullable")).isEqualTo("YES");
        assertThat(byName.get("created_at").get("data_type")).isEqualTo("timestamp with time zone");
        assertThat(byName.get("created_at").get("is_nullable")).isEqualTo("NO");
    }

    @Test
    void passwordResetCodeHashIsVarchar64() {
        Integer maxLength = jdbc.queryForObject("""
                SELECT character_maximum_length FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'password_reset_codes' AND column_name = 'code_hash'
                """, Integer.class);
        assertThat(maxLength).isEqualTo(64);
    }

    @Test
    void passwordResetCodeFailedAttemptsDefaultsToZero() {
        UUID user = insertUser("alice");
        jdbc.update("INSERT INTO password_reset_codes (user_id, code_hash, expires_at) VALUES (?, 'hash', now())", user);
        Integer failedAttempts = jdbc.queryForObject(
                "SELECT failed_attempts FROM password_reset_codes WHERE user_id = ?", Integer.class, user);
        assertThat(failedAttempts).isZero();
    }

    @Test
    void passwordResetCodeIdAndCreatedAtHaveDefaults() {
        UUID user = insertUser("alice");
        jdbc.update("INSERT INTO password_reset_codes (user_id, code_hash, expires_at) VALUES (?, 'hash', now())", user);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT id, created_at FROM password_reset_codes WHERE user_id = ?", user);
        assertThat(row.get("id")).isNotNull();
        assertThat(row.get("created_at")).isNotNull();
    }

    @Test
    void negativeFailedAttemptsIsRejected() {
        UUID user = insertUser("alice");
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO password_reset_codes (user_id, code_hash, expires_at, failed_attempts) "
                        + "VALUES (?, 'hash', now(), -1)", user))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("password_reset_codes_failed_attempts_check");
    }

    @Test
    void passwordResetCodeWithoutCodeHashIsRejected() {
        UUID user = insertUser("alice");
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO password_reset_codes (user_id, code_hash, expires_at) VALUES (?, NULL, now())", user))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void passwordResetCodeWithoutExpiresAtIsRejected() {
        UUID user = insertUser("alice");
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO password_reset_codes (user_id, code_hash, expires_at) VALUES (?, 'hash', NULL)", user))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingUserCascadesToPasswordResetCodes() {
        UUID user = insertUser("alice");
        jdbc.update("INSERT INTO password_reset_codes (user_id, code_hash, expires_at) VALUES (?, 'hash', now())", user);

        jdbc.update("DELETE FROM users WHERE id = ?", user);

        assertThat(count("password_reset_codes")).isZero();
    }

    @Test
    void passwordResetCodesHasIndexOnUserId() {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM pg_indexes
                WHERE schemaname = 'public' AND tablename = 'password_reset_codes'
                  AND indexdef LIKE '%(user_id)%'
                """, Integer.class);
        assertThat(count).isGreaterThanOrEqualTo(1);
    }

    @Test
    void auditLogHasExpectedColumnsAndTypes() {
        List<Map<String, Object>> columns = jdbc.queryForList("""
                SELECT column_name, data_type, is_nullable FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'audit_log'
                """);
        Map<String, Map<String, Object>> byName = columns.stream()
                .collect(java.util.stream.Collectors.toMap(c -> (String) c.get("column_name"), c -> c));
        assertThat(byName.keySet()).containsExactlyInAnyOrder(
                "id", "occurred_at", "user_id", "username", "action", "outcome",
                "target_type", "target_id", "details");

        assertThat(byName.get("id").get("data_type")).isEqualTo("uuid");
        assertThat(byName.get("occurred_at").get("data_type")).isEqualTo("timestamp with time zone");
        assertThat(byName.get("occurred_at").get("is_nullable")).isEqualTo("NO");
        assertThat(byName.get("user_id").get("data_type")).isEqualTo("uuid");
        assertThat(byName.get("user_id").get("is_nullable")).isEqualTo("YES");
        assertThat(byName.get("username").get("is_nullable")).isEqualTo("NO");
        assertThat(byName.get("action").get("is_nullable")).isEqualTo("NO");
        assertThat(byName.get("outcome").get("is_nullable")).isEqualTo("NO");
        assertThat(byName.get("target_type").get("is_nullable")).isEqualTo("YES");
        assertThat(byName.get("target_id").get("is_nullable")).isEqualTo("YES");
        assertThat(byName.get("details").get("data_type")).isEqualTo("jsonb");
        assertThat(byName.get("details").get("is_nullable")).isEqualTo("YES");
    }

    @Test
    void auditLogOccurredAtDefaultsToNow() {
        jdbc.update("INSERT INTO audit_log (username, action, outcome) VALUES ('alice', 'AUTH_LOGIN', 'SUCCESS')");
        Timestamp occurredAt = jdbc.queryForObject(
                "SELECT occurred_at FROM audit_log WHERE username = 'alice'", Timestamp.class);
        assertThat(occurredAt).isNotNull();
    }

    @Test
    void auditLogIdDefaultsToGeneratedUuid() {
        jdbc.update("INSERT INTO audit_log (username, action, outcome) VALUES ('alice', 'AUTH_LOGIN', 'FAILURE')");
        UUID id = jdbc.queryForObject("SELECT id FROM audit_log WHERE username = 'alice'", UUID.class);
        assertThat(id).isNotNull();
    }

    @Test
    void auditLogAcceptsFailedLoginWithoutUser() {
        jdbc.update("INSERT INTO audit_log (username, action, outcome) VALUES ('unknown', 'AUTH_LOGIN', 'FAILURE')");
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE user_id IS NULL AND username = 'unknown'", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void auditLogOutcomeCheckRejectsOtherValues() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO audit_log (username, action, outcome) VALUES ('alice', 'AUTH_LOGIN', 'PENDING')"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("audit_log_outcome_check");
    }

    @Test
    void deletingUserKeepsAuditRowWithNullUserAndUsernameSnapshot() {
        UUID user = insertUser("alice");
        jdbc.update("INSERT INTO audit_log (user_id, username, action, outcome) VALUES (?, 'alice', 'AUTH_LOGIN', 'SUCCESS')", user);

        jdbc.update("DELETE FROM users WHERE id = ?", user);

        Map<String, Object> row = jdbc.queryForMap("SELECT user_id, username FROM audit_log");
        assertThat(row.get("user_id")).isNull();
        assertThat(row.get("username")).isEqualTo("alice");
    }

    @Test
    void auditLogHasIndexesOnOccurredAtAndActionOccurredAt() {
        List<String> definitions = jdbc.queryForList("""
                SELECT indexdef FROM pg_indexes
                WHERE schemaname = 'public' AND tablename = 'audit_log'
                """, String.class);
        assertThat(definitions).anyMatch(d -> d.contains("(occurred_at DESC)"));
        assertThat(definitions).anyMatch(d -> d.contains("(action, occurred_at)"));
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    private UUID insertUser(String username) {
        return jdbc.queryForObject(
                "INSERT INTO users (username, password_hash, role) VALUES (?, 'hash', 'ADMIN') RETURNING id",
                UUID.class, username);
    }

    private UUID insertHost(UUID owner) {
        return insertHost("host-" + UUID.randomUUID(), "LOCAL", null, null, owner);
    }

    private UUID insertHost(String name, String connectionType, String host, Integer port, UUID owner) {
        return jdbc.queryForObject("""
                INSERT INTO docker_hosts (name, connection_type, host, port, owner_user_id)
                VALUES (?, ?, ?, ?, ?) RETURNING id
                """, UUID.class, name, connectionType, host, port, owner);
    }

    private UUID insertStack(UUID host, UUID creator, String name) {
        return jdbc.queryForObject("""
                INSERT INTO stacks (name, host_id, compose_file_path, created_by)
                VALUES (?, ?, '/stacks/x/compose.yml', ?) RETURNING id
                """, UUID.class, name, host, creator);
    }

    private void insertVersion(UUID stack, UUID creator) {
        jdbc.update("INSERT INTO stack_versions (stack_id, yaml_content, created_by) VALUES (?, 'services: {}', ?)",
                stack, creator);
    }
}
