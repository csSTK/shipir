package dev.bethge.shipir.compose;

import dev.bethge.shipir.PostgresTestConfiguration;
import dev.bethge.shipir.compose.dto.DeploymentResponse;
import dev.bethge.shipir.compose.dto.StackCreateRequest;
import dev.bethge.shipir.compose.dto.StackDetailResponse;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
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
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(PostgresTestConfiguration.class)
class StackRealDeployTest {

    static final Path BASE_DIR = baseDir();

    private static Path baseDir() {
        try {
            return Files.createTempDirectory("shipir-stacks-real-");
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
    StackService stacks;
    @Autowired
    StackDeploymentService deployments;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void deploysAndStopsATinyStackOnTheLocalSocket() throws Exception {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "No Docker daemon available");
        Process p = new ProcessBuilder("docker", "compose", "version").redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        Assumptions.assumeTrue(p.waitFor() == 0, "docker compose CLI not available");

        jdbc.update("INSERT INTO docker_hosts (name, connection_type, owner_user_id) "
                + "SELECT 'Real host', 'LOCAL', id FROM users WHERE username = 'admin'");
        UUID hostId = jdbc.queryForObject("SELECT id FROM docker_hosts", UUID.class);
        String name = "shipir-it-" + UUID.randomUUID().toString().substring(0, 8);
        StackDetailResponse stack = stacks.create(hostId, new StackCreateRequest(name,
                "services:\n  sleeper:\n    image: busybox:1.36\n    command: sleep 300\n", null), "admin");
        try {
            DeploymentResponse deployment = deployments.deploy(hostId, stack.id(), "admin");
            assertThat(deployment.deploymentId()).matches("[A-Za-z0-9-]{1,64}");
            await().atMost(3, TimeUnit.MINUTES).pollInterval(500, TimeUnit.MILLISECONDS).until(() ->
                    !"STOPPED".equals(jdbc.queryForObject("SELECT status FROM stacks WHERE id = ?", String.class, stack.id())));
            assertThat(jdbc.queryForObject("SELECT status FROM stacks WHERE id = ?", String.class, stack.id()))
                    .isEqualTo("RUNNING");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM stack_versions WHERE stack_id = ?", Integer.class,
                    stack.id())).isEqualTo(2);
            assertThat(jdbc.queryForList("SELECT outcome FROM audit_log WHERE action = 'STACK_DEPLOY_RESULT'",
                    String.class)).containsExactly("SUCCESS");
        } finally {
            await().atMost(1, TimeUnit.MINUTES).ignoreExceptions().untilAsserted(() -> deployments.stop(hostId, stack.id()));
        }
    }
}
