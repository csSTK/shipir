package dev.bethge.shipir.compose;

import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.events.AliasEvent;
import org.yaml.snakeyaml.events.CollectionEndEvent;
import org.yaml.snakeyaml.events.CollectionStartEvent;
import org.yaml.snakeyaml.events.Event;
import org.yaml.snakeyaml.events.MappingStartEvent;
import org.yaml.snakeyaml.events.ScalarEvent;

import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Finds paths in a Compose file that Compose resolves against the project's working directory: bind mount sources,
 * {@code env_file}, build contexts and {@code file:} of top-level secrets/configs. An adopted project is stored under
 * Shipir's stacks directory, so such paths would silently point somewhere else on the next deploy.
 *
 * <p>Heuristic on the raw YAML (no interpolation, no {@code docker compose config}, which would hide the original form):
 * <ul>
 *   <li>A path is relative when it is not absolute (leading {@code /}) and does not start with {@code $} (a variable we
 *       cannot resolve, so it is not flagged) or {@code ~}.</li>
 *   <li>Short volume {@code src:dst[:mode]}: the source is the part before the first colon. A source without any slash
 *       and not {@code .}/{@code ..} is a named volume; anything else that is not absolute is a relative bind mount.</li>
 *   <li>Long volume form: {@code type: bind} with a {@code source}; here any non-absolute source is a path (a
 *       slash-less {@code data} is a relative directory, not a named volume).</li>
 *   <li>{@code build}: a string, or {@code context} (a map without {@code context} defaults to {@code .}), plus local
 *       {@code additional_contexts}; remote contexts (URL, {@code git@}, {@code github.com/}, {@code service:}) are skipped.</li>
 *   <li>Path keys count on services and in {@code x-*} blocks (scanned where defined, since merge keys pull them into
 *       services), never inside {@code environment}/{@code labels}/{@code annotations}. Jackson does not expand aliases, so a second pass over the SnakeYAML node graph refuses an alias at any depth
 *       inside {@code volumes}, {@code env_file}, {@code build} or top-level {@code secrets}/{@code configs}: its target cannot be read from the
 *       tree, so adoption fails closed ("expand it first").</li>
 *   <li>{@code env_file}: string, list of strings or list of {@code {path: ...}}.</li>
 * </ul>
 * Not covered: {@code include}, {@code extends.file}, paths built from variables. Unparsable YAML yields no findings; the
 * Compose validation reports it.
 */
final class RelativePathDetector {

    private static final YAMLMapper YAML = YAMLMapper.builder().build();
    /** Free-form or non-path subtrees whose keys may be named like Compose keys (e.g. {@code environment: {build: x}}). */
    private static final Set<String> FREE_FORM_KEYS = Set.of("environment", "labels", "annotations");
    private static final Set<String> SKIPPED_KEYS = Set.of("environment", "labels", "annotations", "volumes", "env_file", "build");
    private static final Pattern NAMED_VOLUME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]*");

    private RelativePathDetector() { }

    /** Offending entries as {@code service.kind: path}, in file order; empty when the file is self-contained. */
    static List<String> find(String yaml) {
        JsonNode root;
        try {
            root = YAML.readTree(yaml);
        } catch (RuntimeException e) {
            return List.of();
        }
        List<String> found = new ArrayList<>();
        if (root == null || !root.isObject()) {
            return found;
        }
        root.properties().forEach(entry -> {
            if ("services".equals(entry.getKey()) && entry.getValue().isObject()) {
                entry.getValue().properties().forEach(svc -> checkKeys(svc.getKey(), svc.getValue(), found));
            } else if (entry.getKey().startsWith("x-")) {
                scan(entry.getKey(), entry.getValue(), found);
            }
        });
        for (String section : List.of("secrets", "configs")) {
            JsonNode top = root.path(section);
            if (top.isObject()) {
                top.properties().forEach(entry -> {
                    String file = text(entry.getValue().path("file"));
                    if (isRelative(file)) {
                        found.add(section + "." + entry.getKey() + " file: " + file);
                    }
                });
            }
        }
        // Jackson reads an alias value as the anchor name, so any "path" found next to an alias is unreliable; the alias
        // findings alone already make adoption fail closed, and the user sees the real paths after expanding them.
        List<String> aliasFindings = new ArrayList<>();
        aliases(yaml, aliasFindings);
        if (!aliasFindings.isEmpty()) {
            return aliasFindings;
        }
        return found;
    }

    /** Path keys directly on a service (or an object that is merged into one). */
    private static void checkKeys(String label, JsonNode node, List<String> found) {
        if (!node.isObject()) {
            return;
        }
        if (node.path("volumes").isArray()) {
            volumes(label, node.path("volumes"), found);
        }
        if (node.has("env_file")) {
            envFile(label, node.path("env_file"), found);
        }
        if (node.has("build")) {
            build(label, node.path("build"), found);
        }
    }

    /** Extension blocks ({@code x-*}) are scanned where they are defined, since services may merge them in. */
    private static void scan(String label, JsonNode node, List<String> found) {
        if (node.isObject()) {
            checkKeys(label, node, found);
            node.properties().forEach(entry -> {
                if (!SKIPPED_KEYS.contains(entry.getKey())) {
                    scan(entry.getKey(), entry.getValue(), found);
                }
            });
        } else if (node.isArray()) {
            node.forEach(child -> scan(label, child, found));
        }
    }

    private static void volumes(String name, JsonNode volumes, List<String> found) {
        for (JsonNode volume : volumes) {
            String source = null;
            if (volume.isString()) {
                String value = volume.asString();
                int colon = value.indexOf(':');
                String candidate = colon < 0 ? null : value.substring(0, colon);
                if (candidate != null && isRelative(candidate) && !isNamedVolume(candidate)) {
                    source = candidate;
                }
            } else if (volume.isObject() && "bind".equals(text(volume.path("type")))) {
                String candidate = text(volume.path("source"));
                if (isRelative(candidate)) {
                    source = candidate;
                }
            }
            if (source != null) {
                found.add(name + " bind mount: " + source);
            }
        }
    }

    private static void envFile(String name, JsonNode envFiles, List<String> found) {
        for (JsonNode envFile : iterable(envFiles)) {
            String path = envFile.isObject() ? text(envFile.path("path")) : text(envFile);
            if (isRelative(path)) {
                found.add(name + " env_file: " + path);
            }
        }
    }

    private static void build(String name, JsonNode build, List<String> found) {
        if (build.isObject()) {
            String context = build.has("context") ? text(build.path("context")) : ".";
            if (isRelative(context) && !isRemote(context)) {
                found.add(name + " build context: " + context);
            }
            JsonNode additional = build.path("additional_contexts");
            if (additional.isObject()) {
                additional.properties().forEach(e -> additionalContext(name, text(e.getValue()), found));
            } else if (additional.isArray()) {
                for (JsonNode item : additional) {
                    String value = text(item);
                    int eq = value == null ? -1 : value.indexOf('=');
                    additionalContext(name, eq < 0 ? null : value.substring(eq + 1), found);
                }
            }
        } else {
            String context = text(build);
            if (isRelative(context) && !isRemote(context)) {
                found.add(name + " build context: " + context);
            }
        }
    }

    private static void additionalContext(String name, String value, List<String> found) {
        if (isRelative(value) && !isRemote(value) && !value.startsWith("service:")) {
            found.add(name + " build additional context: " + value);
        }
    }

    /**
     * Second pass on the parser events, where aliases are still visible (Jackson reads an alias as its anchor name).
     * An alias at any depth inside a path subtree is refused: {@code volumes}/{@code env_file}/{@code build} of a service
     * or extension block, and the top-level {@code secrets}/{@code configs}.
     */
    private static void aliases(String yaml, List<String> found) {
        List<Frame> stack = new ArrayList<>();
        try {
            for (Event event : new Yaml().parse(new StringReader(yaml))) {
                Frame top = stack.isEmpty() ? null : stack.get(stack.size() - 1);
                if (event instanceof AliasEvent) {
                    reportAlias(stack, found);
                    valueDone(top);
                } else if (event instanceof ScalarEvent scalar) {
                    if (top != null && top.mapping && top.expectKey) {
                        top.key = scalar.getValue();
                        top.expectKey = false;
                    } else {
                        valueDone(top);
                    }
                } else if (event instanceof CollectionStartEvent start) {
                    stack.add(new Frame(start instanceof MappingStartEvent));
                } else if (event instanceof CollectionEndEvent) {
                    stack.remove(stack.size() - 1);
                    valueDone(stack.isEmpty() ? null : stack.get(stack.size() - 1));
                }
            }
        } catch (RuntimeException e) {
            // Jackson parsed this file, so failing here must not read as "no aliases": refuse instead of failing open
            found.add("the file could not be checked for YAML aliases; simplify it or remove anchors and aliases");
        }
    }

    private static void reportAlias(List<Frame> stack, List<String> found) {
        String section = stack.isEmpty() ? null : stack.get(0).key;
        for (int depth = 0; depth < stack.size(); depth++) {
            Frame frame = stack.get(depth);
            String key = frame.key;
            // sequences have no key; a free-form subtree (environment, labels, ...) holds no paths
            if (!frame.mapping || frame.expectKey || key == null) {
                continue;
            }
            if (FREE_FORM_KEYS.contains(key)) {
                return;
            }
            // depth 0 holds the top-level sections. Below them only services.<name>.* (depth 2) and x-* blocks carry
            // path keys; at depth 1 elsewhere the key is a service/network/volume NAME, not a Compose key.
            boolean serviceKey = depth == 2 && "services".equals(section);
            boolean extensionKey = depth >= 1 && section != null && section.startsWith("x-");
            boolean path = switch (key) {
                case "env_file", "build", "volumes" -> serviceKey || extensionKey;
                case "secrets", "configs" -> depth == 0;
                default -> false;
            };
            if (path) {
                String label = depth == 0 || stack.get(depth - 1).key == null ? "document" : stack.get(depth - 1).key;
                found.add(label + " uses a YAML alias for " + key + "; expand it first");
                return;
            }
        }
    }

    private static void valueDone(Frame frame) {
        if (frame != null && frame.mapping) {
            frame.expectKey = true;
        }
    }

    private static final class Frame {
        final boolean mapping;
        boolean expectKey = true;
        String key;

        Frame(boolean mapping) {
            this.mapping = mapping;
        }
    }

    private static Iterable<JsonNode> iterable(JsonNode node) {
        if (node.isArray()) {
            return node;
        }
        return node.isMissingNode() || node.isNull() ? List.of() : List.of(node);
    }

    private static String text(JsonNode node) {
        return node.isString() ? node.asString() : null;
    }

    private static boolean isRelative(String path) {
        return path != null && !path.isBlank() && !path.startsWith("/") && !path.startsWith("$") && !path.startsWith("~");
    }

    private static boolean isNamedVolume(String source) {
        return !source.equals(".") && !source.equals("..") && NAMED_VOLUME.matcher(source).matches();
    }

    private static boolean isRemote(String context) {
        return context.contains("://") || context.startsWith("git@") || context.startsWith("github.com/");
    }
}
