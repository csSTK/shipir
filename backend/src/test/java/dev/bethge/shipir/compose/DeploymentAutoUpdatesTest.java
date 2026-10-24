package dev.bethge.shipir.compose;

import dev.bethge.shipir.git.AutoUpdateDeployments;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The compose side of the polling port against the real database. */
class DeploymentAutoUpdatesTest extends DeploymentTestBase {

    @Autowired
    AutoUpdateDeployments port;
    @Autowired
    DeploymentLocks locks;

    private UUID deployment;

    @BeforeEach
    void insert() {
        jdbc.update("INSERT INTO deployments (name, host_id, compose_file_path, created_by, source_type, git_repo_url, "
                + "git_ref, git_compose_path, auto_update_enabled, auto_update_interval_seconds) "
                + "SELECT 'g', ?, 'x', u.id, 'GIT', 'https://example.com/r.git', 'main', 'compose.yaml', TRUE, 120 "
                + "FROM users u WHERE u.username = 'admin'", hostId);
        jdbc.update("INSERT INTO deployments (name, host_id, compose_file_path, created_by) "
                + "SELECT 'off', ?, 'x', u.id FROM users u WHERE u.username = 'admin'", hostId);
        deployment = jdbc.queryForObject("SELECT id FROM deployments WHERE name = 'g'", UUID.class);
    }

    @Test
    void listsOnlyEnabledGitDeployments() {
        assertThat(port.findEnabled()).singleElement().satisfies(s -> {
            assertThat(s.id()).isEqualTo(deployment);
            assertThat(s.intervalSeconds()).isEqualTo(120);
        });
    }

    @Test
    void sameErrorIsAuditedOnceAsSystemAndClearedOnSuccess() {
        Instant t = Instant.parse("2026-01-01T10:00:00Z");
        port.recordPoll(deployment, t, "GIT_UNREACHABLE");
        port.recordPoll(deployment, t.plusSeconds(120), "GIT_UNREACHABLE");

        assertThat(jdbc.queryForList("SELECT username || ':' || outcome FROM audit_log WHERE action = 'DEPLOYMENT_AUTO_UPDATE'",
                String.class)).containsExactly("system:FAILURE");
        assertThat(jdbc.queryForObject("SELECT last_poll_error FROM deployments WHERE id = ?", String.class, deployment))
                .isEqualTo("GIT_UNREACHABLE");

        port.recordPoll(deployment, t.plusSeconds(240), null);
        assertThat(jdbc.queryForObject("SELECT last_poll_error FROM deployments WHERE id = ?", String.class, deployment)).isNull();
        assertThat(port.findEnabled().getFirst().lastPolledAt()).isEqualTo(t.plusSeconds(240));
    }

    @Test
    void runningDeploymentMakesRedeployBusy() {
        locks.acquire(deployment);
        try {
            assertThat(port.redeploy(deployment)).isEqualTo(AutoUpdateDeployments.RedeployOutcome.BUSY);
        } finally {
            locks.release(deployment);
        }
    }

    @Test
    void failedCheckNotifiesOncePerDistinctError() {
        jdbc.update("DELETE FROM notifications");
        Instant t = Instant.parse("2026-01-01T10:00:00Z");
        port.recordPoll(deployment, t, "GIT_UNREACHABLE");
        port.recordPoll(deployment, t.plusSeconds(120), "GIT_UNREACHABLE");
        assertThat(count("admin")).isEqualTo(1);

        port.recordPoll(deployment, t.plusSeconds(240), "GIT_AUTH_FAILED");
        assertThat(count("admin")).isEqualTo(2);

        port.recordPoll(deployment, t.plusSeconds(360), null);
        port.recordPoll(deployment, t.plusSeconds(480), "GIT_AUTH_FAILED");
        assertThat(count("admin")).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT deployment_name FROM notifications LIMIT 1", String.class)).isEqualTo("g");
    }

    private long count(String username) {
        return jdbc.queryForObject("SELECT count(*) FROM notifications n JOIN users u ON u.id = n.user_id "
                + "WHERE u.username = ? AND n.type = 'GIT_AUTO_UPDATE_FAILED'", Long.class, username);
    }
}
