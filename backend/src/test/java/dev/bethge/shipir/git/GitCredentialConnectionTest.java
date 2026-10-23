package dev.bethge.shipir.git;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ls-remote through JGit against a local smart-HTTP stub. Plain http is only reachable here because the
 * test profile sets {@code shipir.git.allow-insecure-http}; production accepts https only.
 */
@TestPropertySource(properties = {"shipir.git.allow-insecure-http=true", "shipir.git.connect-timeout=2s"})
class GitCredentialConnectionTest extends GitTestBase {

    private HttpServer server;
    private final List<String> authHeaders = new CopyOnWriteArrayList<>();

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static byte[] pkt(String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        return (String.format("%04x", b.length + 4) + s).getBytes(StandardCharsets.UTF_8);
    }

    /** Serves /private.git (needs Basic octocat:TOKEN) and /public.git; everything else is 404. */
    private String startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            if (auth != null) {
                authHeaders.add(auth);
            }
            boolean known = path.equals("/private.git/info/refs") || path.equals("/public.git/info/refs");
            if (!known) {
                ex.sendResponseHeaders(404, -1);
            } else if (path.startsWith("/private.git") && !("Basic " + Base64.getEncoder()
                    .encodeToString(("octocat:" + TOKEN).getBytes(StandardCharsets.UTF_8))).equals(auth)) {
                ex.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"git\"");
                ex.sendResponseHeaders(401, -1);
            } else {
                var out = new java.io.ByteArrayOutputStream();
                out.writeBytes(pkt("# service=git-upload-pack\n"));
                out.writeBytes("0000".getBytes(StandardCharsets.UTF_8));
                out.writeBytes(pkt("1111111111111111111111111111111111111111 HEAD\0side-band-64k\n"));
                out.writeBytes("0000".getBytes(StandardCharsets.UTF_8));
                ex.getResponseHeaders().add("Content-Type", "application/x-git-upload-pack-advertisement");
                ex.sendResponseHeaders(200, out.size());
                ex.getResponseBody().write(out.toByteArray());
            }
            ex.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private String test(Login l, String credentialId, String repoUrl) throws Exception {
        return mvc.perform(l.write(post("/api/git/credentials/" + credentialId + "/test"))
                        .contentType(APPLICATION_JSON).content("{\"repoUrl\":\"%s\"}".formatted(repoUrl)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void okWithValidCredentialsAndSendsDecryptedToken() throws Exception {
        String base = startStub();
        Login l = login("admin");
        String id = create(l, "GitHub");
        mvc.perform(l.write(post("/api/git/credentials/" + id + "/test")).contentType(APPLICATION_JSON)
                        .content("{\"repoUrl\":\"%s/private.git\"}".formatted(base)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"));
        assertThat(authHeaders).isNotEmpty();
    }

    @Test
    void authFailedWithWrongToken() throws Exception {
        String base = startStub();
        Login l = login("admin");
        mvc.perform(l.write(post("/api/git/credentials")).contentType(APPLICATION_JSON)
                .content(json("Bad", "octocat", "wrong-token-value-0000"))).andExpect(status().isCreated());
        String badId = jdbc.queryForObject("SELECT id::text FROM git_credentials", String.class);
        assertThat(test(l, badId, base + "/private.git")).contains("AUTH_FAILED").doesNotContain("wrong-token");
    }

    @Test
    void notFoundForUnknownRepository() throws Exception {
        String base = startStub();
        Login l = login("admin");
        String id = create(l, "GitHub");
        assertThat(test(l, id, base + "/missing.git")).contains("NOT_FOUND");
    }

    @Test
    void unreachableWhenNothingListens() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        Login l = login("admin");
        String id = create(l, "GitHub");
        assertThat(test(l, id, "http://127.0.0.1:" + port + "/x.git")).contains("UNREACHABLE");
    }

    @Test
    void unknownCredentialIs404() throws Exception {
        Login l = login("admin");
        mvc.perform(l.write(post("/api/git/credentials/" + java.util.UUID.randomUUID() + "/test"))
                        .contentType(APPLICATION_JSON).content("{\"repoUrl\":\"https://example.com/r.git\"}"))
                .andExpect(status().isNotFound());
    }
}
