package dev.bethge.shipir.proxy.config;

import dev.bethge.shipir.compose.generator.model.ProxyType;
import dev.bethge.shipir.docker.DockerConnectionException;
import dev.bethge.shipir.docker.ViewerErrorCodes;
import dev.bethge.shipir.docker.client.ContainerFileService;
import dev.bethge.shipir.docker.dto.ContainerFileWrite;
import dev.bethge.shipir.proxy.ProxyException;
import dev.bethge.shipir.proxy.ProxyHostAccess;
import dev.bethge.shipir.proxy.ProxySyncService;
import dev.bethge.shipir.proxy.render.ProxyConfigRenderer;
import dev.bethge.shipir.proxy.render.RenderedFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Lets Caddy itself judge the custom file. The managed files and the candidate custom file are copied into a throw-away
 * directory inside the running proxy container ({@code import custom.caddy} resolves next to the Caddyfile, so the copy
 * is complete and the live files stay untouched) and {@code caddy validate} runs there as an argument list. The directory
 * name is generated here (a UUID), never taken from input, and removed afterwards.
 */
@Component
class CaddyCustomConfigValidator implements CustomConfigValidator {

    private static final Logger log = LoggerFactory.getLogger(CaddyCustomConfigValidator.class);
    private static final String CUSTOM_FILE = "custom.caddy";
    private static final long COPY_LIMIT = 5L * 1024 * 1024;

    private final ProxySyncService sync;
    private final ContainerFileService files;
    private final ProxyHostAccess access;

    CaddyCustomConfigValidator(ProxySyncService sync, ContainerFileService files, ProxyHostAccess access) {
        this.sync = sync;
        this.files = files;
        this.access = access;
    }

    @Override
    public ProxyType type() {
        return ProxyType.CADDY;
    }

    @Override
    public void validate(UUID hostId, String content, List<RenderedFile> managed) {
        CustomConfigValidator.checkSize(content);
        String dir = "validate-" + UUID.randomUUID();
        String absolute = ProxyConfigRenderer.CONFIG_DIR + "/" + dir;
        List<ContainerFileWrite> copy = new ArrayList<>();
        for (RenderedFile f : managed) {
            copy.add(new ContainerFileWrite(dir + "/" + f.name(), f.content().getBytes(StandardCharsets.UTF_8), 0644));
        }
        copy.add(new ContainerFileWrite(dir + "/" + CUSTOM_FILE, content.getBytes(StandardCharsets.UTF_8), 0644));
        String containerId = sync.proxyContainerId(hostId, ProxyType.CADDY);
        try {
            files.writeFiles(hostId, access.endpoint(hostId), containerId, ProxyConfigRenderer.CONFIG_DIR, copy,
                    COPY_LIMIT);
            ProxySyncService.ExecOutcome result = sync.execInProxy(hostId, ProxyType.CADDY,
                    List.of("caddy", "validate", "--config", absolute + "/Caddyfile", "--adapter", "caddyfile"));
            if (result.exitCode() != 0) {
                throw CustomConfigValidator.invalid("Caddy rejected the configuration: " + result.output());
            }
        } catch (DockerConnectionException e) {
            throw new ProxyException(HttpStatus.BAD_GATEWAY, "PROXY_SYNC_FAILED",
                    "The configuration could not be validated ("
                            + ViewerErrorCodes.forViewer(e.getCode(), access.isAdmin()) + ")");
        } finally {
            cleanUp(hostId, absolute);
        }
    }

    private void cleanUp(UUID hostId, String absolute) {
        try {
            sync.execInProxy(hostId, ProxyType.CADDY, List.of("rm", "-rf", "--", absolute));
        } catch (RuntimeException e) {
            log.warn("Could not remove the validation copy {} on host {}: {}", absolute, hostId, e.getClass().getSimpleName());
        }
    }
}
