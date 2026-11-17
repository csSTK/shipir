package dev.bethge.shipir.compose;

import dev.bethge.shipir.PostgresTestConfiguration;
import dev.bethge.shipir.compose.dto.DeploymentRunResponse;
import dev.bethge.shipir.compose.dto.DeploymentCreateRequest;
import dev.bethge.shipir.compose.dto.DeploymentDetailResponse;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** One real {@code docker compose up/down} against the local daemon through the deployment service. */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class DeploymentRealDeployTest {

    static final Path BASE_DIR = baseDir();

    private static Path baseDir() {
        try {
            return Files.createTempDirectory("shipir-deployments-real-");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("shipir.stacks.base-dir", BASE_DIR::toString);
        registry.add("shipir.docker.local-socket", () -> "unix://"
                + DockerClientFactory.instance().getRemoteDockerUnixSocketPath());
    }

    @Autowired
    DeploymentService deployments;
    @Autowired
    DeploymentRunService runs;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void deploysAndStopsATinyDeploymentOnTheLocalSocket() throws Exception {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "No Docker daemon available");
        Process p = new ProcessBuilder("docker", "compose", "version").redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        Assumptions.assumeTrue(p.waitFor() == 0, "docker compose CLI not available");

        jdbc.update("INSERT INTO docker_hosts (name, connection_type, owner_user_id) "
                + "SELECT 'Real host', 'LOCAL', id FROM users WHERE username = 'admin'");
        UUID hostId = jdbc.queryForObject("SELECT id FROM docker_hosts", UUID.class);
        String name = "shipir-it-" + UUID.randomUUID().toString().substring(0, 8);
        DeploymentDetailResponse deployment = dev.bethge.shipir.common.security.SystemScope.call(() -> deployments.create(hostId, new DeploymentCreateRequest(name,
                "services:\n  sleeper:\n    image: busybox:1.36\n    command: sleep 300\n", null), "admin"));
        try {
            DeploymentRunResponse run = dev.bethge.shipir.common.security.SystemScope.call(() -> runs.deploy(hostId, deployment.id(), "admin"));
            assertThat(run.runId()).matches("[A-Za-z0-9-]{1,64}");
            await().atMost(3, TimeUnit.MINUTES).pollInterval(500, TimeUnit.MILLISECONDS).until(() ->
                    !"STOPPED".equals(jdbc.queryForObject("SELECT status FROM deployments WHERE id = ?", String.class, deployment.id())));
            assertThat(jdbc.queryForObject("SELECT status FROM deployments WHERE id = ?", String.class, deployment.id()))
                    .isEqualTo("RUNNING");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM deployment_versions WHERE deployment_id = ?", Integer.class,
                    deployment.id())).isEqualTo(2);
            assertThat(jdbc.queryForList("SELECT outcome FROM audit_log WHERE action = 'DEPLOYMENT_DEPLOY_RESULT'",
                    String.class)).containsExactly("SUCCESS");
        } finally {
            await().atMost(1, TimeUnit.MINUTES).ignoreExceptions().untilAsserted(() ->
                    dev.bethge.shipir.common.security.SystemScope.run(() -> runs.stop(hostId, deployment.id())));
        }
    }
}
