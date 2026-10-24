package dev.bethge.shipir.compose.execution;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Locates the Docker CLI plugin directories (where {@code docker-compose} lives). Needed because the CLI only looks in
 * {@code $DOCKER_CONFIG/cli-plugins} plus the {@code cliPluginsExtraDirs} of its config.json and the system dirs; a temp
 * {@code DOCKER_CONFIG} would otherwise hide the plugin.
 */
final class CliPluginDirs {

    /** System-wide plugin dirs the Docker CLI searches by default (Unix). */
    static final List<Path> SYSTEM_DIRS = List.of(
            Path.of("/usr/local/lib/docker/cli-plugins"), Path.of("/usr/local/libexec/docker/cli-plugins"),
            Path.of("/usr/lib/docker/cli-plugins"), Path.of("/usr/libexec/docker/cli-plugins"));

    private CliPluginDirs() {
    }

    /**
     * Existing plugin dirs, in order: {@code <config>/cli-plugins} of the original config dir ({@code DOCKER_CONFIG} from
     * {@code env}, else {@code <home>/.docker}), the {@code cliPluginsExtraDirs} of its config.json, then {@code systemDirs}.
     * A missing or unreadable config.json is ignored.
     */
    static List<String> resolve(Map<String, String> env, Path home, List<Path> systemDirs) {
        String configured = env.get("DOCKER_CONFIG");
        Path configDir = configured != null && !configured.isBlank() ? Path.of(configured) : home.resolve(".docker");
        List<Path> candidates = new ArrayList<>();
        candidates.add(configDir.resolve("cli-plugins"));
        candidates.addAll(extraDirs(configDir.resolve("config.json")));
        candidates.addAll(systemDirs);
        Set<String> result = new LinkedHashSet<>();
        for (Path dir : candidates) {
            if (Files.isDirectory(dir)) {
                result.add(dir.toString());
            }
        }
        return List.copyOf(result);
    }

    private static List<Path> extraDirs(Path configFile) {
        if (!Files.isRegularFile(configFile)) {
            return List.of();
        }
        try {
            JsonNode extra = new JsonMapper().readTree(Files.readString(configFile)).path("cliPluginsExtraDirs");
            List<Path> dirs = new ArrayList<>();
            for (JsonNode node : extra) {
                if (node.isTextual() && !node.asText().isBlank()) {
                    dirs.add(Path.of(node.asText()));
                }
            }
            return dirs;
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }
}
