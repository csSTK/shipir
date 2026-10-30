package dev.bethge.shipir.compose;

import com.jayway.jsonpath.JsonPath;
import dev.bethge.shipir.git.AutoUpdateDeployments;
import dev.bethge.shipir.git.GitOperationException;
import dev.bethge.shipir.git.GitCheckout;
import dev.bethge.shipir.git.GitRepositoryService;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.event.EventListener;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The compose side of the polling port against the real database. */
class DeploymentAutoUpdatesTest extends DeploymentTestBase {

    @Autowired
    AutoUpdateDeployments port;
    @Autowired
    DeploymentLocks locks;
    @MockitoBean
    GitRepositoryService git;
    @Autowired
    FailureEvents events;

    /** Every failure announcement (notification, webhook) is derived from one of these two events. */
    @TestConfiguration
    static class EventsConfig {
        @org.springframework.context.annotation.Bean
        FailureEvents failureEvents() {
            return new FailureEvents();
        }
    }

    static class FailureEvents {
        final List<Object> published = new CopyOnWriteArrayList<>();

        @EventListener
        void on(DeploymentRunFailedEvent e) {
            published.add(e);
        }

        @EventListener
        void on(GitAutoUpdateFailedEvent e) {
            published.add(e);
        }
    }

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

    @Test
    void failureBeforeTheRunIsAnnouncedOnceAsGitAutoUpdateFailedOnly() throws Exception {
        UUID id = deployedGitDeployment("prerun");
        events.published.clear();
        when(git.sync(any(), any(), any(), any(), any())).thenThrow(
                new GitOperationException(org.springframework.http.HttpStatus.BAD_GATEWAY, "GIT_UNREACHABLE", "unreachable"));
        // what GitPollingScheduler does: redeploy throws, the scheduler records the code
        GitOperationException failure = catchThrowableOfType(GitOperationException.class, () -> port.redeploy(id));
        assertThat(failure).isNotNull();
        port.recordPoll(id, Instant.parse("2026-01-01T10:00:00Z"), failure.code());

        assertThat(events.published).singleElement().isInstanceOf(GitAutoUpdateFailedEvent.class);
    }

    @Test
    void failureOfTheRunItselfIsAnnouncedOnceAsDeploymentFailedOnly() throws Exception {
        UUID id = deployedGitDeployment("rungit");
        upFails = true;
        jdbc.update("DELETE FROM notifications");
        events.published.clear();
        when(git.sync(any(), any(), any(), any(), any())).thenReturn(new GitCheckout("b".repeat(40), YAML));

        assertThat(port.redeploy(id)).isEqualTo(AutoUpdateDeployments.RedeployOutcome.STARTED);
        port.recordPoll(id, Instant.parse("2026-01-01T10:00:00Z"), null); // the scheduler records a clean poll after STARTED
        await().atMost(10, java.util.concurrent.TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(events.published).singleElement().isInstanceOf(DeploymentRunFailedEvent.class));
        // nothing else follows (no second announcement for the same failure)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE type = 'GIT_AUTO_UPDATE_FAILED'",
                Long.class)).isZero();
    }

    private UUID deployedGitDeployment(String name) throws Exception {
        Login admin = login("admin");
        String res = mvc.perform(admin.write(post(url()).contentType(APPLICATION_JSON).content(createJson(name, YAML, null))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(res, "$.id"));
        jdbc.update("UPDATE deployments SET source_type = 'GIT', git_repo_url = 'https://example.com/r.git', git_ref = 'main', "
                + "git_compose_path = 'compose.yaml', auto_update_enabled = TRUE, auto_update_interval_seconds = 120 WHERE id = ?", id);
        jdbc.update("UPDATE deployment_versions SET commit_sha = ?, deployed_at = now(), deploy_succeeded_at = now() "
                + "WHERE deployment_id = ?", "a".repeat(40), id);
        java.nio.file.Files.createDirectories(files.repoDir(hostId, name));
        java.nio.file.Files.writeString(files.repoDir(hostId, name).resolve("compose.yaml"), YAML);
        return id;
    }
}
