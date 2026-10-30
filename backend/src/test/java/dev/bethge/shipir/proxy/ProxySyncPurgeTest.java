package dev.bethge.shipir.proxy;

import dev.bethge.shipir.common.security.EncryptionProperties;
import dev.bethge.shipir.common.security.SecretCipher;
import dev.bethge.shipir.compose.generator.model.ProxyType;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.client.ContainerFileService;
import dev.bethge.shipir.docker.client.ContainerService;
import dev.bethge.shipir.docker.client.ExecHandle;
import dev.bethge.shipir.docker.client.ExecService;
import dev.bethge.shipir.docker.dto.ContainerDto;
import dev.bethge.shipir.docker.dto.ContainerFileWrite;
import dev.bethge.shipir.proxy.render.CaddyConfigRenderer;
import dev.bethge.shipir.proxy.render.TraefikConfigRenderer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Deleted certificates must not leave their private key in the proxy container ({@link ProxySyncService#purgeCertificateFiles}). */
class ProxySyncPurgeTest {

    private static final UUID HOST = UUID.randomUUID();
    private static final DockerEndpoint ENDPOINT = new DockerEndpoint.Local("unix:///var/run/docker.sock");

    private final ProxyInstanceRepository instances = mock(ProxyInstanceRepository.class);
    private final ContainerService containers = mock(ContainerService.class);
    private final ContainerFileService fileService = mock(ContainerFileService.class);
    private final ExecService exec = mock(ExecService.class);
    private final ProxyHostAccess access = mock(ProxyHostAccess.class);
    private ProxySyncService service;

    @BeforeEach
    void setUp() {
        service = new ProxySyncService(instances, mock(ProxyRouteRepository.class), mock(ProxyCertificateRepository.class),
                List.of(new TraefikConfigRenderer(), new CaddyConfigRenderer()),
                new SecretCipher(new EncryptionProperties(Base64.getEncoder().encodeToString(new byte[32]))),
                containers, fileService, exec, access);
        when(access.endpoint(HOST)).thenReturn(ENDPOINT);
        when(instances.findByHostId(HOST)).thenReturn(
                Optional.of(ProxyInstance.create(HOST, ProxyType.TRAEFIK, "ops@example.com", CertMode.ACME_HTTP)));
    }

    private void container(String state) {
        when(containers.listContainers(HOST, ENDPOINT)).thenReturn(List.of(
                new ContainerDto("proxy1", "p", "img", state, "s", null, Instant.now(), List.of(),
                        ProxySyncService.PROXY_DEPLOYMENT, "traefik")));
    }

    private void execEndsImmediately() {
        doAnswer(inv -> {
            Consumer<Throwable> end = inv.getArgument(7);
            end.accept(null);
            return mock(ExecHandle.class);
        }).when(exec).open(eq(HOST), eq(ENDPOINT), eq("proxy1"), any(), anyInt(), anyInt(), any(), any());
    }

    @SuppressWarnings("unchecked")
    private List<ContainerFileWrite> blanks() {
        ArgumentCaptor<List<ContainerFileWrite>> captor = ArgumentCaptor.forClass(List.class);
        verify(fileService).writeFiles(eq(HOST), eq(ENDPOINT), eq("proxy1"), eq("/etc/shipir-proxy"), captor.capture(), anyLong());
        return captor.getValue();
    }

    @Test
    void overwritesTheFilesWithEmptyContentAndUnlinksThemWithAFixedArgumentList() {
        UUID id = UUID.randomUUID();
        container("running");
        execEndsImmediately();

        service.purgeCertificateFiles(HOST, List.of(id));

        List<ContainerFileWrite> writes = blanks();
        assertThat(writes).extracting(ContainerFileWrite::path)
                .containsExactly("certs/" + id + ".crt", "certs/" + id + ".key");
        assertThat(writes).allSatisfy(w -> {
            assertThat(w.content()).isEmpty();
            assertThat(w.mode()).isEqualTo(0600);
        });
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> cmd = ArgumentCaptor.forClass(List.class);
        verify(exec).open(eq(HOST), eq(ENDPOINT), eq("proxy1"), cmd.capture(), anyInt(), anyInt(), any(), any());
        assertThat(cmd.getValue()).containsExactly("rm", "-f", "--",
                "/etc/shipir-proxy/certs/" + id + ".crt", "/etc/shipir-proxy/certs/" + id + ".key");
    }

    @Test
    void aStoppedContainerStillGetsTheKeyOverwrittenButNoExec() {
        UUID id = UUID.randomUUID();
        container("exited");

        service.purgeCertificateFiles(HOST, List.of(id));

        assertThat(blanks()).hasSize(2);
        verify(exec, never()).open(any(), any(), any(), any(), anyInt(), anyInt(), any(), any());
    }

    @Test
    void aRestartingContainerIsStoppedOverwrittenAndStartedAgain() {
        UUID id = UUID.randomUUID();
        container("restarting");

        service.purgeCertificateFiles(HOST, List.of(id));

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(containers, fileService);
        order.verify(containers).stopContainer(eq(HOST), eq(ENDPOINT), eq("proxy1"), anyInt());
        order.verify(fileService).writeFiles(eq(HOST), eq(ENDPOINT), eq("proxy1"), eq("/etc/shipir-proxy"), any(), anyLong());
        order.verify(containers).restartContainer(eq(HOST), eq(ENDPOINT), eq("proxy1"), anyInt());
        verify(exec, never()).open(any(), any(), any(), any(), anyInt(), anyInt(), any(), any());
    }

    @Test
    void aFailingUnlinkIsNotAnErrorBecauseTheKeyIsAlreadyGone() {
        container("running");
        doThrow(new DockerConnectionException(DockerErrorCode.INVALID_STATE, "x", null)).when(exec)
                .open(any(), any(), any(), any(), anyInt(), anyInt(), any(), any());

        service.purgeCertificateFiles(HOST, List.of(UUID.randomUUID()));

        assertThat(blanks()).hasSize(2);
    }

    @Test
    void aFailingOverwriteIsReportedSoTheCallerCanRollBack() {
        container("running");
        doThrow(new DockerConnectionException(DockerErrorCode.UNREACHABLE, "x", null)).when(fileService)
                .writeFiles(any(), any(), any(), any(), any(), anyLong());

        assertThatThrownBy(() -> service.purgeCertificateFiles(HOST, List.of(UUID.randomUUID())))
                .isInstanceOfSatisfying(ProxyException.class, e -> assertThat(e.code()).isEqualTo("PROXY_SYNC_FAILED"));
    }

    @Test
    void aDockerListingFailureIsNotSwallowed() {
        when(containers.listContainers(HOST, ENDPOINT))
                .thenThrow(new DockerConnectionException(DockerErrorCode.UNREACHABLE, "x", null));

        assertThatThrownBy(() -> service.purgeCertificateFiles(HOST, List.of(UUID.randomUUID())))
                .isInstanceOfSatisfying(ProxyException.class, e -> assertThat(e.code()).isEqualTo("PROXY_SYNC_FAILED"));
    }

    @Test
    void doesNothingWithoutAProxyOrAContainer() {
        when(instances.findByHostId(HOST)).thenReturn(Optional.empty());
        service.purgeCertificateFiles(HOST, List.of(UUID.randomUUID()));

        when(instances.findByHostId(HOST)).thenReturn(
                Optional.of(ProxyInstance.create(HOST, ProxyType.TRAEFIK, "ops@example.com", CertMode.ACME_HTTP)));
        when(containers.listContainers(HOST, ENDPOINT)).thenReturn(List.of());
        service.purgeCertificateFiles(HOST, List.of(UUID.randomUUID()));

        verify(fileService, never()).writeFiles(any(), any(), any(), any(), any(), anyLong());
    }
}
