package dev.bethge.shipir.compose;

import dev.bethge.shipir.git.AutoUpdateStacks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The compose side of the polling port against the real database. */
class StackAutoUpdatesTest extends StackTestBase {

    @Autowired
    AutoUpdateStacks port;
    @Autowired
    DeploymentLocks locks;

    private UUID stack;

    @BeforeEach
    void insert() {
        jdbc.update("INSERT INTO stacks (name, host_id, compose_file_path, created_by, source_type, git_repo_url, "
                + "git_ref, git_compose_path, auto_update_enabled, auto_update_interval_seconds) "
                + "SELECT 'g', ?, 'x', u.id, 'GIT', 'https://example.com/r.git', 'main', 'compose.yaml', TRUE, 120 "
                + "FROM users u WHERE u.username = 'admin'", hostId);
        jdbc.update("INSERT INTO stacks (name, host_id, compose_file_path, created_by) "
                + "SELECT 'off', ?, 'x', u.id FROM users u WHERE u.username = 'admin'", hostId);
        stack = jdbc.queryForObject("SELECT id FROM stacks WHERE name = 'g'", UUID.class);
    }

    @Test
    void listsOnlyEnabledGitStacks() {
        assertThat(port.findEnabled()).singleElement().satisfies(s -> {
            assertThat(s.id()).isEqualTo(stack);
            assertThat(s.intervalSeconds()).isEqualTo(120);
        });
    }

    @Test
    void sameErrorIsAuditedOnceAsSystemAndClearedOnSuccess() {
        Instant t = Instant.parse("2026-01-01T10:00:00Z");
        port.recordPoll(stack, t, "GIT_UNREACHABLE");
        port.recordPoll(stack, t.plusSeconds(120), "GIT_UNREACHABLE");

        assertThat(jdbc.queryForList("SELECT username || ':' || outcome FROM audit_log WHERE action = 'STACK_AUTO_UPDATE'",
                String.class)).containsExactly("system:FAILURE");
        assertThat(jdbc.queryForObject("SELECT last_poll_error FROM stacks WHERE id = ?", String.class, stack))
                .isEqualTo("GIT_UNREACHABLE");

        port.recordPoll(stack, t.plusSeconds(240), null);
        assertThat(jdbc.queryForObject("SELECT last_poll_error FROM stacks WHERE id = ?", String.class, stack)).isNull();
        assertThat(port.findEnabled().getFirst().lastPolledAt()).isEqualTo(t.plusSeconds(240));
    }

    @Test
    void runningDeploymentMakesRedeployBusy() {
        locks.acquire(stack);
        try {
            assertThat(port.redeploy(stack)).isEqualTo(AutoUpdateStacks.RedeployOutcome.BUSY);
        } finally {
            locks.release(stack);
        }
    }
}
