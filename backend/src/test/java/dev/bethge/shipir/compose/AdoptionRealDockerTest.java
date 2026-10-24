package dev.bethge.shipir.compose;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Adopts a real container carrying Compose labels on the local Docker daemon (skipped without Docker); the container must
 * keep running because adoption never touches it. Compose itself stays faked.
 */
class AdoptionRealDockerTest extends DeploymentTestBase {

    private static final String PROJECT = "shipir-adopt-it";

    private String run(String... args) throws Exception {
        Process p = new ProcessBuilder(args).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        return out.trim();
    }

    @BeforeEach
    void startLabelledContainer() throws Exception {
        boolean docker;
        try {
            docker = new ProcessBuilder("docker", "info").redirectErrorStream(true).start().waitFor() == 0;
        } catch (Exception e) {
            docker = false;
        }
        Assumptions.assumeTrue(docker, "Docker not available");
        run("docker", "rm", "-f", PROJECT + "-web-1");
        run("docker", "run", "-d", "--name", PROJECT + "-web-1", "--label", "com.docker.compose.project=" + PROJECT,
                "--label", "com.docker.compose.service=web", "busybox", "sleep", "300");
    }

    @AfterEach
    void removeContainer() throws Exception {
        run("docker", "rm", "-f", PROJECT + "-web-1");
    }

    @Test
    void adoptsRunningProjectWithoutTouchingItsContainer() throws Exception {
        mvc.perform(login("admin").write(post("/api/hosts/" + hostId + "/deployments/" + PROJECT + "/adopt")
                        .contentType("application/json").content("{\"yaml\":\"services:\\n  web:\\n    image: busybox\\n\"}")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value(PROJECT));

        assertThat(run("docker", "inspect", "-f", "{{.State.Running}}", PROJECT + "-web-1")).isEqualTo("true");
    }

    @Test
    void unknownProjectIsNotFoundOnRealDaemon() throws Exception {
        mvc.perform(login("admin").write(post("/api/hosts/" + hostId + "/deployments/no-such-project-xyz/adopt")))
                .andExpect(status().isNotFound());
    }
}
