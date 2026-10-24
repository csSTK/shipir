package dev.bethge.shipir.registry;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The test endpoint end to end against a local Registry v2 stub; http only because of the test property. */
@TestPropertySource(properties = {"shipir.registry.allow-insecure-http=true", "shipir.registry.timeout=2s"})
class RegistryCredentialConnectionTest extends RegistryTestBase {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void testEndpointReportsOkAuthFailedAndUnreachable() throws Exception {
        String expected = "Basic " + Base64.getEncoder().encodeToString(("robot:" + SECRET)
                .getBytes(StandardCharsets.UTF_8));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v2/", ex -> {
            boolean ok = expected.equals(ex.getRequestHeaders().getFirst("Authorization"));
            if (!ok) {
                ex.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"r\"");
            }
            ex.sendResponseHeaders(ok ? 200 : 401, -1);
            ex.close();
        });
        server.start();
        String host = "127.0.0.1:" + server.getAddress().getPort();

        Login l = login("admin");
        String id = create(l, "Stub", host);
        mvc.perform(l.write(post("/api/registries/" + id + "/test")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OK"));

        mvc.perform(l.write(put("/api/registries/" + id)).contentType(APPLICATION_JSON)
                        .content(json("Stub", host, "robot", "wrong-secret-value-0000")))
                .andExpect(status().isOk());
        mvc.perform(l.write(post("/api/registries/" + id + "/test")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("AUTH_FAILED"));

        server.stop(0);
        mvc.perform(l.write(post("/api/registries/" + id + "/test")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UNREACHABLE"));
    }
}
