package dev.bethge.shipir.compose.validation;

import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.api.lowlevel.Parse;
import org.snakeyaml.engine.v2.events.AliasEvent;
import org.snakeyaml.engine.v2.events.Event;
import org.snakeyaml.engine.v2.events.MappingEndEvent;
import org.snakeyaml.engine.v2.events.MappingStartEvent;
import org.snakeyaml.engine.v2.events.ScalarEvent;
import org.snakeyaml.engine.v2.events.SequenceEndEvent;
import org.snakeyaml.engine.v2.events.SequenceStartEvent;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Keeps non-ADMIN compose files from making the Shipir server read files outside the deployment directory (compose runs
 * on the Shipir host, not on the target host): {@code env_file} (all forms), {@code include} (path, env_file,
 * project_directory), {@code extends.file} and {@code secrets}/{@code configs} {@code file}. Works on the raw YAML because
 * compose inlines env_file contents into the resolved config, so those paths are not visible in
 * {@code config --format json}. Local included files are followed (paths relative to the including file). Paths are
 * resolved with symlinks; variable interpolation, {@code ~} and URLs are rejected because their value cannot be known here.
 * The whole tree is walked, so anchors defined anywhere and merge keys of mappings ({@code <<: *x}) cannot hide an
 * entry (conservative: an {@code env_file} key anywhere counts). Jackson does not expand aliases used as values (it
 * yields the anchor name), so {@link #aliasViolations} rejects, at YAML event level, every alias used as a value inside
 * or under {@code env_file}/{@code include}/{@code extends}/{@code secrets}/{@code configs}/{@code file}/{@code path}/
 * {@code project_directory}, and any merge key in such a context or at the document root. Aliases elsewhere
 * ({@code image: *img}, {@code <<: *defaults} in a service) stay allowed.
 */
final class ComposeFilePolicy {

    private static final int MAX_DEPTH = 8;
    private static final YAMLMapper YAML = YAMLMapper.builder().build();

    private final Path allowed;
    private final boolean failClosed;
    private final List<String> violations = new ArrayList<>();
    private final Set<Path> visited = new HashSet<>();

    private ComposeFilePolicy(Path allowed, boolean failClosed) {
        this.allowed = allowed;
        this.failClosed = failClosed;
    }

    /**
     * @param composeFile the compose file (inside {@code allowedDir})
     * @param allowedDir  the deployment directory every referenced file must stay in
     * @param failClosed  report a file that cannot be parsed as a violation (false = skip it; compose will reject it itself)
     */
    static List<String> violations(Path composeFile, Path allowedDir, boolean failClosed) {
        Path realAllowed = ComposeSecurityPolicy.real(allowedDir);
        if (realAllowed == null) {
            return List.of("deployment directory cannot be resolved");
        }
        ComposeFilePolicy policy = new ComposeFilePolicy(realAllowed, failClosed);
        policy.scan(composeFile.toAbsolutePath().normalize(), 0);
        return policy.violations;
    }

    private void scan(Path file, int depth) {
        Path real = ComposeSecurityPolicy.real(file);
        if (real == null || !visited.add(real)) {
            return;
        }
        if (depth > MAX_DEPTH) {
            violations.add("include nesting too deep");
            return;
        }
        JsonNode root;
        try {
            String text = Files.readString(file);
            java.util.Optional<List<String>> aliases = aliasViolations(text);
            aliases.ifPresent(list -> list.forEach(a -> violations.add(display(file) + ": " + a)));
            root = YAML.readTree(text);
            if (aliases.isEmpty()) {
                // the event pass failed although Jackson parsed the file: never pass what could not be checked
                violations.add(display(file) + ": YAML could not be checked for aliases");
            }
        } catch (IOException | RuntimeException e) {
            if (failClosed && Files.exists(file)) {
                violations.add("compose file " + display(file) + " cannot be analysed");
            }
            return;
        }
        walk(root, file.getParent(), file, depth, true);
    }

    private static final Set<String> PATH_KEYS = Set.of("env_file", "label_file", "include", "extends", "secrets",
            "configs", "file", "path", "project_directory");

    private static final String MERGE_TAG = "tag:yaml.org,2002:merge";

    private static final class Frame {
        final boolean mapping;
        final boolean sensitive;
        final boolean root;
        int children;
        String lastKey = "";

        Frame(boolean mapping, boolean sensitive, boolean root) {
            this.mapping = mapping;
            this.sensitive = sensitive;
            this.root = root;
        }

        boolean inValuePosition() {
            return mapping && children % 2 == 1;
        }
    }

    /**
     * Constructs a file-path check cannot see through (see class doc). Conservative: reject an alias that is a value of a
     * path-relevant key, nested anywhere below one, or a mapping key; and reject any merge key ({@code <<}, in any form:
     * alias, inline mapping, list, nested) directly in the root mapping or below a path-relevant key, since merged
     * {@code include}/{@code secrets}/{@code configs}/{@code file}/{@code path} entries would not be checked at their usual position.
     * Returns empty when the event pass itself fails; the caller then decides (violation if Jackson parsed the file).
     */
    static java.util.Optional<List<String>> aliasViolations(String yaml) {
        List<String> found = new ArrayList<>();
        Deque<Frame> stack = new ArrayDeque<>();
        try {
            for (Event event : new Parse(LoadSettings.builder().build()).parseString(yaml)) {
                if (event instanceof MappingStartEvent || event instanceof SequenceStartEvent) {
                    Frame parent = stack.peek();
                    boolean value = parent != null && parent.inValuePosition();
                    boolean sensitive = parent != null && (parent.sensitive || (value && PATH_KEYS.contains(parent.lastKey)));
                    boolean root = parent == null && event instanceof MappingStartEvent;
                    if (parent != null) {
                        parent.children++;
                    }
                    stack.push(new Frame(event instanceof MappingStartEvent, sensitive, root));
                } else if (event instanceof MappingEndEvent || event instanceof SequenceEndEvent) {
                    stack.pop();
                } else if (event instanceof AliasEvent alias) {
                    Frame parent = stack.peek();
                    if (parent != null) {
                        boolean value = parent.inValuePosition();
                        boolean bad = parent.sensitive
                                || (parent.mapping && !value) // alias as a mapping key could spell "env_file"
                                || (value && PATH_KEYS.contains(parent.lastKey));
                        if (bad) {
                            found.add("YAML alias *" + alias.getAlias() + " is not allowed here (file paths must be literal)");
                        }
                        parent.children++;
                    }
                } else if (event instanceof ScalarEvent scalar) {
                    Frame parent = stack.peek();
                    if (parent != null) {
                        if (parent.mapping && parent.children % 2 == 0) {
                            parent.lastKey = scalar.getValue();
                            if ((parent.root || parent.sensitive)
                                    && ("<<".equals(scalar.getValue()) || scalar.getTag().filter(MERGE_TAG::equals).isPresent())) {
                                found.add("merge keys at the top level or under file-path keys are not allowed here");
                            }
                        }
                        parent.children++;
                    }
                }
            }
        } catch (RuntimeException e) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(found);
    }

    private void walk(JsonNode node, Path baseDir, Path file, int depth, boolean top) {
        if (node.isArray()) {
            node.forEach(child -> walk(child, baseDir, file, depth, false));
            return;
        }
        if (!node.isObject()) {
            return;
        }
        node.propertyStream().forEach(entry -> {
            String key = entry.getKey();
            JsonNode value = entry.getValue();
            switch (key) {
                case "env_file" -> pathsOf(value).forEach(p -> check(p, baseDir, "env_file"));
                case "label_file" -> pathsOf(value).forEach(p -> check(p, baseDir, "label_file"));
                case "provider" -> {
                    // a provider service makes compose execute a binary (or plugin) on the Shipir host (Compose 2.36+)
                    if (value.isObject() && value.has("type")) {
                        violations.add("provider services are not allowed (they run an executable on the Shipir host)");
                    }
                }
                case "extends" -> {
                    if (value.isObject() && value.has("file")) {
                        pathsOf(value.get("file")).forEach(p -> follow(checkLocalSource(p, baseDir, "extends file"), file, depth));
                    }
                }
                case "include" -> {
                    if (top) {
                        include(value, baseDir, file, depth);
                    }
                }
                case "secrets", "configs" -> {
                    if (top && value.isObject()) {
                        value.forEach(def -> pathsOf(def.path("file")).forEach(p -> check(p, baseDir, key + " file")));
                    }
                }
                default -> { }
            }
            walk(value, baseDir, file, depth, false);
        });
    }

    private void include(JsonNode include, Path baseDir, Path file, int depth) {
        for (JsonNode entry : include.isArray() ? include : List.of(include)) {
            if (entry.isTextual()) {
                follow(checkLocalSource(entry.asString(), baseDir, "include"), file, depth);
            } else if (entry.isObject()) {
                pathsOf(entry.get("path")).forEach(p -> follow(checkLocalSource(p, baseDir, "include"), file, depth));
                // an include entry's env_file is covered by the generic env_file check in walk()
                pathsOf(entry.get("project_directory")).forEach(p -> check(p, baseDir, "include project_directory"));
            }
        }
    }

    /**
     * For {@code include} and {@code extends.file} Compose treats {@code oci://}, {@code git@host:x/y}, {@code https://...}
     * and the {@code github.com/x/y} shorthand as remote sources and fetches them from the network on the Shipir host.
     * Only plain local paths are allowed: no {@code :} and no {@code github.com/} prefix. Verified with
     * {@code docker compose config} (v5.1.3): {@code github.com/...} is the only host-like form Compose treats as remote;
     * {@code conf.d/a.yaml}, {@code my.app/base.yaml}, {@code gitlab.com/x/y.yaml} and {@code ghcr.io/x/y} are plain local paths
     * (deliberately no longer rejected). {@code GitHub.com/} and {@code ./github.com/} are rejected fail-closed although
     * Compose treats them as local. A broader
     * "dot in first segment" heuristic would wrongly reject those local paths.
     */
    private Path checkLocalSource(String raw, Path baseDir, String what) {
        String first = raw.startsWith("./") ? raw.substring(2) : raw;
        boolean githubShorthand = first.regionMatches(true, 0, "github.com/", 0, "github.com/".length());
        if (raw.contains(":") || githubShorthand) {
            violations.add(what + " " + raw + ": remote sources are not allowed");
            return null;
        }
        return check(raw, baseDir, what);
    }

    private void follow(Path included, Path from, int depth) {
        if (included != null && Files.isRegularFile(included)) {
            scan(included, depth + 1);
        }
    }

    /** Text values of a string, a list of strings, or the {@code path} of an object / list of objects. */
    private static List<String> pathsOf(JsonNode node) {
        List<String> paths = new ArrayList<>();
        if (node == null) {
            return paths;
        }
        if (node.isTextual()) {
            paths.add(node.asString());
        } else if (node.isArray()) {
            node.forEach(child -> paths.addAll(pathsOf(child)));
        } else if (node.isObject() && node.has("path")) {
            paths.addAll(pathsOf(node.get("path")));
        }
        return paths;
    }

    /** Records a violation unless the path stays in the allowed directory; returns the resolved path when it does. */
    private Path check(String raw, Path baseDir, String what) {
        if (raw.isBlank()) {
            return null;
        }
        if (raw.contains("$") || raw.startsWith("~") || raw.contains("://")) {
            violations.add(what + " " + raw + ": variables, ~ and URLs are not allowed");
            return null;
        }
        Path resolved;
        try {
            resolved = ComposeSecurityPolicy.real(baseDir.resolve(raw));
        } catch (RuntimeException e) {
            violations.add(what + " " + raw + ": invalid path");
            return null;
        }
        if (resolved == null || !resolved.startsWith(allowed)) {
            violations.add(what + " " + raw + ": outside the deployment directory");
            return null;
        }
        return resolved;
    }

    private String display(Path file) {
        Path real = ComposeSecurityPolicy.real(file);
        return real != null && real.startsWith(allowed) ? allowed.relativize(real).toString() : file.getFileName().toString();
    }
}
