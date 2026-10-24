package dev.bethge.shipir.demo;

import dev.bethge.shipir.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The 'demo' profile seeds an empty database after all migrations. The demo passwords are public
 * (see README, Demo section) and must verify against the stored Argon2id hashes.
 */
@SpringBootTest
@ActiveProfiles("demo")
@Import(PostgresTestConfiguration.class)
class DemoSeedProfileOnTest {

    private static final Map<String, String> DEMO_PASSWORDS = Map.of(
            "demo-admin", "Demo-Admin-2026!",
            "demo-operator", "Demo-Operator-2026!",
            "demo-viewer", "Demo-Viewer-2026!");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void seedsOneDemoUserPerRoleWithDocumentedPassword() {
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(3);
        DEMO_PASSWORDS.forEach((username, password) -> {
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT password_hash, role, password_changed_at FROM users WHERE username = ?", username);
            assertThat(row.get("role")).isEqualTo(username.replace("demo-", "").toUpperCase());
            assertThat(row.get("password_changed_at")).as("setup gate must not force a change").isNotNull();
            String hash = (String) row.get("password_hash");
            assertThat(hash).startsWith("{argon2}$argon2id$");
            assertThat(passwordEncoder.matches(password, hash)).as(username).isTrue();
        });
    }

    @Test
    void seedsLocalEnvironmentWithOperatorAndViewerGrants() {
        Integer hosts = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM docker_hosts WHERE connection_type = 'LOCAL'", Integer.class);
        assertThat(hosts).isEqualTo(1);
        Integer grants = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM permissions p JOIN users u ON u.id = p.user_id "
                        + "WHERE u.username IN ('demo-operator', 'demo-viewer') AND p.host_id IS NOT NULL", Integer.class);
        assertThat(grants).isEqualTo(2);
    }

    @Test
    void seedsSampleDeploymentWithUndeployedDraft() {
        Map<String, Object> deployment = jdbcTemplate.queryForMap(
                "SELECT id, host_id, compose_file_path, current_version_id FROM deployments WHERE name = 'demo-web'");
        assertThat(deployment.get("compose_file_path").toString())
                .isEqualTo(deployment.get("host_id") + "/demo-web/compose.yaml");
        Map<String, Object> draft = jdbcTemplate.queryForMap(
                "SELECT yaml_content, deployed_at FROM deployment_versions WHERE id = ?::uuid",
                deployment.get("current_version_id").toString());
        assertThat(draft.get("deployed_at")).as("draft stays editable and deployable").isNull();
        assertThat(draft.get("yaml_content").toString()).contains("nginx");
    }

    @Test
    void seedsNotificationsAndAuditEntries() {
        Integer notifications = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notifications WHERE id::text LIKE '0de00000-%'", Integer.class);
        Integer audit = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_log WHERE id::text LIKE '0de00000-%'", Integer.class);
        assertThat(notifications).isPositive();
        assertThat(audit).isPositive();
    }
}
