package dev.bethge.shipir.git;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A credential is bound to a host. A repository URL on any other host is refused with 400 before a single request
 * (an unauthenticated probe included) leaves the server, so the token can never reach an attacker-chosen host.
 */
@TestPropertySource(properties = {"shipir.git.allow-insecure-http=true", "shipir.git.connect-timeout=2s"})
class GitCredentialHostBindingTest extends GitTestBase {

    @Autowired
    GitRepositoryService git;

    private HttpServer attacker;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private String attackerUrl;

    @BeforeEach
    void startAttacker() throws IOException {
        // "localhost" and 127.0.0.1 are different hosts for the binding, both resolve to this stub
        attacker = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        attacker.createContext("/", ex -> {
            requests.add(ex.getRequestURI() + " auth=" + ex.getRequestHeaders().getFirst("Authorization"));
            ex.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"git\"");
            ex.sendResponseHeaders(401, -1);
            ex.close();
        });
        attacker.start();
        attackerUrl = "http://localhost:" + attacker.getAddress().getPort() + "/x.git";
    }

    @AfterEach
    void stop() {
        attacker.stop(0);
    }

    private String credentialId() throws Exception {
        return create(login("admin"), "GitHub");
    }

    @Test
    void connectionTestWithForeignUrlIs400AndSendsNothing() throws Exception {
        Login l = login("admin");
        String id = credentialId();
        mvc.perform(l.write(post("/api/git/credentials/" + id + "/test")).contentType(APPLICATION_JSON)
                        .content("{\"repoUrl\":\"%s\"}".formatted(attackerUrl)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CREDENTIAL_HOST_MISMATCH"));
        assertThat(requests).isEmpty();
    }

    @Test
    void syncRemoteCommitAndEnsureCommitRefuseForeignUrl() throws Exception {
        UUID id = UUID.fromString(credentialId());
        Path dir = Files.createTempDirectory("shipir-bind-");
        String sha = "1".repeat(40);
        List<Runnable> calls = List.of(
                () -> git.sync(dir, attackerUrl, "main", "compose.yaml", id),
                () -> git.remoteCommit(attackerUrl, "main", id),
                () -> git.ensureCommit(dir, attackerUrl, sha, "compose.yaml", id));
        for (Runnable call : calls) {
            assertThatThrownBy(call::run).isInstanceOf(GitOperationException.class)
                    .satisfies(e -> {
                        assertThat(((GitOperationException) e).code()).isEqualTo("CREDENTIAL_HOST_MISMATCH");
                        assertThat(((GitOperationException) e).status().value()).isEqualTo(400);
                    });
        }
        assertThat(requests).isEmpty();
    }

    @Test
    void operatorCannotAttachTheCredentialToAForeignRepositoryUrl() throws Exception {
        String id = credentialId();
        jdbc.update("INSERT INTO permissions (user_id, host_id, level) SELECT u.id, h.id, 'OPERATE' FROM users u, docker_hosts h "
                + "WHERE u.username = 'operator'");
        Login operator = login("operator");
        String dockerHost = jdbc.queryForObject("SELECT id::text FROM docker_hosts LIMIT 1", String.class);
        mvc.perform(operator.write(post("/api/hosts/" + dockerHost + "/managed-deployments"))
                        .contentType(APPLICATION_JSON)
                        .content(("{\"name\":\"evil\",\"source\":\"GIT\",\"repoUrl\":\"%s\",\"ref\":\"main\","
                                + "\"composePath\":\"compose.yaml\",\"credentialId\":\"%s\"}").formatted(attackerUrl, id)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CREDENTIAL_HOST_MISMATCH"));
        assertThat(requests).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deployments", Integer.class)).isZero();
    }

    @Test
    void sameHostIsStillUsedWithTheToken() throws Exception {
        UUID id = UUID.fromString(credentialId());
        // 127.0.0.1 is the bound host: the request is allowed and carries the credential
        String ownUrl = attackerUrl.replace("localhost", "127.0.0.1");
        assertThatThrownBy(() -> git.remoteCommit(ownUrl, "main", id))
                .isInstanceOf(GitOperationException.class)
                .satisfies(e -> assertThat(((GitOperationException) e).code()).isNotEqualTo("CREDENTIAL_HOST_MISMATCH"));
        assertThat(requests).isNotEmpty();
    }

    @Test
    void credentialWithoutAHostIsRefusedEverywhere() throws Exception {
        UUID id = UUID.fromString(credentialId());
        jdbc.update("UPDATE git_credentials SET host = NULL");
        String ownUrl = attackerUrl.replace("localhost", "127.0.0.1");
        assertThatThrownBy(() -> git.remoteCommit(ownUrl, "main", id)).isInstanceOf(GitOperationException.class)
                .satisfies(e -> assertThat(((GitOperationException) e).code()).isEqualTo("CREDENTIAL_HOST_MISMATCH"));
        assertThat(requests).isEmpty();
    }

    @Test
    void createAndUpdateValidateAndNormalizeTheHost() throws Exception {
        Login l = login("admin");
        mvc.perform(l.write(post("/api/git/credentials")).contentType(APPLICATION_JSON)
                        .content(json("A", "https://github.com", "octocat", TOKEN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIAL_HOST"));
        mvc.perform(l.write(post("/api/git/credentials")).contentType(APPLICATION_JSON)
                        .content("{\"name\":\"A\",\"username\":\"u\",\"token\":\"%s\"}".formatted(TOKEN)))
                .andExpect(status().isBadRequest());
        String body = mvc.perform(l.write(post("/api/git/credentials")).contentType(APPLICATION_JSON)
                        .content(json("B", " GitHub.com. ", "octocat", TOKEN)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.host").value("github.com"))
                .andReturn().getResponse().getContentAsString();
        String id = com.jayway.jsonpath.JsonPath.read(body, "$.id");
        mvc.perform(l.write(put("/api/git/credentials/" + id)).contentType(APPLICATION_JSON)
                        .content(json("B", "git.example.org:8443", "octocat", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.host").value("git.example.org:8443"));
    }
}
