package dev.bethge.shipir.proxy.status;

import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.client.ContainerFileService;
import dev.bethge.shipir.docker.dto.ContainerFileEntry;
import dev.bethge.shipir.proxy.cert.TestCerts;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.endsWith;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CaddyStorageReaderTest {

    static final String ROOT = CaddyStorageReader.CERTIFICATES_DIR;
    static final String ISSUER = "acme-v02.api.letsencrypt.org-directory";
    static final DockerEndpoint EP = new DockerEndpoint.Local("unix:///var/run/docker.sock");
    final UUID host = UUID.randomUUID();
    final ContainerFileService files = mock(ContainerFileService.class);
    final CaddyStorageReader reader = new CaddyStorageReader(files);

    static ContainerFileEntry dir(String parent, String name) {
        return new ContainerFileEntry(name, parent + "/" + name, ContainerFileEntry.Type.DIRECTORY, 0, Instant.now(), 0755, null);
    }

    void layout(String... domainDirs) {
        when(files.list(any(), any(), eq("c1"), eq(ROOT), anyLong())).thenReturn(List.of(dir(ROOT, ISSUER)));
        when(files.list(any(), any(), eq("c1"), eq(ROOT + "/" + ISSUER), anyLong()))
                .thenReturn(java.util.Arrays.stream(domainDirs).map(d -> dir(ROOT + "/" + ISSUER, d)).toList());
    }

    void crt(String dirName, TestCerts.Pair pair) {
        when(files.readFile(any(), any(), eq("c1"), eq(ROOT + "/" + ISSUER + "/" + dirName + "/" + dirName + ".crt"), anyLong()))
                .thenReturn(pair.certPem().getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void readsCertificatesOfTheStorageLayoutAndOnlyTheCrtFiles() {
        layout("shop.example.com", "wildcard_.example.org");
        crt("shop.example.com", TestCerts.valid("RSA", "shop.example.com", "dns:shop.example.com"));
        crt("wildcard_.example.org", TestCerts.valid("RSA", "example.org", "dns:*.example.org"));

        List<IssuedCert> certs = reader.read(host, EP, "c1");

        assertThat(certs).extracting(IssuedCert::domains).containsExactly(List.of("shop.example.com"), List.of("*.example.org"));
        assertThat(certs).allSatisfy(c -> assertThat(c.notAfter()).isAfter(Instant.now()));
        verify(files, never()).readFile(any(), any(), any(), endsWith(".key"), anyLong());
        verify(files, never()).readFile(any(), any(), any(), endsWith(".json"), anyLong());
    }

    @Test
    void aMissingCrtOrUnreadableOneIsSkipped() {
        layout("a.example.com", "b.example.com");
        when(files.readFile(any(), any(), eq("c1"), eq(ROOT + "/" + ISSUER + "/a.example.com/a.example.com.crt"), anyLong()))
                .thenThrow(new DockerConnectionException(DockerErrorCode.NOT_FOUND, "x", null));
        when(files.readFile(any(), any(), eq("c1"), eq(ROOT + "/" + ISSUER + "/b.example.com/b.example.com.crt"), anyLong()))
                .thenReturn("garbage".getBytes(StandardCharsets.UTF_8));

        assertThat(reader.read(host, EP, "c1")).isEmpty();
    }

    @Test
    void noCertificatesDirectoryMeansNoCertificates() {
        when(files.list(any(), any(), any(), eq(ROOT), anyLong()))
                .thenThrow(new DockerConnectionException(DockerErrorCode.NOT_FOUND, "x", null));

        assertThat(reader.read(host, EP, "c1")).isEmpty();
    }

    @Test
    void directoryNamesThatCouldEscapeTheStorageAreNeverRead() {
        layout("..", "a/b", "ok.example.com", "-rf", "x y");
        crt("ok.example.com", TestCerts.valid("RSA", "ok.example.com", "dns:ok.example.com"));

        List<IssuedCert> certs = reader.read(host, EP, "c1");

        assertThat(certs).hasSize(1);
        verify(files, never()).readFile(any(), any(), any(), argThat(p -> p.contains("..")), anyLong());
    }
}
