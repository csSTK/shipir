package dev.bethge.shipir.proxy.status;

import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.DockerEndpoint;
import dev.bethge.shipir.docker.DockerErrorCode;
import dev.bethge.shipir.docker.client.ContainerFileService;
import dev.bethge.shipir.docker.dto.ContainerFileEntry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Lists the certificates in Caddy's storage ({@code certificates/<issuer>/<domain>/<domain>.crt}) and reads the end
 * date of each. Only the {@code .crt} files are ever requested, never the {@code .key} or {@code .json} next to them.
 * Listing a directory makes the Docker archive API stream the whole subtree as tar, so the {@code .key} files are
 * passed through the tar reader of {@code ContainerFileService.list}; they are discarded there and never retained.
 * Bounded by directory scan size, certificate count and file size.
 */
class CaddyStorageReader {

    static final String CERTIFICATES_DIR = "/data/caddy/certificates";
    static final int MAX_CERTS = 300;
    static final long MAX_CRT_BYTES = 64 * 1024;
    static final long MAX_SCAN_BYTES = 8L * 1024 * 1024;
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_.-]{1,253}");
    private static final String WILDCARD_PREFIX = "wildcard_.";

    private final ContainerFileService files;

    CaddyStorageReader(ContainerFileService files) {
        this.files = files;
    }

    List<IssuedCert> read(UUID hostId, DockerEndpoint endpoint, String containerId) {
        List<ContainerFileEntry> issuers;
        try {
            issuers = files.list(hostId, endpoint, containerId, CERTIFICATES_DIR, MAX_SCAN_BYTES);
        } catch (DockerConnectionException e) {
            if (e.getCode() == DockerErrorCode.NOT_FOUND || e.getCode() == DockerErrorCode.INVALID_PATH) {
                return List.of();
            }
            throw e;
        }
        List<IssuedCert> out = new ArrayList<>();
        for (ContainerFileEntry issuer : issuers) {
            if (!isDirectory(issuer) || out.size() >= MAX_CERTS) {
                continue;
            }
            String issuerPath = CERTIFICATES_DIR + "/" + issuer.name();
            List<ContainerFileEntry> domains;
            try {
                domains = files.list(hostId, endpoint, containerId, issuerPath, MAX_SCAN_BYTES);
            } catch (DockerConnectionException e) {
                continue;
            }
            for (ContainerFileEntry dir : domains) {
                if (!isDirectory(dir) || out.size() >= MAX_CERTS) {
                    continue;
                }
                certificate(hostId, endpoint, containerId, issuerPath, dir.name()).ifPresent(out::add);
            }
        }
        return List.copyOf(out);
    }

    private java.util.Optional<IssuedCert> certificate(UUID hostId, DockerEndpoint endpoint, String containerId,
                                                       String issuerPath, String dirName) {
        try {
            byte[] crt = files.readFile(hostId, endpoint, containerId, issuerPath + "/" + dirName + "/" + dirName + ".crt",
                    MAX_CRT_BYTES);
            if (crt == null) {
                return java.util.Optional.empty();
            }
            Instant notAfter = CertPem.notAfter(crt);
            String domain = dirName.startsWith(WILDCARD_PREFIX) ? "*." + dirName.substring(WILDCARD_PREFIX.length()) : dirName;
            return java.util.Optional.of(new IssuedCert(List.of(domain.toLowerCase(java.util.Locale.ROOT)), notAfter));
        } catch (DockerConnectionException | StatusParseException e) {
            return java.util.Optional.empty();
        }
    }

    private static boolean isDirectory(ContainerFileEntry e) {
        return e.type() == ContainerFileEntry.Type.DIRECTORY && SAFE_NAME.matcher(e.name()).matches()
                && !e.name().contains("..") && !e.name().equals(".");
    }
}
