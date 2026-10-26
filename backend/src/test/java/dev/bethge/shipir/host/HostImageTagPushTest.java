package dev.bethge.shipir.host;

import dev.bethge.shipir.compose.execution.RegistryAuthSource;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.client.ImageService;
import dev.bethge.shipir.docker.dto.RegistryLogin;
import dev.bethge.shipir.streaming.ComposeOutputStreamService;
import dev.bethge.shipir.streaming.RunStatus;
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

/** POST /images/{id}/tag and /images/push against a stubbed ImageService. */
class HostImageTagPushTest extends HostApiTestBase {

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
    private String host;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM audit_log");
        lines.clear();
        when(streams.start(any())).thenReturn((Consumer<String>) lines::add);
        when(registryAuths.auths()).thenReturn(List.of(new RegistryAuthSource.Auth("ghcr.io", "bot", "s3cret-token")));
        jdbc.update("INSERT INTO docker_hosts (name, connection_type, owner_user_id) "
                + "SELECT 'local', 'LOCAL', id FROM users WHERE username = 'admin'");
        host = jdbc.queryForObject("SELECT id FROM docker_hosts WHERE name = 'local'", UUID.class).toString();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder tag(String imageId, String repo, String tag) {
        return post("/api/hosts/{h}/images/{i}/tag", host, imageId).contentType("application/json")
                .content("{\"repository\":\"%s\",\"tag\":\"%s\"}".formatted(repo, tag));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder push(String ref) {
        return post("/api/hosts/{h}/images/push", host).contentType("application/json")
                .content("{\"reference\":\"%s\"}".formatted(ref));
    }

    private List<String> audit(String action) {
        return jdbc.queryForList("SELECT outcome || ':' || coalesce(target_id, '') || ':' || coalesce(details::text, '') "
                + "FROM audit_log WHERE action = ?", String.class, action);
    }

    // ---- tag ----

    @Test
    void operatorTagsAnImageAndItIsAudited() throws Exception {
        mvc.perform(login("operator").write(tag("sha256:abc123", "myorg/app", "v2"))).andExpect(status().isNoContent());
        verify(images).tag(any(), any(), eq("sha256:abc123"), eq("myorg/app"), eq("v2"));
        assertThat(audit("IMAGE_TAG")).hasSize(1).allSatisfy(r -> assertThat(r).startsWith("SUCCESS:myorg/app:v2:"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-v", "Upper", "a b", "a;b", "../x", "x@sha256:abc", ""})
    void invalidRepositoryIs400(String repo) throws Exception {
        mvc.perform(login("admin").write(tag("sha256:abc", repo, "v1"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IMAGE_REFERENCE"));
        verify(images, never()).tag(any(), any(), any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "-x", ".x", "a b", "a/b", "a:b", "v1;id"})
    void invalidTagIs400(String tag) throws Exception {
        mvc.perform(login("admin").write(tag("sha256:abc", "app", tag))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IMAGE_REFERENCE"));
        verify(images, never()).tag(any(), any(), any(), any(), any());
    }

    @Test
    void invalidImageIdIs400() throws Exception {
        mvc.perform(login("admin").write(tag("-bad", "app", "v1"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RESOURCE_ID"));
    }

    @Test
    void unknownImageIs404() throws Exception {
        doThrow(new DockerConnectionException(DockerErrorCode.NOT_FOUND, "x", null))
                .when(images).tag(any(), any(), any(), any(), any());
        mvc.perform(login("admin").write(tag("sha256:abc", "app", "v1"))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void viewerCannotTagAndCsrfIsRequired() throws Exception {
        mvc.perform(login("viewer").write(tag("sha256:abc", "app", "v1"))).andExpect(status().isForbidden());
        mvc.perform(tag("sha256:abc", "app", "v1").session(login("admin").session())).andExpect(status().isForbidden());
        verify(images, never()).tag(any(), any(), any(), any(), any());
    }

    // ---- push ----

    private RegistryLogin pushedWith(String ref) {
        ArgumentCaptor<RegistryLogin> auth = ArgumentCaptor.forClass(RegistryLogin.class);
        verify(images, timeout(5000)).push(any(), any(), eq(ref), auth.capture(), any());
        return auth.getValue();
    }

    @Test
    void operatorPushesWithTheMatchingRegistryLoginAndAuditHasNoSecret() throws Exception {
        mvc.perform(login("operator").write(push("ghcr.io/owner/app:1")))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.runId").isNotEmpty());
        RegistryLogin used = pushedWith("ghcr.io/owner/app:1");
        assertThat(used.username()).isEqualTo("bot");
        assertThat(used.toString()).doesNotContain("s3cret-token");
        verify(streams, timeout(5000)).complete(any(), eq(RunStatus.DONE));
        verify(streams).registerRun(any(), eq(UUID.fromString(host)), eq(null));
        assertThat(audit("IMAGE_PUSH")).hasSize(1).allSatisfy(r -> assertThat(r)
                .startsWith("SUCCESS:ghcr.io/owner/app:1").doesNotContain("s3cret").doesNotContain("bot"));
        assertThat(String.join("\n", lines)).doesNotContain("s3cret");
    }

    @Test
    void referenceWithoutTagPushesLatestAndUnmatchedRegistryGetsNoLogin() throws Exception {
        mvc.perform(login("admin").write(push("quay.io/org/app"))).andExpect(status().isAccepted());
        assertThat(pushedWith("quay.io/org/app:latest")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "-v", "--help", "app latest", "App", "a;b", "app:", "../x", "http://x/y", "app@sha256:abc"})
    void invalidReferenceIs400(String ref) throws Exception {
        mvc.perform(login("admin").write(push(ref))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IMAGE_REFERENCE"));
        verify(images, never()).push(any(), any(), any(), any(), any());
    }

    @Test
    void digestReferencesCannotBePushed() throws Exception {
        mvc.perform(login("admin").write(push("app@sha256:" + "a".repeat(64)))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IMAGE_REFERENCE"));
    }

    @Test
    void failedPushEndsRunAsFailedWithoutDaemonText() throws Exception {
        doThrow(new DockerConnectionException(DockerErrorCode.PUSH_FAILED, "denied: s3cret-token", null))
                .when(images).push(any(), any(), any(), any(), any());
        mvc.perform(login("admin").write(push("ghcr.io/owner/app:1"))).andExpect(status().isAccepted());
        verify(streams, timeout(5000)).complete(any(), eq(RunStatus.FAILED));
        assertThat(lines).contains("Push failed (PUSH_FAILED)");
        assertThat(String.join("\n", lines)).doesNotContain("s3cret");
    }

    @Test
    void progressLinesAreStreamed() throws Exception {
        doAnswer(inv -> {
            inv.<Consumer<String>>getArgument(4).accept("abc: Pushed");
            return null;
        }).when(images).push(any(), any(), any(), any(), any());
        mvc.perform(login("admin").write(push("ghcr.io/owner/app:1"))).andExpect(status().isAccepted());
        verify(streams, timeout(5000)).complete(any(), eq(RunStatus.DONE));
        assertThat(lines).contains("abc: Pushed");
    }

    @Test
    void viewerIs403AndCsrfIsRequired() throws Exception {
        mvc.perform(login("viewer").write(push("app:1"))).andExpect(status().isForbidden());
        mvc.perform(push("app:1").session(login("admin").session())).andExpect(status().isForbidden());
        verify(images, never()).push(any(), any(), any(), any(), any());
    }

    @Test
    void unknownHostIs404() throws Exception {
        mvc.perform(login("admin").write(post("/api/hosts/{h}/images/push", UUID.randomUUID())
                        .contentType("application/json").content("{\"reference\":\"app:1\"}")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("HOST_NOT_FOUND"));
    }
}
