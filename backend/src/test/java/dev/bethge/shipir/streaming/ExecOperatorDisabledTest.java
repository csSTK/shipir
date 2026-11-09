package dev.bethge.shipir.streaming;

import dev.bethge.shipir.PostgresTestConfiguration;
import dev.bethge.shipir.auth.Role;
import dev.bethge.shipir.auth.User;
import dev.bethge.shipir.auth.UserRepository;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.client.ExecService;
import dev.bethge.shipir.host.DockerHost;
import dev.bethge.shipir.host.DockerHostRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Default configuration ({@code operator-enabled} unset = false): consoles stay ADMIN only, even with OPERATE grants. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
class ExecOperatorDisabledTest {

    static final String PASSWORD = "A-test-Passw0rd-for-exec!";

    @LocalServerPort
    int port;
    @Autowired
    UserRepository users;
    @Autowired
    DockerHostRepository hosts;
    @Autowired
    PasswordEncoder encoder;
    @Autowired
    JdbcTemplate jdbc;
    @MockitoBean
    ExecService execService;

    private final HttpClient http = HttpClient.newHttpClient();
    private final Map<String, String> jar = new HashMap<>();
    private UUID hostId;
    private UUID userId;

    private HttpResponse<String> call(HttpRequest.Builder b) throws Exception {
        if (!jar.isEmpty()) {
            b.header("Cookie", jar.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining("; ")));
        }
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        r.headers().allValues("set-cookie").forEach(c -> {
            String pair = c.split(";", 2)[0];
            int i = pair.indexOf('=');
            jar.put(pair.substring(0, i), pair.substring(i + 1));
        });
        return r;
    }

    @BeforeEach
    void setUp() throws Exception {
        String name = "execdisabled-" + UUID.randomUUID().toString().substring(0, 8);
        User u = new User(name, "x", Role.OPERATOR);
        u.changePasswordHash(encoder.encode(PASSWORD), Instant.now());
        userId = users.save(u).getId();
        hostId = hosts.save(DockerHost.local("exec-off-" + UUID.randomUUID(), userId)).getId();
        jdbc.update("INSERT INTO permissions (user_id, host_id, level) VALUES (?, ?, 'OPERATE')", userId, hostId);
        RealServerLogin.login(http, jar, port, name, PASSWORD);
    }

    @AfterEach
    void cleanUp() {
        hosts.deleteById(hostId);
    }

    @Test
    void operatorWithOperateGrantStillGets403ByDefault() throws Exception {
        var r = call(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/hosts/" + hostId + "/containers/web/exec"))
                .header("Content-Type", "application/json").header("X-XSRF-TOKEN", jar.get("XSRF-TOKEN"))
                .POST(HttpRequest.BodyPublishers.ofString("{}")));
        assertThat(r.statusCode()).isEqualTo(403);
        verify(execService, never()).open(any(), any(DockerEndpoint.class), anyString(), anyList(), anyInt(), anyInt(), any(), any());
    }

    @Test
    void capabilitiesReportOperatorExecAsDisabled() throws Exception {
        var r = call(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/exec/capabilities")));
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("\"operatorEnabled\":false");
    }

    @Test
    void accessReportsNoScopeForAnOperatorWithAnOperateGrantWhileOperatorExecIsOff() throws Exception {
        var r = call(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/exec/access?hostId=" + hostId)));
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).isEqualTo("{\"hostOperable\":false,\"operableProjects\":[]}");
    }
}
