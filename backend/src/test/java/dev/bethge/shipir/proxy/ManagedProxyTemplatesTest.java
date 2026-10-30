package dev.bethge.shipir.proxy;

import dev.bethge.shipir.compose.generator.model.ProxyType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ManagedProxyTemplatesTest {

    private static final String NETWORK = "shipir-proxy";
    /** Compose keys that grant host-level access; the bundled templates must not use any of them. */
    private static final Set<String> HOST_ACCESS_KEYS = Set.of("privileged", "cap_add", "pid", "ipc", "network_mode",
            "devices", "security_opt", "userns_mode", "use_api_socket", "cgroup_parent", "uts", "cgroup");

    private final ManagedProxyTemplates templates = new ManagedProxyTemplates();
    private final YAMLMapper yaml = YAMLMapper.builder().build();

    @Test
    void bothTemplatesLoadAsComposeFiles() {
        for (ProxyType type : ProxyType.values()) {
            JsonNode compose = parse(type);
            assertThat(compose.path("services").isObject()).as(type + " has services").isTrue();
            assertThat(compose.path("services").size()).as(type + " service count").isEqualTo(1);
        }
    }

    @Test
    void imagesArePinnedToAVersionTag() {
        for (ProxyType type : ProxyType.values()) {
            for (JsonNode service : services(parse(type))) {
                String image = service.path("image").asText();
                String name = image.substring(image.lastIndexOf('/') + 1);
                assertThat(name).as(type + " image " + image).containsPattern(":v?\\d+(\\.\\d+)*(-[a-z]+)?$");
                assertThat(image).as(type + " image").doesNotEndWith(":latest");
            }
        }
    }

    @Test
    void noDockerSocketIsMountedAndNoHostPathIsBound() {
        for (ProxyType type : ProxyType.values()) {
            for (String mount : mounts(parse(type))) {
                assertThat(mount).as(type + " mount").doesNotContain("docker.sock");
                assertThat(mount).as(type + " mount is a named volume, not a host path").doesNotStartWith("/")
                        .doesNotStartWith(".");
            }
        }
    }

    @Test
    void noHostLevelAccessKeysAreUsed() {
        for (ProxyType type : ProxyType.values()) {
            for (JsonNode service : services(parse(type))) {
                List<String> used = new ArrayList<>();
                service.propertyNames().forEach(key -> {
                    if (HOST_ACCESS_KEYS.contains(key)) {
                        used.add(key);
                    }
                });
                assertThat(used).as(type + " host-level keys").isEmpty();
            }
        }
    }

    @Test
    void networkIsDeclaredExternalAndJoinedByTheProxy() {
        for (ProxyType type : ProxyType.values()) {
            JsonNode compose = parse(type);
            JsonNode network = compose.path("networks").path(NETWORK);
            assertThat(network.path("name").asText()).as(type + " network name").isEqualTo(NETWORK);
            assertThat(network.path("external").asBoolean()).as(type + " network external").isTrue();
            JsonNode service = services(compose).get(0);
            assertThat(names(service.path("networks"))).as(type + " service networks").containsExactly(NETWORK);
        }
    }

    @Test
    void everyNamedVolumeUsedByAServiceIsDeclared() {
        for (ProxyType type : ProxyType.values()) {
            JsonNode compose = parse(type);
            assertThat(mounts(compose)).as(type + " mounts").isNotEmpty();
            for (String mount : mounts(compose)) {
                String source = mount.substring(0, mount.indexOf(':'));
                assertThat(compose.path("volumes").has(source)).as(type + " volume " + source).isTrue();
            }
        }
    }

    @Test
    void publishesHttpHttpsAndHttp3Ports() {
        assertThat(ports(parse(ProxyType.TRAEFIK))).containsExactlyInAnyOrder("80:80", "443:443");
        assertThat(ports(parse(ProxyType.CADDY))).containsExactlyInAnyOrder("80:80", "443:443", "443:443/udp");
    }

    @Test
    void traefikUsesFileProviderAndBothAcmeResolvers() {
        String text = templates.yaml(ProxyType.TRAEFIK);
        JsonNode command = service(parse(ProxyType.TRAEFIK), "traefik").path("command");
        List<String> flags = names(command);
        assertThat(flags)
                .contains("--providers.file.directory=/etc/shipir-proxy")
                .contains("--providers.file.watch=true")
                .contains("--entrypoints.web.address=:80")
                .contains("--entrypoints.websecure.address=:443")
                .contains("--certificatesresolvers.letsencrypt-http.acme.tlschallenge=true")
                .contains("--certificatesresolvers.letsencrypt-dns.acme.dnschallenge=true")
                .contains("--certificatesresolvers.letsencrypt-dns.acme.dnschallenge.provider=${DNS_PROVIDER:?set DNS_PROVIDER in .env}")
                .contains("--certificatesresolvers.letsencrypt-http.acme.storage=/letsencrypt/acme-http.json")
                .contains("--certificatesresolvers.letsencrypt-dns.acme.storage=/letsencrypt/acme-dns.json");
        assertThat(text).contains("${ACME_EMAIL:?");
        assertThat(flags).noneMatch(flag -> flag.contains("providers.docker"));
    }

    @Test
    void traefikTakesProviderCredentialsFromTheDeploymentEnvFile() {
        assertThat(service(parse(ProxyType.TRAEFIK), "traefik").path("env_file").toString()).contains(".env");
    }

    @Test
    void caddyReadsItsCaddyfileFromTheConfigVolume() {
        JsonNode service = service(parse(ProxyType.CADDY), "caddy");
        assertThat(names(service.path("command"))).containsSubsequence("run", "--config", "/etc/shipir-proxy/Caddyfile");
        assertThat(mounts(parse(ProxyType.CADDY))).anyMatch(mount -> mount.startsWith("shipir-proxy-config:/etc/shipir-proxy"));
        assertThat(mounts(parse(ProxyType.CADDY))).anyMatch(mount -> mount.endsWith(":/data"));
    }

    @Test
    void caddyAdminApiIsNotPublished() {
        assertThat(ports(parse(ProxyType.CADDY))).noneMatch(port -> port.contains("2019"));
    }

    @Test
    void dockerComposeConfigAcceptsBothTemplates() throws Exception {
        assumeTrue(composeAvailable(), "docker compose CLI not available");
        for (ProxyType type : ProxyType.values()) {
            Path dir = Files.createTempDirectory("managed-proxy-" + type.name().toLowerCase());
            try {
                Files.writeString(dir.resolve("compose.yaml"), templates.yaml(type));
                Files.writeString(dir.resolve(".env"), "ACME_EMAIL=ops@example.com\nDNS_PROVIDER=cloudflare\n");
                Process p = new ProcessBuilder("docker", "compose", "-p", "managedproxy", "-f", "compose.yaml",
                        "config", "-q").directory(dir.toFile()).redirectErrorStream(true).start();
                String out = new String(p.getInputStream().readAllBytes());
                assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
                assertThat(p.exitValue()).as(type + ": " + out).isZero();
            } finally {
                Files.deleteIfExists(dir.resolve("compose.yaml"));
                Files.deleteIfExists(dir.resolve(".env"));
                Files.deleteIfExists(dir);
            }
        }
    }

    private JsonNode parse(ProxyType type) {
        String text = templates.yaml(type);
        assertThat(text).as(type + " template").isNotBlank();
        return yaml.readTree(text);
    }

    private static JsonNode service(JsonNode compose, String name) {
        return compose.path("services").path(name);
    }

    private static List<JsonNode> services(JsonNode compose) {
        List<JsonNode> result = new ArrayList<>();
        compose.path("services").forEach(result::add);
        return result;
    }

    private static List<String> mounts(JsonNode compose) {
        List<String> result = new ArrayList<>();
        for (JsonNode service : services(compose)) {
            service.path("volumes").forEach(volume -> result.add(volume.asText()));
        }
        return result;
    }

    private static List<String> ports(JsonNode compose) {
        List<String> result = new ArrayList<>();
        for (JsonNode service : services(compose)) {
            service.path("ports").forEach(port -> result.add(port.asText()));
        }
        return result;
    }

    private static List<String> names(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node.isObject()) {
            node.propertyNames().forEach(result::add);
        } else {
            node.forEach(item -> result.add(item.asText()));
        }
        return result;
    }

    private static boolean composeAvailable() {
        try {
            Process p = new ProcessBuilder("docker", "compose", "version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
