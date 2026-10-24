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
 * Verifies the V1-V22 schema. Each test rolls back, so the shared Testcontainers DB stays clean.
 * A failing insert aborts the Postgres transaction, so each test asserts at most one violation, as its last statement.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
@Transactional
class SchemaMigrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void flywayAppliedVersions1To21Successfully() {
        List<String> versions = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class);
        assertThat(versions).containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16", "17", "18", "19", "20", "21", "22");
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
    void secondLocalHostIsRejectedButManyTcpHostsAreAccepted() {
        UUID owner = insertUser("alice");
        insertHost("local", "LOCAL", null, null, owner);
        insertHost("tcp-1", "TCP", "docker.local", 2375, owner);
        insertHost("tcp-2", "TCP", "docker.local", 2376, owner);
        assertThat(count("docker_hosts")).isEqualTo(3);
        assertThatThrownBy(() -> insertHost("local-2", "LOCAL", null, null, owner))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("docker_hosts_single_local_idx");
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
        insertHost("same", "TCP", "docker.local", 2375, owner);
        assertThatThrownBy(() -> insertHost("same", "TCP", "docker.local", 2375, owner))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("docker_hosts_name_key");
    }

    @Test
    void allTimestampColumnsAreTimestamptz() {
        List<String> wrong = jdbc.queryForList("""
                SELECT table_name || '.' || column_name FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name IN ('users','docker_hosts','deployments','deployment_versions')
                  AND (column_name LIKE '%\\_at' ESCAPE '\\')
                  AND data_type <> 'timestamp with time zone'
                """, String.class);
        assertThat(wrong).isEmpty();

        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = 'public' AND data_type = 'timestamp with time zone'
                  AND table_name IN ('users','docker_hosts','deployments','deployment_versions')
                """, Integer.class);
        assertThat(count).isEqualTo(9); // 3 users + 1 hosts + 3 deployments + 2 versions
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
    void usersDisabledAtIsNullableTimestamptz() {
        Map<String, Object> column = jdbc.queryForMap("""
                SELECT data_type, is_nullable FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'users' AND column_name = 'disabled_at'
                """);
        assertThat(column.get("data_type")).isEqualTo("timestamp with time zone");
        assertThat(column.get("is_nullable")).isEqualTo("YES");
    }

    @Test
    void userInsertedWithoutDisabledAtIsEnabled() {
        UUID user = insertUser("alice");
        Timestamp disabledAt = jdbc.queryForObject(
                "SELECT disabled_at FROM users WHERE id = ?", Timestamp.class, user);
        assertThat(disabledAt).isNull();
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
    void deploymentNameIsUniquePerHostButNotAcrossHosts() {
        UUID user = insertUser("alice");
        UUID host1 = insertHost(user);
        UUID host2 = insertHost(user);
        insertDeployment(host1, user, "web");
        insertDeployment(host2, user, "web");
        assertThatThrownBy(() -> insertDeployment(host1, user, "web"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingHostCascadesToDeploymentsAndVersions() {
        UUID user = insertUser("alice");
        UUID host = insertHost(user);
        UUID deployment = insertDeployment(host, user, "web");
        insertVersion(deployment, user);

        jdbc.update("DELETE FROM docker_hosts WHERE id = ?", host);

        assertThat(count("deployments")).isZero();
        assertThat(count("deployment_versions")).isZero();
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
    void deletingUserWhoCreatedDeploymentFails() {
        UUID owner = insertUser("owner");
        UUID creator = insertUser("creator");
        UUID host = insertHost(owner);
        insertDeployment(host, creator, "web");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM users WHERE id = ?", creator))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingUserWhoCreatedVersionFails() {
        UUID owner = insertUser("owner");
        UUID author = insertUser("author");
        UUID deployment = insertDeployment(insertHost(owner), owner, "web");
        insertVersion(deployment, author);
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

    @Test
    void notificationsHasExpectedColumnsAndTypes() {
        List<Map<String, Object>> columns = jdbc.queryForList("""
                SELECT column_name, data_type, is_nullable FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'notifications'
                """);
        Map<String, Map<String, Object>> byName = columns.stream()
                .collect(java.util.stream.Collectors.toMap(c -> (String) c.get("column_name"), c -> c));
        assertThat(byName.keySet()).containsExactlyInAnyOrder(
                "id", "user_id", "type", "host_id", "host_name", "message", "created_at", "read_at");

        assertThat(byName.get("id").get("data_type")).isEqualTo("uuid");
        assertThat(byName.get("user_id").get("is_nullable")).isEqualTo("NO");
        assertThat(byName.get("type").get("is_nullable")).isEqualTo("NO");
        assertThat(byName.get("host_id").get("data_type")).isEqualTo("uuid");
        assertThat(byName.get("host_id").get("is_nullable")).isEqualTo("YES");
        assertThat(byName.get("host_name").get("is_nullable")).isEqualTo("NO");
        assertThat(byName.get("message").get("is_nullable")).isEqualTo("NO");
        assertThat(byName.get("created_at").get("data_type")).isEqualTo("timestamp with time zone");
        assertThat(byName.get("created_at").get("is_nullable")).isEqualTo("NO");
        assertThat(byName.get("read_at").get("data_type")).isEqualTo("timestamp with time zone");
        assertThat(byName.get("read_at").get("is_nullable")).isEqualTo("YES");
    }

    @Test
    void notificationIdAndCreatedAtHaveDefaults() {
        UUID user = insertUser("alice");
        jdbc.update("""
                INSERT INTO notifications (user_id, type, host_name, message)
                VALUES (?, 'HOST_DOWN', 'prod', 'Host prod is down')
                """, user);
        Map<String, Object> row = jdbc.queryForMap("SELECT id, created_at, read_at FROM notifications");
        assertThat(row.get("id")).isNotNull();
        assertThat(row.get("created_at")).isNotNull();
        assertThat(row.get("read_at")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"HOST_DOWN", "HOST_UP"})
    void notificationAcceptsKnownTypes(String type) {
        UUID user = insertUser("alice");
        insertNotification(user, type);
        assertThat(count("notifications")).isEqualTo(1);
    }

    @Test
    void notificationRejectsUnknownType() {
        UUID user = insertUser("alice");
        assertThatThrownBy(() -> insertNotification(user, "HOST_EXPLODED"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("notifications_type_check");
    }

    @Test
    void notificationPreferenceRejectsUnknownType() {
        UUID user = insertUser("alice");
        assertThatThrownBy(() -> insertPreference(user, "HOST_EXPLODED", true))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("notification_preferences_type_check");
    }

    @Test
    void notificationPreferenceRequiresInAppEnabled() {
        UUID user = insertUser("alice");
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO notification_preferences (user_id, type, in_app_enabled) VALUES (?, 'HOST_DOWN', NULL)",
                user))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingUserCascadesToNotificationsAndPreferences() {
        UUID user = insertUser("alice");
        insertNotification(user, "HOST_DOWN");
        insertPreference(user, "HOST_DOWN", true);

        jdbc.update("DELETE FROM users WHERE id = ?", user);

        assertThat(count("notifications")).isZero();
        assertThat(count("notification_preferences")).isZero();
    }

    @Test
    void deletingHostSetsNotificationHostIdNullAndKeepsHostName() {
        UUID owner = insertUser("alice");
        UUID host = insertHost("prod", "LOCAL", null, null, owner);
        UUID user = insertUser("bob");
        jdbc.update("""
                INSERT INTO notifications (user_id, type, host_id, host_name, message)
                VALUES (?, 'HOST_DOWN', ?, 'prod', 'Host prod is down')
                """, user, host);

        jdbc.update("DELETE FROM docker_hosts WHERE id = ?", host);

        Map<String, Object> row = jdbc.queryForMap("SELECT host_id, host_name FROM notifications");
        assertThat(row.get("host_id")).isNull();
        assertThat(row.get("host_name")).isEqualTo("prod");
    }

    @Test
    void notificationPreferencePrimaryKeyRejectsDuplicateUserAndType() {
        UUID user = insertUser("alice");
        insertPreference(user, "HOST_DOWN", true);
        assertThatThrownBy(() -> insertPreference(user, "HOST_DOWN", false))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("notification_preferences_pkey");
    }

    @Test
    void notificationsHasIndexOnUserReadAtCreatedAt() {
        List<String> definitions = jdbc.queryForList("""
                SELECT indexdef FROM pg_indexes
                WHERE schemaname = 'public' AND tablename = 'notifications'
                """, String.class);
        assertThat(definitions).anyMatch(d -> d.contains("(user_id, read_at, created_at DESC)"));
    }

    @Test
    void notificationWebhooksHasExpectedColumnsAndTypes() {
        List<Map<String, Object>> columns = jdbc.queryForList("""
                SELECT column_name, data_type, is_nullable FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'notification_webhooks'
                """);
        Map<String, Map<String, Object>> byName = columns.stream()
                .collect(java.util.stream.Collectors.toMap(c -> (String) c.get("column_name"), c -> c));
        assertThat(byName.keySet()).containsExactlyInAnyOrder(
                "id", "name", "url_encrypted", "url_hint", "event_types", "enabled", "created_by",
                "created_at", "updated_at", "last_delivery_at", "last_delivery_status");

        assertThat(byName.get("id").get("data_type")).isEqualTo("uuid");
        assertThat(byName.get("name").get("data_type")).isEqualTo("character varying");
        assertThat(byName.get("name").get("is_nullable")).isEqualTo("NO");
        assertThat(byName.get("url_encrypted").get("data_type")).isEqualTo("bytea");
        assertThat(byName.get("url_encrypted").get("is_nullable")).isEqualTo("NO");
        assertThat(byName.get("url_hint").get("data_type")).isEqualTo("character varying");
        assertThat(byName.get("url_hint").get("is_nullable")).isEqualTo("NO");
        assertThat(byName.get("event_types").get("data_type")).isEqualTo("ARRAY");
        assertThat(byName.get("event_types").get("is_nullable")).isEqualTo("NO");
        assertThat(byName.get("enabled").get("data_type")).isEqualTo("boolean");
        assertThat(byName.get("enabled").get("is_nullable")).isEqualTo("NO");
        assertThat(byName.get("created_by").get("data_type")).isEqualTo("uuid");
        assertThat(byName.get("created_by").get("is_nullable")).isEqualTo("NO");
        assertThat(byName.get("created_at").get("data_type")).isEqualTo("timestamp with time zone");
        assertThat(byName.get("created_at").get("is_nullable")).isEqualTo("NO");
        assertThat(byName.get("updated_at").get("data_type")).isEqualTo("timestamp with time zone");
        assertThat(byName.get("updated_at").get("is_nullable")).isEqualTo("NO");
        assertThat(byName.get("last_delivery_at").get("data_type")).isEqualTo("timestamp with time zone");
        assertThat(byName.get("last_delivery_at").get("is_nullable")).isEqualTo("YES");
        assertThat(byName.get("last_delivery_status").get("data_type")).isEqualTo("character varying");
        assertThat(byName.get("last_delivery_status").get("is_nullable")).isEqualTo("YES");
    }

    @Test
    void notificationWebhooksHasNoPlaintextUrlColumn() {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'notification_webhooks'
                  AND (column_name LIKE '%url%' AND column_name <> 'url_encrypted' AND column_name <> 'url_hint')
                """, Integer.class);
        assertThat(count).isZero();
    }

    @Test
    void notificationWebhookNameIsUnique() {
        UUID user = insertUser("alice");
        insertWebhook("ops-hook", user);
        assertThatThrownBy(() -> insertWebhook("ops-hook", user))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("notification_webhooks_name_key");
    }

    @Test
    void notificationWebhookEnabledDefaultsToTrue() {
        UUID user = insertUser("alice");
        insertWebhook("ops-hook", user);
        Boolean enabled = jdbc.queryForObject(
                "SELECT enabled FROM notification_webhooks WHERE name = 'ops-hook'", Boolean.class);
        assertThat(enabled).isTrue();
    }

    @Test
    void notificationWebhookWithoutEncryptedUrlIsRejected() {
        UUID user = insertUser("alice");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO notification_webhooks (name, url_encrypted, url_hint, event_types, created_by)
                VALUES ('ops-hook', NULL, 'https://example.com', ARRAY['HOST_DOWN'], ?)
                """, user))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingUserWhoCreatedWebhookFails() {
        UUID creator = insertUser("creator");
        insertWebhook("ops-hook", creator);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM users WHERE id = ?", creator))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void retentionSettingsHasExactlyOneDefaultRow() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, audit_log_days, notification_days, updated_by FROM retention_settings");
        assertThat(rows).hasSize(1);
        assertThat(((Number) rows.get(0).get("id")).intValue()).isEqualTo(1);
        assertThat(rows.get(0).get("audit_log_days")).isEqualTo(365);
        assertThat(rows.get(0).get("notification_days")).isEqualTo(30);
        assertThat(rows.get(0).get("updated_by")).isNull();
    }

    @Test
    void retentionSettingsHasExpectedColumnsAndTypes() {
        List<Map<String, Object>> columns = jdbc.queryForList("""
                SELECT column_name, data_type, is_nullable FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'retention_settings'
                """);
        Map<String, Map<String, Object>> byName = columns.stream()
                .collect(java.util.stream.Collectors.toMap(c -> (String) c.get("column_name"), c -> c));
        assertThat(byName.keySet()).containsExactlyInAnyOrder(
                "id", "audit_log_days", "notification_days", "chat_session_days", "updated_at", "updated_by");
        assertThat(byName.get("chat_session_days").get("data_type")).isEqualTo("integer");
        assertThat(byName.get("chat_session_days").get("is_nullable")).isEqualTo("NO");
        assertThat(byName.get("id").get("data_type")).isEqualTo("smallint");
        assertThat(byName.get("audit_log_days").get("data_type")).isEqualTo("integer");
        assertThat(byName.get("audit_log_days").get("is_nullable")).isEqualTo("NO");
        assertThat(byName.get("notification_days").get("data_type")).isEqualTo("integer");
        assertThat(byName.get("notification_days").get("is_nullable")).isEqualTo("NO");
        assertThat(byName.get("updated_at").get("data_type")).isEqualTo("timestamp with time zone");
        assertThat(byName.get("updated_at").get("is_nullable")).isEqualTo("NO");
        assertThat(byName.get("updated_by").get("data_type")).isEqualTo("uuid");
        assertThat(byName.get("updated_by").get("is_nullable")).isEqualTo("YES");
    }

    @Test
    void retentionSettingsSecondRowIsRejected() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO retention_settings (id, audit_log_days, notification_days) VALUES (2, 365, 30)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 3651, -1})
    void retentionAuditLogDaysOutsideRangeIsRejected(int days) {
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE retention_settings SET audit_log_days = ? WHERE id = 1", days))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("retention_settings_audit_log_days_check");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 3651, -1})
    void retentionNotificationDaysOutsideRangeIsRejected(int days) {
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE retention_settings SET notification_days = ? WHERE id = 1", days))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("retention_settings_notification_days_check");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3650})
    void retentionDaysBoundariesAreAccepted(int days) {
        jdbc.update("UPDATE retention_settings SET audit_log_days = ?, notification_days = ? WHERE id = 1",
                days, days);
        Integer audit = jdbc.queryForObject("SELECT audit_log_days FROM retention_settings", Integer.class);
        assertThat(audit).isEqualTo(days);
    }

    @Test
    void deletingUserWhoUpdatedRetentionSettingsSetsUpdatedByNull() {
        UUID admin = insertUser("retention-editor");
        jdbc.update("UPDATE retention_settings SET updated_by = ? WHERE id = 1", admin);
        jdbc.update("DELETE FROM users WHERE id = ?", admin);
        UUID updatedBy = jdbc.queryForObject("SELECT updated_by FROM retention_settings WHERE id = 1", UUID.class);
        assertThat(updatedBy).isNull();
    }

    @Test
    void retentionChatSessionDaysDefaultsToNinety() {
        Integer days = jdbc.queryForObject("SELECT chat_session_days FROM retention_settings WHERE id = 1", Integer.class);
        assertThat(days).isEqualTo(90);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 3651, -1})
    void retentionChatSessionDaysOutsideRangeIsRejected(int days) {
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE retention_settings SET chat_session_days = ? WHERE id = 1", days))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("retention_settings_chat_session_days_check");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 3650})
    void retentionChatSessionDaysBoundariesAreAccepted(int days) {
        jdbc.update("UPDATE retention_settings SET chat_session_days = ? WHERE id = 1", days);
        Integer stored = jdbc.queryForObject("SELECT chat_session_days FROM retention_settings", Integer.class);
        assertThat(stored).isEqualTo(days);
    }

    @Test
    void aiSettingsKeepsOnlyGlobalSwitchAndActiveProvider() {
        List<Map<String, Object>> columns = jdbc.queryForList("""
                SELECT column_name, data_type, is_nullable, column_default FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'ai_settings'
                """);
        Map<String, Map<String, Object>> byName = columns.stream()
                .collect(java.util.stream.Collectors.toMap(c -> (String) c.get("column_name"), c -> c));
        assertThat(byName.keySet()).containsExactlyInAnyOrder(
                "id", "enabled", "active_provider", "updated_at", "updated_by");
        assertThat(byName.get("id").get("data_type")).isEqualTo("smallint");
        assertThat(byName.get("enabled").get("data_type")).isEqualTo("boolean");
        assertThat(byName.get("enabled").get("is_nullable")).isEqualTo("NO");
        assertThat(byName.get("active_provider").get("is_nullable")).isEqualTo("NO");
        assertThat(String.valueOf(byName.get("active_provider").get("column_default")))
                .startsWith("'OPENAI_COMPATIBLE'");
        assertThat(byName.get("updated_at").get("data_type")).isEqualTo("timestamp with time zone");
        assertThat(byName.get("updated_by").get("is_nullable")).isEqualTo("YES");
    }

    @Test
    void aiSettingsHasExactlyOneSeededRowDisabledWithOpenAiCompatibleActive() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, enabled, active_provider, updated_by FROM ai_settings");
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(((Number) row.get("id")).intValue()).isEqualTo(1);
        assertThat(row.get("enabled")).isEqualTo(false);
        assertThat(row.get("active_provider")).isEqualTo("OPENAI_COMPATIBLE");
        assertThat(row.get("updated_by")).isNull();
    }

    @Test
    void aiSettingsSecondRowIsRejected() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO ai_settings (id) VALUES (2)"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ai_settings_id_check");
    }

    @Test
    void aiSettingsActiveProviderMustBeAKnownProvider() {
        jdbc.update("UPDATE ai_settings SET active_provider = 'GEMINI' WHERE id = 1");
        assertThat(jdbc.queryForObject("SELECT active_provider FROM ai_settings", String.class)).isEqualTo("GEMINI");
        // last: a failed statement aborts the surrounding transaction
        assertThatThrownBy(() -> jdbc.update("UPDATE ai_settings SET active_provider = 'OTHER' WHERE id = 1"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aiSettingsActiveProviderIsBackedByAForeignKeyToTheConfigs() {
        List<String> targets = jdbc.queryForList("""
                SELECT ccu.table_name FROM information_schema.table_constraints tc
                JOIN information_schema.constraint_column_usage ccu ON ccu.constraint_name = tc.constraint_name
                WHERE tc.table_name = 'ai_settings' AND tc.constraint_type = 'FOREIGN KEY'
                  AND tc.constraint_name = 'ai_settings_active_provider_fkey'
                """, String.class);
        assertThat(targets).containsExactly("ai_provider_configs");
    }

    @Test
    void aiProviderConfigsHasExpectedColumnsAndTypes() {
        List<Map<String, Object>> columns = jdbc.queryForList("""
                SELECT column_name, data_type, is_nullable FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'ai_provider_configs'
                """);
        Map<String, Map<String, Object>> byName = columns.stream()
                .collect(java.util.stream.Collectors.toMap(c -> (String) c.get("column_name"), c -> c));
        assertThat(byName.keySet()).containsExactlyInAnyOrder(
                "provider", "base_url", "model", "api_key_ciphertext", "api_key_hint", "updated_at", "updated_by");
        assertThat(byName.get("provider").get("is_nullable")).isEqualTo("NO");
        assertThat(byName.get("base_url").get("is_nullable")).isEqualTo("NO");
        assertThat(byName.get("model").get("is_nullable")).isEqualTo("NO");
        assertThat(byName.get("api_key_ciphertext").get("data_type")).isEqualTo("bytea");
        assertThat(byName.get("api_key_ciphertext").get("is_nullable")).isEqualTo("YES");
        assertThat(byName.get("api_key_hint").get("is_nullable")).isEqualTo("YES");
        assertThat(byName.get("updated_at").get("data_type")).isEqualTo("timestamp with time zone");
        assertThat(byName.get("updated_by").get("is_nullable")).isEqualTo("YES");
    }

    @Test
    void aiProviderConfigsAreSeededForBothProvidersWithoutKeys() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT provider, base_url, model, api_key_ciphertext, api_key_hint, updated_by FROM ai_provider_configs");
        assertThat(rows).hasSize(2);
        Map<String, Map<String, Object>> byProvider = rows.stream()
                .collect(java.util.stream.Collectors.toMap(r -> (String) r.get("provider"), r -> r));
        Map<String, Object> openAi = byProvider.get("OPENAI_COMPATIBLE");
        assertThat(openAi.get("base_url")).isEqualTo("https://integrate.api.nvidia.com/v1");
        assertThat(openAi.get("model")).isEqualTo("z-ai/glm-5.3-flash");
        Map<String, Object> gemini = byProvider.get("GEMINI");
        assertThat(gemini.get("base_url")).isEqualTo("https://generativelanguage.googleapis.com/v1beta");
        assertThat(gemini.get("model")).isEqualTo("gemini-flash-latest");
        rows.forEach(r -> {
            assertThat(r.get("api_key_ciphertext")).isNull();
            assertThat(r.get("api_key_hint")).isNull();
            assertThat(r.get("updated_by")).isNull();
        });
    }

    @Test
    void aiProviderConfigsRejectUnknownProvider() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO ai_provider_configs (provider, base_url, model) VALUES ('OTHER', 'https://x', 'm')"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ai_provider_configs_provider_check");
    }

    @Test
    void aiProviderConfigsStoreApiKeyOnlyAsCiphertextAndHint() {
        // No plaintext column exists; the ciphertext is a BYTEA and the hint is the only readable form.
        List<String> columns = jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'ai_provider_configs' AND column_name LIKE 'api_key%'
                """, String.class);
        assertThat(columns).containsExactlyInAnyOrder("api_key_ciphertext", "api_key_hint");
    }

    @Test
    void deletingUserWhoUpdatedAiProviderConfigSetsUpdatedByNull() {
        UUID admin = insertUser("ai-editor");
        jdbc.update("UPDATE ai_provider_configs SET updated_by = ? WHERE provider = 'GEMINI'", admin);
        jdbc.update("DELETE FROM users WHERE id = ?", admin);
        UUID updatedBy = jdbc.queryForObject(
                "SELECT updated_by FROM ai_provider_configs WHERE provider = 'GEMINI'", UUID.class);
        assertThat(updatedBy).isNull();
    }

    @Test
    void deletingUserWhoUpdatedAiSettingsSetsUpdatedByNull() {
        UUID admin = insertUser("ai-settings-editor");
        jdbc.update("UPDATE ai_settings SET updated_by = ? WHERE id = 1", admin);
        jdbc.update("DELETE FROM users WHERE id = ?", admin);
        UUID updatedBy = jdbc.queryForObject("SELECT updated_by FROM ai_settings WHERE id = 1", UUID.class);
        assertThat(updatedBy).isNull();
    }


    @Test
    void chatSessionsHasExpectedColumnsAndTypes() {
        List<Map<String, Object>> columns = jdbc.queryForList("""
                SELECT column_name, data_type, is_nullable, column_default FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'chat_sessions'
                """);
        Map<String, Map<String, Object>> byName = columns.stream()
                .collect(java.util.stream.Collectors.toMap(c -> (String) c.get("column_name"), c -> c));
        assertThat(byName.keySet()).containsExactlyInAnyOrder(
                "id", "user_id", "title", "shared_with_admin", "created_at", "updated_at");
        assertThat(byName.get("id").get("data_type")).isEqualTo("uuid");
        assertThat(byName.get("user_id").get("is_nullable")).isEqualTo("NO");
        assertThat(byName.get("title").get("is_nullable")).isEqualTo("NO");
        assertThat(byName.get("shared_with_admin").get("data_type")).isEqualTo("boolean");
        assertThat(byName.get("shared_with_admin").get("is_nullable")).isEqualTo("NO");
        assertThat(String.valueOf(byName.get("shared_with_admin").get("column_default"))).startsWith("false");
        assertThat(byName.get("created_at").get("data_type")).isEqualTo("timestamp with time zone");
        assertThat(byName.get("updated_at").get("data_type")).isEqualTo("timestamp with time zone");
    }

    @Test
    void chatSessionIdDefaultsToGeneratedUuidAndSharingDefaultsToFalse() {
        UUID user = insertUser("alice");
        UUID session = jdbc.queryForObject(
                "INSERT INTO chat_sessions (user_id, title) VALUES (?, 'hello') RETURNING id", UUID.class, user);
        Boolean shared = jdbc.queryForObject(
                "SELECT shared_with_admin FROM chat_sessions WHERE id = ?", Boolean.class, session);
        assertThat(session).isNotNull();
        assertThat(shared).isFalse();
    }

    @Test
    void chatSessionsHasIndexOnUserIdAndUpdatedAt() {
        List<String> indexes = jdbc.queryForList("""
                SELECT indexdef FROM pg_indexes
                WHERE schemaname = 'public' AND tablename = 'chat_sessions'
                """, String.class);
        assertThat(indexes).anyMatch(def -> def.contains("(user_id, updated_at)"));
    }

    @Test
    void deletingUserCascadesToChatSessionsAndMessages() {
        UUID user = insertUser("alice");
        UUID session = insertChatSession(user);
        insertChatMessage(session, 1, "USER", null);
        jdbc.update("DELETE FROM users WHERE id = ?", user);
        assertThat(count("chat_sessions")).isZero();
        assertThat(count("chat_messages")).isZero();
    }

    @Test
    void deletingChatSessionCascadesToMessages() {
        UUID user = insertUser("alice");
        UUID session = insertChatSession(user);
        insertChatMessage(session, 1, "USER", null);
        insertChatMessage(session, 2, "ASSISTANT", null);
        jdbc.update("DELETE FROM chat_sessions WHERE id = ?", session);
        assertThat(count("chat_messages")).isZero();
    }

    @Test
    void chatMessagesHasExpectedColumnsAndTypes() {
        List<Map<String, Object>> columns = jdbc.queryForList("""
                SELECT column_name, data_type, is_nullable FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'chat_messages'
                """);
        Map<String, Map<String, Object>> byName = columns.stream()
                .collect(java.util.stream.Collectors.toMap(c -> (String) c.get("column_name"), c -> c));
        assertThat(byName.keySet()).containsExactlyInAnyOrder(
                "id", "session_id", "seq", "role", "content", "tool_calls", "tool_call_id", "status", "created_at");
        assertThat(byName.get("seq").get("data_type")).isEqualTo("integer");
        assertThat(byName.get("seq").get("is_nullable")).isEqualTo("NO");
        assertThat(byName.get("content").get("data_type")).isEqualTo("text");
        assertThat(byName.get("content").get("is_nullable")).isEqualTo("YES");
        assertThat(byName.get("tool_calls").get("data_type")).isEqualTo("jsonb");
        assertThat(byName.get("tool_calls").get("is_nullable")).isEqualTo("YES");
        assertThat(byName.get("tool_call_id").get("is_nullable")).isEqualTo("YES");
        assertThat(byName.get("status").get("is_nullable")).isEqualTo("YES");
        assertThat(byName.get("created_at").get("data_type")).isEqualTo("timestamp with time zone");
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "ASSISTANT", "TOOL"})
    void chatMessageAcceptsKnownRoles(String role) {
        UUID session = insertChatSession(insertUser("alice"));
        insertChatMessage(session, 1, role, null);
        assertThat(count("chat_messages")).isEqualTo(1);
    }

    @Test
    void chatMessageRejectsUnknownRole() {
        UUID session = insertChatSession(insertUser("alice"));
        assertThatThrownBy(() -> insertChatMessage(session, 1, "SYSTEM", null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chat_messages_role_check");
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "CONFIRMED", "REJECTED"})
    void chatMessageAcceptsKnownPendingActionStatuses(String status) {
        UUID session = insertChatSession(insertUser("alice"));
        insertChatMessage(session, 1, "ASSISTANT", status);
        assertThat(count("chat_messages")).isEqualTo(1);
    }

    @Test
    void chatMessageStatusNullIsAccepted() {
        UUID session = insertChatSession(insertUser("alice"));
        insertChatMessage(session, 1, "USER", null);
        Object status = jdbc.queryForObject("SELECT status FROM chat_messages", Object.class);
        assertThat(status).isNull();
    }

    @Test
    void chatMessageRejectsUnknownStatus() {
        UUID session = insertChatSession(insertUser("alice"));
        assertThatThrownBy(() -> insertChatMessage(session, 1, "ASSISTANT", "DONE"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chat_messages_status_check");
    }

    @Test
    void chatMessageToolCallsStoresJson() {
        UUID session = insertChatSession(insertUser("alice"));
        jdbc.update("""
                INSERT INTO chat_messages (session_id, seq, role, tool_calls)
                VALUES (?, 1, 'ASSISTANT', '[{"id":"c1","name":"list_hosts"}]'::jsonb)
                """, session);
        String name = jdbc.queryForObject(
                "SELECT tool_calls->0->>'name' FROM chat_messages", String.class);
        assertThat(name).isEqualTo("list_hosts");
    }

    @Test
    void duplicateSeqWithinSessionIsRejected() {
        UUID session = insertChatSession(insertUser("alice"));
        insertChatMessage(session, 1, "USER", null);
        assertThatThrownBy(() -> insertChatMessage(session, 1, "ASSISTANT", null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chat_messages_session_seq_key");
    }

    @Test
    void sameSeqInDifferentSessionsIsAccepted() {
        UUID user = insertUser("alice");
        insertChatMessage(insertChatSession(user), 1, "USER", null);
        insertChatMessage(insertChatSession(user), 1, "USER", null);
        assertThat(count("chat_messages")).isEqualTo(2);
    }

    @Test
    void chatMessageWithUnknownSessionIsRejected() {
        assertThatThrownBy(() -> insertChatMessage(UUID.randomUUID(), 1, "USER", null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void registryCredentialsHasExpectedColumnsAndTypes() {
        List<Map<String, Object>> columns = jdbc.queryForList("""
                SELECT column_name, data_type, is_nullable FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'registry_credentials'
                """);
        Map<String, Map<String, Object>> byName = columns.stream()
                .collect(java.util.stream.Collectors.toMap(c -> (String) c.get("column_name"), c -> c));
        assertThat(byName.keySet()).containsExactlyInAnyOrder(
                "id", "name", "registry_host", "username", "secret_ciphertext", "secret_hint",
                "created_by", "created_at", "updated_at");

        assertThat(byName.get("id").get("data_type")).isEqualTo("uuid");
        assertThat(byName.get("name").get("data_type")).isEqualTo("character varying");
        assertThat(byName.get("name").get("is_nullable")).isEqualTo("NO");
        assertThat(byName.get("registry_host").get("data_type")).isEqualTo("character varying");
        assertThat(byName.get("registry_host").get("is_nullable")).isEqualTo("NO");
        assertThat(byName.get("username").get("data_type")).isEqualTo("character varying");
        assertThat(byName.get("username").get("is_nullable")).isEqualTo("NO");
        assertThat(byName.get("secret_ciphertext").get("data_type")).isEqualTo("bytea");
        assertThat(byName.get("secret_ciphertext").get("is_nullable")).isEqualTo("NO");
        assertThat(byName.get("secret_hint").get("data_type")).isEqualTo("character varying");
        assertThat(byName.get("secret_hint").get("is_nullable")).isEqualTo("NO");
        assertThat(byName.get("created_by").get("data_type")).isEqualTo("uuid");
        assertThat(byName.get("created_by").get("is_nullable")).isEqualTo("NO");
        assertThat(byName.get("created_at").get("data_type")).isEqualTo("timestamp with time zone");
        assertThat(byName.get("created_at").get("is_nullable")).isEqualTo("NO");
        assertThat(byName.get("updated_at").get("data_type")).isEqualTo("timestamp with time zone");
        assertThat(byName.get("updated_at").get("is_nullable")).isEqualTo("NO");
    }

    @Test
    void registryCredentialsHasNoPlaintextSecretColumn() {
        List<String> columnNames = jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'registry_credentials'
                """, String.class);
        assertThat(columnNames).filteredOn(c -> c.contains("password") || c.contains("secret") || c.contains("token"))
                .containsExactlyInAnyOrder("secret_ciphertext", "secret_hint");
    }

    @Test
    void registryCredentialIdAndTimestampsHaveDefaults() {
        UUID user = insertUser("alice");
        insertRegistryCredential("ghcr", "ghcr.io", user);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT id, created_at, updated_at FROM registry_credentials WHERE name = 'ghcr'");
        assertThat(row.get("id")).isNotNull();
        assertThat(row.get("created_at")).isNotNull();
        assertThat(row.get("updated_at")).isNotNull();
    }

    @Test
    void duplicateRegistryCredentialNameIsRejected() {
        UUID user = insertUser("alice");
        insertRegistryCredential("ghcr", "ghcr.io", user);
        assertThatThrownBy(() -> insertRegistryCredential("ghcr", "other.example.com", user))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("registry_credentials_name_key");
    }

    @Test
    void duplicateRegistryHostIsRejected() {
        UUID user = insertUser("alice");
        insertRegistryCredential("ghcr", "ghcr.io", user);
        assertThatThrownBy(() -> insertRegistryCredential("ghcr-2", "ghcr.io", user))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("registry_credentials_registry_host_key");
    }

    @Test
    void registryCredentialWithoutSecretCiphertextIsRejected() {
        UUID user = insertUser("alice");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO registry_credentials (name, registry_host, username, secret_ciphertext, secret_hint, created_by)
                VALUES ('ghcr', 'ghcr.io', 'octocat', NULL, '...abcd', ?)
                """, user))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingUserWhoCreatedRegistryCredentialFails() {
        UUID creator = insertUser("creator");
        insertRegistryCredential("ghcr", "ghcr.io", creator);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM users WHERE id = ?", creator))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void gitCredentialsHasNoPlaintextTokenColumn() {
        List<Map<String, Object>> cols = jdbc.queryForList("""
                SELECT column_name, data_type, is_nullable FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'git_credentials'
                """);
        assertThat(cols).extracting(c -> c.get("column_name")).containsExactlyInAnyOrder(
                "id", "name", "username", "token_ciphertext", "token_hint", "created_by", "created_at", "updated_at");
        assertThat(cols).filteredOn(c -> "token_ciphertext".equals(c.get("column_name")))
                .singleElement().satisfies(c -> {
                    assertThat(c.get("data_type")).isEqualTo("bytea");
                    assertThat(c.get("is_nullable")).isEqualTo("NO");
                });
    }

    @Test
    void duplicateGitCredentialNameIsRejected() {
        UUID user = insertUser("alice");
        insertCredential("gh", user);
        assertThatThrownBy(() -> insertCredential("gh", user))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("git_credentials_name_key");
    }

    @Test
    void deletingUserWhoCreatedGitCredentialFails() {
        UUID user = insertUser("alice");
        insertCredential("gh", user);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM users WHERE id = ?", user))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void existingDeploymentDefaultsToFileSourceWithoutAutoUpdate() {
        UUID user = insertUser("alice");
        UUID deployment = insertDeployment(insertHost(user), user, "web");
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT source_type, auto_update_enabled, git_repo_url, current_version_id FROM deployments WHERE id = ?",
                deployment);
        assertThat(row.get("source_type")).isEqualTo("FILE");
        assertThat(row.get("auto_update_enabled")).isEqualTo(false);
        assertThat(row.get("git_repo_url")).isNull();
        assertThat(row.get("current_version_id")).isNull();
    }

    @Test
    void gitDeploymentWithAllGitColumnsIsAccepted() {
        UUID user = insertUser("alice");
        UUID cred = insertCredential("gh", user);
        UUID deployment = insertGitDeployment(insertHost(user), user, "git-web", cred, 60);
        assertThat(jdbc.queryForObject("SELECT source_type FROM deployments WHERE id = ?", String.class, deployment))
                .isEqualTo("GIT");
    }

    @Test
    void gitDeploymentStoresPollState() {
        UUID user = insertUser("alice");
        UUID deployment = insertGitDeployment(insertHost(user), user, "git-poll", null, null);
        jdbc.update("UPDATE deployments SET last_polled_at = now(), last_poll_error = 'GIT_UNREACHABLE' WHERE id = ?", deployment);
        assertThat(jdbc.queryForObject("SELECT last_poll_error FROM deployments WHERE id = ?", String.class, deployment))
                .isEqualTo("GIT_UNREACHABLE");
        assertThat(jdbc.queryForObject("SELECT last_polled_at IS NOT NULL FROM deployments WHERE id = ?", Boolean.class, deployment))
                .isTrue();
    }

    @Test
    void gitDeploymentWithoutCredentialIsAccepted() {
        UUID user = insertUser("alice");
        UUID deployment = insertGitDeployment(insertHost(user), user, "git-pub", null, null);
        assertThat(deployment).isNotNull();
    }

    @Test
    void invalidSourceTypeIsRejected() {
        UUID user = insertUser("alice");
        UUID host = insertHost(user);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO deployments (name, host_id, compose_file_path, created_by, source_type)
                VALUES ('x', ?, '/x', ?, 'SVN')
                """, host, user))
                .isInstanceOf(DataIntegrityViolationException.class); // also trips the git-columns check
    }

    @Test
    void gitDeploymentWithoutRepoUrlIsRejected() {
        UUID user = insertUser("alice");
        UUID host = insertHost(user);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO deployments (name, host_id, compose_file_path, created_by, source_type, git_ref, git_compose_path)
                VALUES ('x', ?, '/x', ?, 'GIT', 'main', 'compose.yml')
                """, host, user))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("deployments_git_columns_check");
    }

    @Test
    void fileDeploymentWithGitColumnsIsRejected() {
        UUID user = insertUser("alice");
        UUID host = insertHost(user);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO deployments (name, host_id, compose_file_path, created_by, git_repo_url)
                VALUES ('x', ?, '/x', ?, 'https://example.com/r.git')
                """, host, user))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("deployments_git_columns_check");
    }

    @Test
    void fileDeploymentWithAutoUpdateEnabledIsRejected() {
        UUID user = insertUser("alice");
        UUID host = insertHost(user);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO deployments (name, host_id, compose_file_path, created_by, auto_update_enabled)
                VALUES ('x', ?, '/x', ?, TRUE)
                """, host, user))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("deployments_git_columns_check");
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 59})
    void autoUpdateIntervalBelow60IsRejected(int seconds) {
        UUID user = insertUser("alice");
        UUID host = insertHost(user);
        assertThatThrownBy(() -> insertGitDeployment(host, user, "g", null, seconds))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("deployments_auto_update_interval_check");
    }

    @Test
    void deletingReferencedGitCredentialIsRestricted() {
        UUID user = insertUser("alice");
        UUID cred = insertCredential("gh", user);
        insertGitDeployment(insertHost(user), user, "g", cred, 60);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM git_credentials WHERE id = ?", cred))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingCurrentVersionSetsDeploymentCurrentVersionNull() {
        UUID user = insertUser("alice");
        UUID deployment = insertDeployment(insertHost(user), user, "web");
        UUID version = jdbc.queryForObject("""
                INSERT INTO deployment_versions (deployment_id, yaml_content, created_by)
                VALUES (?, 'services: {}', ?) RETURNING id
                """, UUID.class, deployment, user);
        jdbc.update("UPDATE deployments SET current_version_id = ? WHERE id = ?", version, deployment);
        jdbc.update("DELETE FROM deployment_versions WHERE id = ?", version);
        assertThat(jdbc.queryForObject("SELECT current_version_id FROM deployments WHERE id = ?", UUID.class, deployment))
                .isNull();
    }

    @Test
    void deploymentVersionDefaultsToManualSourceWithNullEnvAndCommit() {
        UUID user = insertUser("alice");
        UUID deployment = insertDeployment(insertHost(user), user, "web");
        insertVersion(deployment, user);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT source, env_ciphertext, commit_sha FROM deployment_versions WHERE deployment_id = ?", deployment);
        assertThat(row.get("source")).isEqualTo("MANUAL");
        assertThat(row.get("env_ciphertext")).isNull();
        assertThat(row.get("commit_sha")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"MANUAL", "GIT", "ROLLBACK"})
    void deploymentVersionAcceptsKnownSourcesWithEncryptedEnv(String source) {
        UUID user = insertUser("alice");
        UUID deployment = insertDeployment(insertHost(user), user, "web");
        jdbc.update("""
                INSERT INTO deployment_versions (deployment_id, yaml_content, created_by, env_ciphertext, commit_sha, source)
                VALUES (?, 'services: {}', ?, ?, 'abc123', ?)
                """, deployment, user, new byte[] {1, 2}, source);
        assertThat(count("deployment_versions")).isEqualTo(1);
    }

    @Test
    void deploymentVersionWithUnknownSourceIsRejected() {
        UUID user = insertUser("alice");
        UUID deployment = insertDeployment(insertHost(user), user, "web");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO deployment_versions (deployment_id, yaml_content, created_by, source)
                VALUES (?, 'services: {}', ?, 'IMPORT')
                """, deployment, user))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("deployment_versions_source_check");
    }

    @Test
    void permissionOnHostIsAccepted() {
        UUID user = insertUser("alice");
        insertPermission(user, insertHost(user), null, "VIEW");
        assertThat(count("permissions")).isEqualTo(1);
    }

    @Test
    void permissionOnDeploymentIsAccepted() {
        UUID user = insertUser("alice");
        UUID host = insertHost(user);
        insertPermission(user, null, insertDeployment(host, user, "web"), "OPERATE");
        assertThat(count("permissions")).isEqualTo(1);
    }

    @Test
    void sameUserMayHoldHostAndDeploymentPermissions() {
        UUID user = insertUser("alice");
        UUID host = insertHost(user);
        insertPermission(user, host, null, "VIEW");
        insertPermission(user, null, insertDeployment(host, user, "web"), "OPERATE");
        assertThat(count("permissions")).isEqualTo(2);
    }

    @Test
    void permissionLevelOutsideViewAndOperateIsRejected() {
        UUID user = insertUser("alice");
        UUID host = insertHost(user);
        assertThatThrownBy(() -> insertPermission(user, host, null, "ADMIN"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void permissionWithoutHostOrDeploymentIsRejected() {
        UUID user = insertUser("alice");
        assertThatThrownBy(() -> insertPermission(user, null, null, "VIEW"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void permissionWithHostAndDeploymentIsRejected() {
        UUID user = insertUser("alice");
        UUID host = insertHost(user);
        assertThatThrownBy(() -> insertPermission(user, host, insertDeployment(host, user, "web"), "VIEW"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void duplicateHostPermissionForSameUserIsRejected() {
        UUID user = insertUser("alice");
        UUID host = insertHost(user);
        insertPermission(user, host, null, "VIEW");
        assertThatThrownBy(() -> insertPermission(user, host, null, "OPERATE"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void duplicateDeploymentPermissionForSameUserIsRejected() {
        UUID owner = insertUser("owner");
        UUID host = insertHost(owner);
        UUID deployment = insertDeployment(host, owner, "web");
        UUID user = insertUser("alice");
        insertPermission(user, null, deployment, "VIEW");
        assertThatThrownBy(() -> insertPermission(user, null, deployment, "OPERATE"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingUserCascadesTheirPermissions() {
        UUID owner = insertUser("owner");
        UUID host = insertHost(owner);
        UUID user = insertUser("alice");
        insertPermission(user, host, null, "VIEW");
        jdbc.update("DELETE FROM users WHERE id = ?", user);
        assertThat(count("permissions")).isZero();
    }

    @Test
    void deletingHostCascadesHostPermissions() {
        UUID owner = insertUser("owner");
        UUID host = insertHost(owner);
        UUID user = insertUser("alice");
        insertPermission(user, host, null, "VIEW");
        jdbc.update("DELETE FROM docker_hosts WHERE id = ?", host);
        assertThat(count("permissions")).isZero();
    }

    @Test
    void deletingDeploymentCascadesDeploymentPermissions() {
        UUID owner = insertUser("owner");
        UUID host = insertHost(owner);
        UUID deployment = insertDeployment(host, owner, "web");
        UUID user = insertUser("alice");
        insertPermission(user, null, deployment, "OPERATE");
        jdbc.update("DELETE FROM deployments WHERE id = ?", deployment);
        assertThat(count("permissions")).isZero();
    }

    private UUID insertChatSession(UUID user) {
        return jdbc.queryForObject(
                "INSERT INTO chat_sessions (user_id, title) VALUES (?, 'chat') RETURNING id", UUID.class, user);
    }

    private void insertChatMessage(UUID session, int seq, String role, String status) {
        jdbc.update("INSERT INTO chat_messages (session_id, seq, role, content, status) VALUES (?, ?, ?, 'text', ?)",
                session, seq, role, status);
    }

    private void insertRegistryCredential(String name, String registryHost, UUID creator) {
        jdbc.update("""
                INSERT INTO registry_credentials (name, registry_host, username, secret_ciphertext, secret_hint, created_by)
                VALUES (?, ?, 'octocat', ?, '...abcd', ?)
                """, name, registryHost, new byte[] {1, 2, 3}, creator);
    }

    private UUID insertCredential(String name, UUID creator) {
        return jdbc.queryForObject("""
                INSERT INTO git_credentials (name, username, token_ciphertext, token_hint, created_by)
                VALUES (?, 'octocat', ?, '...abcd', ?) RETURNING id
                """, UUID.class, name, new byte[] {1, 2, 3}, creator);
    }

    private UUID insertGitDeployment(UUID host, UUID creator, String name, UUID credential, Integer intervalSeconds) {
        return jdbc.queryForObject("""
                INSERT INTO deployments (name, host_id, compose_file_path, created_by, source_type, git_repo_url, git_ref,
                                    git_compose_path, git_credential_id, auto_update_enabled,
                                    auto_update_interval_seconds)
                VALUES (?, ?, '/deployments/x/compose.yml', ?, 'GIT', 'https://example.com/r.git', 'main', 'compose.yml',
                        ?, ?, ?) RETURNING id
                """, UUID.class, name, host, creator, credential, intervalSeconds != null, intervalSeconds);
    }

    private void insertWebhook(String name, UUID creator) {
        jdbc.update("""
                INSERT INTO notification_webhooks (name, url_encrypted, url_hint, event_types, created_by)
                VALUES (?, ?, 'https://example.com', ARRAY['HOST_DOWN'], ?)
                """, name, new byte[] {1, 2, 3}, creator);
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
        return insertHost("host-" + UUID.randomUUID(), "TCP", "docker.local", 2375, owner);
    }

    private UUID insertHost(String name, String connectionType, String host, Integer port, UUID owner) {
        return jdbc.queryForObject("""
                INSERT INTO docker_hosts (name, connection_type, host, port, owner_user_id)
                VALUES (?, ?, ?, ?, ?) RETURNING id
                """, UUID.class, name, connectionType, host, port, owner);
    }

    private UUID insertDeployment(UUID host, UUID creator, String name) {
        return jdbc.queryForObject("""
                INSERT INTO deployments (name, host_id, compose_file_path, created_by)
                VALUES (?, ?, '/deployments/x/compose.yml', ?) RETURNING id
                """, UUID.class, name, host, creator);
    }

    private void insertNotification(UUID user, String type) {
        jdbc.update("INSERT INTO notifications (user_id, type, host_name, message) VALUES (?, ?, 'prod', 'msg')",
                user, type);
    }

    private void insertPreference(UUID user, String type, boolean inAppEnabled) {
        jdbc.update("INSERT INTO notification_preferences (user_id, type, in_app_enabled) VALUES (?, ?, ?)",
                user, type, inAppEnabled);
    }

    private void insertVersion(UUID deployment, UUID creator) {
        jdbc.update("INSERT INTO deployment_versions (deployment_id, yaml_content, created_by) VALUES (?, 'services: {}', ?)",
                deployment, creator);
    }

    private void insertPermission(UUID user, UUID host, UUID deployment, String level) {
        jdbc.update("INSERT INTO permissions (user_id, host_id, deployment_id, level) VALUES (?, ?, ?, ?)",
                user, host, deployment, level);
    }
}
