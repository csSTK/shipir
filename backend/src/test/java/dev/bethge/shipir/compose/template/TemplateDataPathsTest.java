package dev.bethge.shipir.compose.template;

import dev.bethge.shipir.compose.generator.ComposeGeneratorService;
import dev.bethge.shipir.compose.generator.model.ServiceSpec;
import dev.bethge.shipir.compose.generator.model.VolumeMountSpec;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression pin for shipr-0qbh.40 (File Browser once kept its database outside the volume). The table records the
 * data paths each image uses, which were checked by hand: rows marked {@code inspect} against the image's declared
 * VOLUMEs ({@code docker image inspect} or the registry config), rows marked {@code docs} only against the image
 * documentation (the image declares no VOLUME, or it was not inspected). This test does not verify the image side;
 * it only pins the template side: each listed target must be mounted writable, and a new template without an entry
 * fails. {@code scripts/check-template-data-paths.sh} re-checks declared VOLUMEs against the registry.
 */
class TemplateDataPathsTest {

    /** template id -> service -> container paths that must be mounted writable (not read-only). */
    private static final Map<String, Map<String, Set<String>>> DATA_PATHS = Map.ofEntries(
            Map.entry("nginx", Map.of("web", Set.of())), // docs; static files from a read-only bind, nothing to persist
            Map.entry("postgres", Map.of("db", Set.of("/var/lib/postgresql/data"))), // inspect
            Map.entry("redis", Map.of("cache", Set.of("/data"))), // inspect
            Map.entry("mariadb", Map.of("db", Set.of("/var/lib/mysql"))), // inspect
            Map.entry("wordpress", Map.of("web", Set.of("/var/www/html"), "db", Set.of("/var/lib/mysql"))), // inspect
            Map.entry("uptime-kuma", Map.of("kuma", Set.of("/app/data"))), // inspect
            Map.entry("adminer", Map.of("adminer", Set.of())), // docs; stateless
            Map.entry("vaultwarden", Map.of("vaultwarden", Set.of("/data"))), // inspect
            Map.entry("gitea", Map.of("gitea", Set.of("/data"))), // inspect
            Map.entry("grafana", Map.of("grafana", Set.of("/var/lib/grafana"))), // docs
            Map.entry("jellyfin", Map.of("jellyfin", Set.of("/config", "/cache"))), // docs
            Map.entry("home-assistant", Map.of("homeassistant", Set.of("/config"))), // docs
            Map.entry("nextcloud", Map.of("nextcloud", Set.of("/var/www/html"))), // inspect
            Map.entry("pihole", Map.of("pihole", Set.of("/etc/pihole", "/etc/dnsmasq.d"))), // docs
            Map.entry("nginx-proxy-manager", Map.of("npm", Set.of("/data", "/etc/letsencrypt"))), // inspect
            Map.entry("homepage", Map.of("homepage", Set.of("/app/config"))), // docs
            Map.entry("filebrowser", Map.of("filebrowser", Set.of("/srv", "/database"))), // inspect
            Map.entry("syncthing", Map.of("syncthing", Set.of("/var/syncthing"))), // inspect
            Map.entry("mysql", Map.of("db", Set.of("/var/lib/mysql"))), // inspect
            Map.entry("mongodb", Map.of("mongo", Set.of("/data/db"))), // inspect
            Map.entry("rabbitmq", Map.of("rabbitmq", Set.of("/var/lib/rabbitmq"))), // docs
            Map.entry("minio", Map.of("minio", Set.of("/data"))), // inspect
            Map.entry("influxdb", Map.of("influxdb", Set.of("/var/lib/influxdb2", "/etc/influxdb2"))), // inspect
            Map.entry("ghost", Map.of("ghost", Set.of("/var/lib/ghost/content"), "db", Set.of("/var/lib/mysql"))), // inspect
            Map.entry("n8n", Map.of("n8n", Set.of("/home/node/.n8n"))), // docs
            Map.entry("paperless-ngx", Map.of( // inspect
                    "webserver", Set.of("/usr/src/paperless/data", "/usr/src/paperless/media",
                            "/usr/src/paperless/consume"),
                    "db", Set.of("/var/lib/postgresql/data"),
                    "broker", Set.of("/data"))),
            Map.entry("bookstack", Map.of("bookstack", Set.of("/config"), "db", Set.of("/var/lib/mysql"))), // inspect
            Map.entry("linkding", Map.of("linkding", Set.of("/etc/linkding/data"))), // docs
            Map.entry("plex", Map.of("plex", Set.of("/config", "/transcode", "/data"))), // inspect
            Map.entry("sonarr", Map.of("sonarr", Set.of("/config", "/tv", "/downloads"))), // docs
            Map.entry("radarr", Map.of("radarr", Set.of("/config", "/movies", "/downloads"))), // docs
            Map.entry("navidrome", Map.of("navidrome", Set.of("/data", "/music"))), // inspect
            Map.entry("audiobookshelf", Map.of("audiobookshelf", // docs
                    Set.of("/config", "/metadata", "/audiobooks", "/podcasts"))));

    private final TemplateCatalog catalog = new TemplateCatalog(new ComposeGeneratorService(),
            new ClassPathResource("templates/apps.json"));

    private AppTemplate template(String id) {
        return catalog.all().stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void everyBundledTemplateDeclaresItsDataPaths() {
        Set<String> bundled = new HashSet<>();
        catalog.all().forEach(t -> bundled.add(t.id()));
        assertThat(DATA_PATHS.keySet()).containsExactlyInAnyOrderElementsOf(bundled);
    }

    @Test
    void everyDataPathIsMountedWritableFromAVolume() {
        for (var entry : DATA_PATHS.entrySet()) {
            AppTemplate t = template(entry.getKey());
            assertThat(t.spec().services().stream().map(ServiceSpec::name))
                    .as("services of %s", t.id())
                    .containsExactlyInAnyOrderElementsOf(entry.getValue().keySet());
            for (ServiceSpec s : t.spec().services()) {
                List<VolumeMountSpec> mounts = s.volumes() == null ? List.of() : s.volumes();
                for (String path : entry.getValue().get(s.name())) {
                    assertThat(mounts)
                            .as("%s/%s mounts %s", t.id(), s.name(), path)
                            .anySatisfy(m -> {
                                assertThat(m.target()).isEqualTo(path);
                                assertThat(m.readOnly()).as("%s read-only", path).isFalse();
                            });
                }
            }
        }
    }

    @Test
    void fileBrowserKeepsItsDatabaseInThePersistedVolume() {
        ServiceSpec fb = template("filebrowser").spec().services().get(0);
        assertThat(fb.command()).containsExactly("--database", "/database/filebrowser.db");
    }

    @Test
    void fileBrowserTextMatchesTheFirstRunCredentials() {
        // v2.31.2 prints no generated password and accepts admin / admin on a fresh database (verified by login
        // against a throwaway container); newer releases generate a random one, so a version bump must revisit this.
        assertThat(template("filebrowser").spec().services().get(0).image()).endsWith(":v2.31.2");
        assertThat(template("filebrowser").description()).contains("admin / admin");
    }

    @Test
    void minioServesFromTheMountedDataPathAndSaysItIsACommunityFork() {
        AppTemplate minio = template("minio");
        ServiceSpec s = minio.spec().services().get(0);
        assertThat(s.command()).startsWith("server", "/data");
        assertThat(minio.description()).contains("community fork of MinIO");
    }
}
