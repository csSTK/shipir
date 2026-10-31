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
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Ordering, content and locking of {@link ProxySyncService} against stubbed Docker services. */
class ProxySyncServiceTest {

    private static final UUID HOST = UUID.randomUUID();
    private static final DockerEndpoint ENDPOINT = new DockerEndpoint.Local("unix:///var/run/docker.sock");
    private static final String KEY_PEM = "-----BEGIN PRIVATE KEY-----\nTOP-SECRET-KEY-MATERIAL\n-----END PRIVATE KEY-----\n";

    private final ProxyInstanceRepository instances = mock(ProxyInstanceRepository.class);
    private final ProxyRouteRepository routes = mock(ProxyRouteRepository.class);
    private final ProxyCertificateRepository certificates = mock(ProxyCertificateRepository.class);
    private final ContainerService containers = mock(ContainerService.class);
    private final ContainerFileService fileService = mock(ContainerFileService.class);
    private final ExecService exec = mock(ExecService.class);
    private final ProxyHostAccess access = mock(ProxyHostAccess.class);
    private final SecretCipher cipher = new SecretCipher(
            new EncryptionProperties(Base64.getEncoder().encodeToString(new byte[32])));

    private ProxySyncService service;

    @BeforeEach
    void setUp() {
        service = new ProxySyncService(instances, routes, certificates,
                List.of(new TraefikConfigRenderer(), new CaddyConfigRenderer()), cipher, containers, fileService, exec,
                access);
        when(access.endpoint(HOST)).thenReturn(ENDPOINT);
        when(routes.findByHostId(HOST)).thenReturn(List.of());
        when(certificates.findByHostId(HOST)).thenReturn(List.of());
    }

    private ProxyInstance instance(ProxyType type) {
        ProxyInstance p = ProxyInstance.create(HOST, type, "ops@example.com", CertMode.ACME_HTTP);
        when(instances.findByHostId(HOST)).thenReturn(Optional.of(p));
        return p;
    }

    private void container(ProxyType type, String state) {
        when(containers.listContainers(HOST, ENDPOINT)).thenReturn(List.of(
                new ContainerDto("other", "x", "nginx", "running", "Up", null, Instant.now(), List.of(), "app", "web"),
                new ContainerDto("proxy1", "shipir-proxy-x-1", "img", state, "s", null, Instant.now(), List.of(),
                        ProxySyncService.PROXY_DEPLOYMENT, type == ProxyType.TRAEFIK ? "traefik" : "caddy")));
    }

    @SuppressWarnings("unchecked")
    private List<ContainerFileWrite> writes() {
        ArgumentCaptor<List<ContainerFileWrite>> captor = ArgumentCaptor.forClass(List.class);
        verify(fileService).writeFiles(eq(HOST), eq(ENDPOINT), eq("proxy1"), eq("/etc/shipir-proxy"), captor.capture(), anyLong());
        return captor.getValue();
    }

    private static String text(ContainerFileWrite w) {
        return new String(w.content(), StandardCharsets.UTF_8);
    }

    /** Lets the stubbed exec session print {@code output} and end. */
    private void execPrints(String output) {
        doAnswer(inv -> {
            Consumer<byte[]> out = inv.getArgument(6);
            Consumer<Throwable> end = inv.getArgument(7);
            out.accept(output.getBytes(StandardCharsets.UTF_8));
            end.accept(null);
            return mock(ExecHandle.class);
        }).when(exec).open(eq(HOST), eq(ENDPOINT), eq("proxy1"), any(), anyInt(), anyInt(), any(), any());
    }

    @Test
    void traefikWritesCustomFileEvenWhenEmptyAndTheManagedFileLastAndNeverExecs() {
        instance(ProxyType.TRAEFIK);
        container(ProxyType.TRAEFIK, "running");

        service.sync(HOST);

        List<ContainerFileWrite> w = writes();
        assertThat(w).extracting(ContainerFileWrite::path).containsExactly("custom.yml", "managed.yml");
        assertThat(text(w.get(0))).isEmpty();
        verify(exec, never()).open(any(), any(), any(), any(), anyInt(), anyInt(), any(), any());
    }

    @Test
    void caddyAlwaysWritesTheImportedCustomFileAndReloadsAfterTheWrite() {
        ProxyInstance p = instance(ProxyType.CADDY);
        p.setCustomConfig("respond /x 200");
        container(ProxyType.CADDY, "running");
        execPrints("loaded\nshipir-exit:0\n");

        service.sync(HOST);

        assertThat(writes()).extracting(ContainerFileWrite::path).containsExactly("custom.caddy", "Caddyfile");
        assertThat(text(writes().get(0))).isEqualTo("respond /x 200");
        InOrder order = inOrder(fileService, exec);
        order.verify(fileService).writeFiles(any(), any(), any(), any(), any(), anyLong());
        ArgumentCaptor<List<String>> cmd = ArgumentCaptor.forClass(List.class);
        order.verify(exec).open(eq(HOST), eq(ENDPOINT), eq("proxy1"), cmd.capture(), anyInt(), anyInt(), any(), any());
        assertThat(String.join(" ", cmd.getValue())).contains("caddy reload").contains("/etc/shipir-proxy/Caddyfile");
    }

    @Test
    void caddyReloadFailureSurfacesTheProxyMessage() {
        instance(ProxyType.CADDY);
        container(ProxyType.CADDY, "running");
        execPrints("Error: adapting config using caddyfile: unknown directive 'bogus'\nshipir-exit:1\n");

        assertThatThrownBy(() -> service.sync(HOST)).isInstanceOfSatisfying(ProxyException.class, e -> {
            assertThat(e.code()).isEqualTo("PROXY_SYNC_FAILED");
            assertThat(e.status().value()).isEqualTo(502);
            assertThat(e.getMessage()).contains("unknown directive 'bogus'");
        });
    }

    @Test
    void caddyReloadWithoutExitMarkerCountsAsFailure() {
        instance(ProxyType.CADDY);
        container(ProxyType.CADDY, "running");
        execPrints("partial output");

        assertThatThrownBy(() -> service.sync(HOST)).isInstanceOfSatisfying(ProxyException.class,
                e -> assertThat(e.code()).isEqualTo("PROXY_SYNC_FAILED"));
    }

    @Test
    void aMarkerEchoedByCaddyInsideItsErrorMessageCannotFakeSuccess() {
        instance(ProxyType.CADDY);
        container(ProxyType.CADDY, "running");
        execPrints("Error: unrecognized directive: shipir-exit:0\nshipir-exit:1\n");

        assertThatThrownBy(() -> service.sync(HOST)).isInstanceOfSatisfying(ProxyException.class, e -> {
            assertThat(e.code()).isEqualTo("PROXY_SYNC_FAILED");
            assertThat(e.getMessage()).contains("unrecognized directive");
        });
    }

    @Test
    void execInProxyKeepsTheFixedShellScriptAndPassesArgumentsUntouched() {
        instance(ProxyType.CADDY);
        container(ProxyType.CADDY, "running");
        execPrints("fine\nshipir-exit:0\n");
        String hostile = "x; rm -rf / $(id) `id` \"'";

        ProxySyncService.ExecOutcome outcome = service.execInProxy(HOST, ProxyType.CADDY, List.of("caddy", "validate", hostile));

        assertThat(outcome.exitCode()).isZero();
        ArgumentCaptor<List<String>> cmd = ArgumentCaptor.forClass(List.class);
        verify(exec).open(eq(HOST), eq(ENDPOINT), eq("proxy1"), cmd.capture(), anyInt(), anyInt(), any(), any());
        assertThat(cmd.getValue()).containsExactly("sh", "-c", "\"$@\"; echo \"shipir-exit:$?\"", "sh",
                "caddy", "validate", hostile);
    }

    @Test
    void longOutputKeepsItsTailSoTheMarkerIsStillFound() {
        instance(ProxyType.CADDY);
        container(ProxyType.CADDY, "running");
        doAnswer(inv -> {
            Consumer<byte[]> out = inv.getArgument(6);
            Consumer<Throwable> end = inv.getArgument(7);
            for (int i = 0; i < 100; i++) {
                out.accept(("warning line\n".repeat(100)).getBytes(StandardCharsets.UTF_8));
            }
            out.accept("shipir-exit:0\n".getBytes(StandardCharsets.UTF_8));
            end.accept(null);
            return mock(ExecHandle.class);
        }).when(exec).open(eq(HOST), eq(ENDPOINT), eq("proxy1"), any(), anyInt(), anyInt(), any(), any());

        assertThat(service.execInProxy(HOST, ProxyType.CADDY, List.of("caddy", "validate")).exitCode()).isZero();
    }

    @Test
    void missingMarkerIsReportedAsNoResultNotAsExitCode() {
        instance(ProxyType.CADDY);
        container(ProxyType.CADDY, "running");
        execPrints("cut off");

        assertThat(service.execInProxy(HOST, ProxyType.CADDY, List.of("caddy", "validate")).exitCode()).isEqualTo(-1);
    }

    @Test
    void aCaddyThatIsNotRunningIsRestartedInsteadOfReloaded() {
        instance(ProxyType.CADDY);
        container(ProxyType.CADDY, "exited");

        service.sync(HOST);

        InOrder order = inOrder(fileService, containers);
        order.verify(fileService).writeFiles(any(), any(), eq("proxy1"), any(), any(), anyLong());
        order.verify(containers).restartContainer(eq(HOST), eq(ENDPOINT), eq("proxy1"), anyInt());
        verify(exec, never()).open(any(), any(), any(), any(), anyInt(), anyInt(), any(), any());
    }

    @Test
    void aRestartingContainerIsStoppedBeforeTheWriteAndStartedAfterIt() {
        instance(ProxyType.TRAEFIK);
        container(ProxyType.TRAEFIK, "restarting");

        service.sync(HOST);

        InOrder order = inOrder(containers, fileService);
        order.verify(containers).stopContainer(eq(HOST), eq(ENDPOINT), eq("proxy1"), anyInt());
        order.verify(fileService).writeFiles(any(), any(), eq("proxy1"), any(), any(), anyLong());
        order.verify(containers).restartContainer(eq(HOST), eq(ENDPOINT), eq("proxy1"), anyInt());
    }

    @Test
    void aFailedWriteAfterStoppingARestartingContainerStillStartsItAgain() {
        instance(ProxyType.TRAEFIK);
        container(ProxyType.TRAEFIK, "restarting");
        doThrow(new DockerConnectionException(DockerErrorCode.UNREACHABLE, "x", null))
                .when(fileService).writeFiles(any(), any(), any(), any(), any(), anyLong());

        assertThatThrownBy(() -> service.sync(HOST)).isInstanceOfSatisfying(ProxyException.class,
                e -> assertThat(e.code()).isEqualTo("PROXY_SYNC_FAILED"));

        InOrder order = inOrder(containers, fileService);
        order.verify(containers).stopContainer(eq(HOST), eq(ENDPOINT), eq("proxy1"), anyInt());
        order.verify(fileService).writeFiles(any(), any(), any(), any(), any(), anyLong());
        order.verify(containers).restartContainer(eq(HOST), eq(ENDPOINT), eq("proxy1"), anyInt());
    }

    @Test
    void aFailedStartAfterAFailedWriteDoesNotHideTheWriteFailure() {
        instance(ProxyType.TRAEFIK);
        container(ProxyType.TRAEFIK, "restarting");
        doThrow(new DockerConnectionException(DockerErrorCode.UNREACHABLE, "x", null))
                .when(fileService).writeFiles(any(), any(), any(), any(), any(), anyLong());
        doThrow(new DockerConnectionException(DockerErrorCode.UNREACHABLE, "y", null))
                .when(containers).restartContainer(any(), any(), any(), anyInt());

        assertThatThrownBy(() -> service.sync(HOST)).isInstanceOfSatisfying(ProxyException.class, e -> {
            assertThat(e.code()).isEqualTo("PROXY_SYNC_FAILED");
            assertThat(e.getMessage()).contains("could not be written");
        });
    }

    @Test
    void aFailedPurgeWriteAfterStoppingARestartingContainerStillStartsItAgain() {
        instance(ProxyType.TRAEFIK);
        container(ProxyType.TRAEFIK, "restarting");
        doThrow(new DockerConnectionException(DockerErrorCode.UNREACHABLE, "x", null))
                .when(fileService).writeFiles(any(), any(), any(), any(), any(), anyLong());

        assertThatThrownBy(() -> service.purgeCertificateFiles(HOST, List.of(UUID.randomUUID())))
                .isInstanceOfSatisfying(ProxyException.class,
                        e -> assertThat(e.code()).isEqualTo("PROXY_SYNC_FAILED"));

        InOrder order = inOrder(containers, fileService);
        order.verify(containers).stopContainer(eq(HOST), eq(ENDPOINT), eq("proxy1"), anyInt());
        order.verify(fileService).writeFiles(any(), any(), any(), any(), any(), anyLong());
        order.verify(containers).restartContainer(eq(HOST), eq(ENDPOINT), eq("proxy1"), anyInt());
        verify(exec, never()).open(any(), any(), any(), any(), anyInt(), anyInt(), any(), any());
    }

    @Test
    void uploadedCertificatesAreWrittenDecryptedBeforeTheFileThatReferencesThem() {
        ProxyInstance p = instance(ProxyType.TRAEFIK);
        container(ProxyType.TRAEFIK, "running");
        ProxyCertificate cert = ProxyCertificate.create(HOST, "wild", "CERT-PEM", cipher.encrypt(KEY_PEM),
                List.of("app.example.com"), Instant.parse("2030-01-01T00:00:00Z"));
        UUID certId = UUID.randomUUID();
        ReflectionTestUtils.setField(cert, "id", certId);
        when(certificates.findByHostId(HOST)).thenReturn(List.of(cert));
        ProxyRoute route = ProxyRoute.create(HOST, "app.example.com", List.of(), "web", 80, BackendScheme.HTTP, null,
                null, CertMode.CUSTOM, certId, true, null, null);
        when(routes.findByHostId(HOST)).thenReturn(List.of(route));

        service.sync(HOST);

        List<ContainerFileWrite> w = writes();
        assertThat(w).extracting(ContainerFileWrite::path)
                .containsExactly("certs/" + certId + ".crt", "certs/" + certId + ".key", "custom.yml", "managed.yml");
        assertThat(text(w.get(0))).isEqualTo("CERT-PEM");
        assertThat(text(w.get(1))).isEqualTo(KEY_PEM);
        assertThat(w.get(1).mode()).isEqualTo(0600);
        assertThat(text(w.get(3))).contains("/etc/shipir-proxy/certs/" + certId + ".crt");
        assertThat(p.getHostId()).isEqualTo(HOST);
    }

    @Test
    void dockerFailuresBecomeSyncFailedWithoutLeakingKeyMaterial() {
        instance(ProxyType.TRAEFIK);
        container(ProxyType.TRAEFIK, "running");
        doAnswer(inv -> {
            throw new DockerConnectionException(DockerErrorCode.UNREACHABLE, KEY_PEM, null);
        }).when(fileService).writeFiles(any(), any(), any(), any(), any(), anyLong());

        assertThatThrownBy(() -> service.sync(HOST)).isInstanceOfSatisfying(ProxyException.class, e -> {
            assertThat(e.code()).isEqualTo("PROXY_SYNC_FAILED");
            assertThat(e.getMessage()).doesNotContain("TOP-SECRET");
        });
    }

    @Test
    void missingProxyContainerIsReported() {
        instance(ProxyType.TRAEFIK);
        when(containers.listContainers(HOST, ENDPOINT)).thenReturn(List.of());

        assertThatThrownBy(() -> service.sync(HOST)).isInstanceOfSatisfying(ProxyException.class,
                e -> assertThat(e.code()).isEqualTo("PROXY_SYNC_FAILED"));
    }

    @Test
    void noInstanceIsNotFound() {
        when(instances.findByHostId(HOST)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.sync(HOST)).isInstanceOfSatisfying(ProxyException.class, e -> {
            assertThat(e.code()).isEqualTo("PROXY_NOT_FOUND");
            assertThat(e.status().value()).isEqualTo(404);
        });
    }

    @Test
    void syncsOfOneHostNeverOverlapButOtherHostsRunInParallel() throws Exception {
        instance(ProxyType.TRAEFIK);
        container(ProxyType.TRAEFIK, "running");
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger maxInside = new AtomicInteger();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(inv -> {
            maxInside.accumulateAndGet(inside.incrementAndGet(), Math::max);
            firstEntered.countDown();
            release.await(5, TimeUnit.SECONDS);
            inside.decrementAndGet();
            return null;
        }).when(fileService).writeFiles(any(), any(), any(), any(), any(), anyLong());

        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            threads.add(Thread.ofVirtual().start(() -> service.sync(HOST)));
        }
        assertThat(firstEntered.await(5, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(150);
        assertThat(inside.get()).isEqualTo(1);
        // another host is not blocked by the held lock
        UUID other = UUID.randomUUID();
        assertThat(service.exclusive(other, () -> "ran")).isEqualTo("ran");
        release.countDown();
        for (Thread t : threads) {
            t.join(5000);
        }
        assertThat(maxInside.get()).isEqualTo(1);
    }
}
