package dev.bethge.shipir.host;

import dev.bethge.shipir.compose.execution.RegistryAuthSource;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.client.ImageService;
import dev.bethge.shipir.docker.dto.RegistryLogin;
import dev.bethge.shipir.streaming.ComposeOutputStreamService;
import dev.bethge.shipir.common.stream.RunStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** POST /images/pull against a stubbed ImageService: validation, registry auth selection, roles, audit, streamed result. */
class HostImagePullTest extends HostApiTestBase {

    /** Registry hosts resolve to a public address, so these tests do not depend on real DNS. */
    @org.springframework.boot.test.context.TestConfiguration
    static class PublicRegistryDns {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        dev.bethge.shipir.common.net.EgressPolicy stubEgressPolicy() {
            return new dev.bethge.shipir.common.net.EgressPolicy(new dev.bethge.shipir.common.net.EgressProperties(false),
                    host -> new java.net.InetAddress[]{java.net.InetAddress.getByAddress(new byte[]{93, (byte) 184, (byte) 216, 34})});
        }
    }

    @MockitoBean
    ImageService images;
    @MockitoBean
    ComposeOutputStreamService streams;
    @MockitoBean
    RegistryAuthSource registryAuths;

    private final List<String> lines = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM audit_log");
        lines.clear();
        when(streams.start(any())).thenReturn((Consumer<String>) lines::add);
        when(registryAuths.auths()).thenReturn(List.of(
                new RegistryAuthSource.Auth("ghcr.io", "bot", "s3cret-token"),
                new RegistryAuthSource.Auth("docker.io", "hubuser", "hub-secret")));
    }

    private String localHostId() {
        jdbc.update("INSERT INTO docker_hosts (name, connection_type, owner_user_id) "
                + "SELECT 'local', 'LOCAL', id FROM users WHERE username = 'admin'");
        return jdbc.queryForObject("SELECT id FROM docker_hosts WHERE name = 'local'", UUID.class).toString();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder pull(String host, String ref) {
        return post("/api/hosts/{h}/images/pull", host).contentType("application/json")
                .content("{\"reference\":\"" + ref + "\"}");
    }

    private RegistryLogin pulledWithLogin(String ref) {
        ArgumentCaptor<RegistryLogin> auth = ArgumentCaptor.forClass(RegistryLogin.class);
        verify(images, timeout(5000)).pull(any(), any(), eq(ref), auth.capture(), any());
        return auth.getValue();
    }

    @Test
    void operatorStartsPullAndGetsRunIdAndAuditRow() throws Exception {
        String host = localHostId();
        mvc.perform(login("operator").write(pull(host, "nginx:1.27")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.runId").isNotEmpty());
        pulledWithLogin("nginx:1.27");
        assertThat(jdbc.queryForList("SELECT outcome || ':' || target_id FROM audit_log WHERE action = 'IMAGE_PULL'",
                String.class)).containsExactly("SUCCESS:nginx:1.27");
        verify(streams, timeout(5000)).complete(any(), eq(RunStatus.DONE));
    }

    @Test
    void progressLinesAreStreamedAndRunRegisteredHostLevel() throws Exception {
        String host = localHostId();
        doAnswer(inv -> {
            inv.<Consumer<String>>getArgument(4).accept("abc: Pull complete");
            return null;
        }).when(images).pull(any(), any(), any(), any(), any());
        mvc.perform(login("admin").write(pull(host, "busybox"))).andExpect(status().isAccepted());
        verify(streams, timeout(5000)).complete(any(), eq(RunStatus.DONE));
        assertThat(lines).contains("abc: Pull complete");
        verify(streams).registerRun(any(), eq(UUID.fromString(host)), eq(null));
    }

    @Test
    void pullFailureCodeIsGenericForOperatorsAndDetailedForAdmins() throws Exception {
        String host = localHostId();
        doThrow(new DockerConnectionException(DockerErrorCode.TLS_ERROR, "handshake", null))
                .when(images).pull(any(), any(), any(), any(), any());
        mvc.perform(login("operator").write(pull(host, "busybox:a"))).andExpect(status().isAccepted());
        verify(streams, timeout(5000)).complete(any(), eq(RunStatus.FAILED));
        assertThat(lines).contains("Pull failed (UNREACHABLE)").doesNotContain("Pull failed (TLS_ERROR)");

        lines.clear();
        mvc.perform(login("admin").write(pull(host, "busybox:b"))).andExpect(status().isAccepted());
        verify(streams, timeout(5000).times(2)).complete(any(), eq(RunStatus.FAILED));
        assertThat(lines).contains("Pull failed (TLS_ERROR)");
    }

    @Test
    void registryDenialStaysVisibleToOperators() throws Exception {
        String host = localHostId();
        doThrow(new DockerConnectionException(DockerErrorCode.PULL_FAILED, "denied: secret", null))
                .when(images).pull(any(), any(), any(), any(), any());
        mvc.perform(login("operator").write(pull(host, "busybox:c"))).andExpect(status().isAccepted());
        verify(streams, timeout(5000)).complete(any(), eq(RunStatus.FAILED));
        assertThat(lines).contains("Pull failed (PULL_FAILED)");
    }

    @Test
    void failedPullEndsRunAsFailedWithoutDaemonText() throws Exception {
        String host = localHostId();
        doThrow(new DockerConnectionException(DockerErrorCode.PULL_FAILED, "denied: secret details", null))
                .when(images).pull(any(), any(), any(), any(), any());
        mvc.perform(login("admin").write(pull(host, "busybox:nope"))).andExpect(status().isAccepted());
        verify(streams, timeout(5000)).complete(any(), eq(RunStatus.FAILED));
        assertThat(lines).contains("Pull failed (PULL_FAILED)");
        assertThat(String.join("\n", lines)).doesNotContain("secret");
    }

    @Test
    void matchingRegistryLoginIsUsed() throws Exception {
        String host = localHostId();
        mvc.perform(login("admin").write(pull(host, "ghcr.io/owner/app:1"))).andExpect(status().isAccepted());
        RegistryLogin used = pulledWithLogin("ghcr.io/owner/app:1");
        assertThat(used.registryHost()).isEqualTo("ghcr.io");
        assertThat(used.username()).isEqualTo("bot");
        assertThat(used.toString()).doesNotContain("s3cret-token");
    }

    @Test
    void dockerHubLoginIsUsedForUnqualifiedNames() throws Exception {
        String host = localHostId();
        mvc.perform(login("admin").write(pull(host, "library/nginx"))).andExpect(status().isAccepted());
        assertThat(pulledWithLogin("library/nginx").username()).isEqualTo("hubuser");
    }

    @Test
    void noLoginIsSentToAnUnmatchedRegistry() throws Exception {
        String host = localHostId();
        mvc.perform(login("admin").write(pull(host, "quay.io/org/app:2"))).andExpect(status().isAccepted());
        assertThat(pulledWithLogin("quay.io/org/app:2")).isNull();
    }

    @Test
    void secretNeverAppearsInAuditRows() throws Exception {
        String host = localHostId();
        mvc.perform(login("admin").write(pull(host, "ghcr.io/owner/app:1"))).andExpect(status().isAccepted());
        pulledWithLogin("ghcr.io/owner/app:1");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE details::text LIKE '%s3cret%' OR target_id LIKE '%s3cret%'",
                Integer.class)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "-v", "--help", "nginx latest", "Nginx", "a;b", "nginx:", "../x", "http://x/y", "nginx@sha256:abc"})
    void invalidReferenceIs400AndNothingPulled(String bad) throws Exception {
        String host = localHostId();
        mvc.perform(login("admin").write(pull(host, bad)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IMAGE_REFERENCE"));
        verify(images, never()).pull(any(), any(), any(), any(), any());
    }

    @Test
    void digestReferenceIsAccepted() throws Exception {
        String host = localHostId();
        String ref = "nginx@sha256:" + "a".repeat(64);
        mvc.perform(login("admin").write(pull(host, ref))).andExpect(status().isAccepted());
        pulledWithLogin(ref);
    }

    @Test
    void viewerIs403() throws Exception {
        String host = localHostId();
        mvc.perform(login("viewer").write(pull(host, "nginx"))).andExpect(status().isForbidden());
        verify(images, never()).pull(any(), any(), any(), any(), any());
    }

    @Test
    void csrfTokenIsRequired() throws Exception {
        String host = localHostId();
        mvc.perform(pull(host, "nginx").session(login("admin").session())).andExpect(status().isForbidden());
    }

    @Test
    void unknownHostIs404() throws Exception {
        localHostId();
        mvc.perform(login("admin").write(pull(UUID.randomUUID().toString(), "nginx")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HOST_NOT_FOUND"));
    }
}
