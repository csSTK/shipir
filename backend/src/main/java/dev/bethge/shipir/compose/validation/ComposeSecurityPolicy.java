package dev.bethge.shipir.compose.validation;

import dev.bethge.shipir.compose.execution.ComposeCommand;
import dev.bethge.shipir.compose.execution.ComposeExecutionException;
import dev.bethge.shipir.compose.execution.ComposeExecutionService;
import dev.bethge.shipir.compose.execution.ComposeOptions;
import dev.bethge.shipir.compose.execution.ComposeProcessResult;
import dev.bethge.shipir.compose.file.ComposeFileRepository;
import dev.bethge.shipir.docker.DockerEndpoint;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * The central gate that keeps non-ADMIN users from reaching host root through Compose. It mirrors the rules of manual
 * container creation (privileged, host networking, bind mounts need ADMIN) and evaluates the parsed output of
 * {@code docker compose config --format json}, never the raw text, so anchors, {@code extends} and {@code include} cannot
 * hide a setting. Denied for non-ADMIN: {@code privileged}, {@code cap_add}, {@code pid}/{@code ipc}/{@code network_mode}
 * {@code host}, {@code devices}, {@code security_opt}, {@code userns_mode}, bind mounts outside the deployment directory
 * (including the Docker socket), {@code use_api_socket}, {@code uts}/{@code cgroup} host, {@code cgroup_parent},
 * {@code device_cgroup_rules}, {@code gpus}, device reservations, {@code container:} namespaces, {@code volumes_from}, build
 * {@code privileged}/{@code network: host}/{@code entitlements} and build {@code context}/{@code dockerfile}/{@code additional_contexts}
 * outside the directory ({@code sysctls} and {@code group_add} are allowed by decision), and named volumes that are bind mounts or network filesystems (nfs, nfs4, cifs, smbfs) in disguise ({@code driver_opts}), plus {@code secrets}/{@code configs} files outside the
 * deployment directory and (see {@link ComposeFilePolicy}) {@code env_file}/{@code include}/{@code extends} paths outside it. Named volumes
 * and paths inside the deployment directory stay allowed. Callers decide who is ADMIN; this class only evaluates.
 */
@Service
public class ComposeSecurityPolicy {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final ComposeExecutionService execution;
    private final ComposeFileRepository files;

    public ComposeSecurityPolicy(ComposeExecutionService execution, ComposeFileRepository files) {
        this.execution = execution;
        this.files = files;
    }

    /**
     * Runs {@code docker compose --profile * config --format json} (services behind a profile are only part of the output
     * when their profile is enabled, and any run may enable them) for the stored deployment and returns the violations (empty = allowed).
     *
     * @param composeFile compose file inside the deployment directory (Git checkout); null = the default file
     * @throws ComposeExecutionException if compose cannot run or its output cannot be read (fail closed)
     */
    public List<String> violations(UUID hostId, String deploymentName, DockerEndpoint endpoint, Path composeFile) {
        ComposeProcessResult result = execution.execute(hostId, deploymentName, ComposeCommand.CONFIG_JSON, endpoint,
                line -> { }, composeFile, null, ComposeOptions.ofProfiles(List.of(ComposeOptions.ALL_PROFILES)));
        if (!result.success()) {
            throw new ComposeExecutionException("docker compose config failed", null);
        }
        Path allowedDir = files.deploymentDir(hostId, deploymentName);
        List<String> violations = new ArrayList<>(violationsOf(parse(result.output()), allowedDir));
        violations.addAll(ComposeFilePolicy.violations(composeFileOf(allowedDir, composeFile), allowedDir, true));
        return violations;
    }

    /**
     * Cheap check of the raw compose YAML (and the local files it includes) for paths outside the deployment directory.
     * Runs before any {@code docker compose} call so compose never opens such a file for a non-ADMIN author. Unparsable
     * YAML is skipped here (compose rejects it itself); {@link #violations} re-checks strictly after compose accepted it.
     */
    public List<String> fileViolations(UUID hostId, String deploymentName, Path composeFile) {
        Path allowedDir = files.deploymentDir(hostId, deploymentName);
        return ComposeFilePolicy.violations(composeFileOf(allowedDir, composeFile), allowedDir, false);
    }

    private static Path composeFileOf(Path deploymentDir, Path composeFile) {
        return composeFile != null ? composeFile : deploymentDir.resolve(ComposeFileRepository.COMPOSE_FILE);
    }

    /**
     * Reads the config object out of the merged stdout/stderr lines. Every line starting with {@code {} is tried as the
     * start; only a complete JSON object that runs to the end of the output (whitespace aside) and looks like a compose
     * config (an object with {@code name}, which {@code config --format json} always emits) is accepted, so a decoy
     * {@code {}} line or trailing noise cannot hide the real config. Anything else fails closed.
     */
    static JsonNode parse(List<String> output) {
        for (int start = 0; start < output.size(); start++) {
            if (!output.get(start).startsWith("{")) {
                continue;
            }
            JsonNode root = tryParse(String.join("\n", output.subList(start, output.size())));
            if (root != null && root.isObject() && root.has("name") && (!root.has("services") || root.get("services").isObject())) {
                return root;
            }
        }
        throw new ComposeExecutionException("docker compose config output is not a compose configuration", null);
    }

    private static JsonNode tryParse(String text) {
        try (tools.jackson.core.JsonParser parser = JSON.createParser(text)) {
            JsonNode node = parser.readValueAsTree();
            return parser.nextToken() == null ? node : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Pure evaluation of a resolved compose configuration; {@code allowedDir} is where bind mounts may point. */
    static List<String> violationsOf(JsonNode config, Path allowedDir) {
        List<String> violations = new ArrayList<>();
        Path allowed = real(allowedDir);
        if (allowed == null) {
            throw new ComposeExecutionException("Deployment directory cannot be resolved", null);
        }
        config.path("services").propertyStream().forEach(entry -> {
            String service = entry.getKey();
            JsonNode s = entry.getValue();
            if (s.path("privileged").asBoolean(false)) {
                violations.add(service + ": privileged");
            }
            if (nonEmpty(s.path("cap_add"))) {
                violations.add(service + ": cap_add");
            }
            for (String key : List.of("pid", "ipc", "uts", "cgroup", "network_mode")) {
                String mode = s.path(key).asString("");
                if ("host".equals(mode)) {
                    violations.add(service + ": " + key + " host");
                } else if (CONTAINER_NAMESPACE_KEYS.contains(key) && mode.startsWith("container:")) {
                    violations.add(service + ": " + key + " container:");
                }
            }
            for (String key : NON_EMPTY_DENIED_KEYS) {
                if (nonEmpty(s.path(key))) {
                    violations.add(service + ": " + key);
                }
            }
            if (s.path("use_api_socket").asBoolean(false)) {
                violations.add(service + ": use_api_socket");
            }
            if (nonEmpty(s.path("deploy").path("resources").path("reservations").path("devices"))) {
                violations.add(service + ": deploy reservations devices");
            }
            buildViolations(service, s.path("build"), allowed, violations);
            if (nonEmpty(s.path("devices"))) {
                violations.add(service + ": devices");
            }
            if (nonEmpty(s.path("security_opt"))) {
                violations.add(service + ": security_opt");
            }
            if (nonEmpty(s.path("userns_mode"))) {
                violations.add(service + ": userns_mode");
            }
            for (JsonNode volume : s.path("volumes")) {
                if (!"bind".equals(volume.path("type").asString(""))) {
                    continue;
                }
                String source = volume.path("source").asString("");
                if (source.contains("docker.sock")) {
                    violations.add(service + ": docker.sock mount");
                } else if (!insideOf(allowed, source)) {
                    violations.add(service + ": bind mount " + source);
                }
            }
        });
        for (String section : List.of("secrets", "configs")) {
            config.path(section).propertyStream().forEach(entry -> {
                String file = entry.getValue().path("file").asString("");
                if (!file.isEmpty() && !insideOf(allowed, file)) {
                    violations.add(section.substring(0, section.length() - 1) + " " + entry.getKey() + ": file " + file);
                }
            });
        }
        config.path("volumes").propertyStream().forEach(entry -> {
            JsonNode opts = entry.getValue().path("driver_opts");
            String type = opts.path("type").asString("");
            String options = opts.path("o").asString("");
            if ("none".equals(type) || "bind".equals(type) || options.contains("bind")) {
                violations.add("volume " + entry.getKey() + ": bind mount via driver_opts");
            } else if (NETWORK_FILESYSTEM_TYPES.contains(type.strip().toLowerCase(Locale.ROOT))) {
                violations.add("volume " + entry.getKey() + ": network filesystem via driver_opts");
            }
        });
        return violations;
    }

    /** Filesystem types of the local volume driver that mount remote shares from the Shipir host (denied by decision, shipr-kqbc.4). */
    private static final Set<String> NETWORK_FILESYSTEM_TYPES = Set.of("nfs", "nfs4", "cifs", "smbfs");

    /** Namespace modes that may also join another container ({@code container:<id>}, possibly one outside Shipir's control). */
    private static final Set<String> CONTAINER_NAMESPACE_KEYS = Set.of("pid", "ipc", "network_mode");

    /** Keys that are denied as soon as they carry a value (sysctls and group_add are deliberately allowed). */
    private static final List<String> NON_EMPTY_DENIED_KEYS = List.of("cgroup_parent", "device_cgroup_rules", "gpus", "volumes_from");

    /**
     * Build section of a service. Compose resolves {@code context} and {@code additional_contexts} paths to absolute ones
     * (checked with the same inside-directory rule as bind mounts); a relative {@code dockerfile} is relative to the context.
     * Additional contexts that reference an image or a service are not file reads and stay allowed.
     */
    private static void buildViolations(String service, JsonNode build, Path allowed, List<String> violations) {
        if (!build.isObject()) {
            return;
        }
        if (build.path("privileged").asBoolean(false)) {
            violations.add(service + ": build privileged");
        }
        if ("host".equals(build.path("network").asString(""))) {
            violations.add(service + ": build network host");
        }
        if (nonEmpty(build.path("entitlements"))) {
            violations.add(service + ": build entitlements");
        }
        if (nonEmpty(build.path("ssh"))) {
            violations.add(service + ": build ssh");
        }
        String context = build.path("context").asString("");
        if (!context.isEmpty() && !insideOf(allowed, context)) {
            violations.add(service + ": build context " + context);
        }
        String dockerfile = build.path("dockerfile").asString("");
        if (!dockerfile.isEmpty() && !insideOf(allowed, resolveAgainst(context, dockerfile))) {
            violations.add(service + ": build dockerfile " + dockerfile);
        }
        build.path("additional_contexts").propertyStream().forEach(entry -> {
            String value = entry.getValue().asString("");
            if (!value.startsWith("docker-image://") && !value.startsWith("service:") && !insideOf(allowed, value)) {
                violations.add(service + ": build additional_contexts " + entry.getKey() + " " + value);
            }
        });
    }

    private static String resolveAgainst(String context, String relativeOrAbsolute) {
        try {
            if (Path.of(relativeOrAbsolute).isAbsolute() || context.isEmpty()) {
                return relativeOrAbsolute;
            }
            // no lexical normalize: 'link/..' must be resolved through the symlink by real()
            return Path.of(context).resolve(relativeOrAbsolute).toString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static boolean nonEmpty(JsonNode node) {
        return !node.isMissingNode() && !node.isNull() && !((node.isArray() || node.isObject()) ? node.isEmpty() : node.asString("").isBlank());
    }

    /** True when {@code source} is an absolute path inside {@code allowed} (symlinks resolved where the path exists). */
    private static boolean insideOf(Path allowed, String source) {
        Path p;
        try {
            p = Path.of(source);
        } catch (RuntimeException e) {
            return false;
        }
        Path resolved = p.isAbsolute() ? real(p) : null;
        return resolved != null && resolved.startsWith(allowed);
    }

    /**
     * Symlink-resolved absolute path, or null when it cannot be resolved. Components are walked one by one so that
     * {@code ..} after a symlink goes to the link target's parent (as the OS does), not to the link's lexical parent.
     */
    static Path real(Path path) {
        try {
            Path current = path.getRoot() != null ? path.getRoot() : Path.of("/");
            if (path.getRoot() == null) {
                path = path.toAbsolutePath();
                current = path.getRoot();
            }
            for (Path part : path) {
                String name = part.toString();
                if (name.equals(".") || name.isEmpty()) {
                    continue;
                }
                if (name.equals("..")) {
                    Path parent = current.getParent();
                    current = parent == null ? current : parent;
                    continue;
                }
                Path next = current.resolve(name);
                // a symlink (or a path below one) is resolved right away; missing components are appended as-is
                if (Files.exists(next, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    next = next.toRealPath();
                }
                current = next;
            }
            return current.normalize();
        } catch (IOException e) {
            return null; // unresolvable (e.g. symlink loop): fail closed
        }
    }
}
