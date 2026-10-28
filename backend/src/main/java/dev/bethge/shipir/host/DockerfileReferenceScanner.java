package dev.bethge.shipir.host;

import dev.bethge.shipir.common.net.EgressPolicy;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.springframework.http.HttpStatus;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Finds the images a Dockerfile makes the daemon pull ({@code FROM}, {@code COPY/ADD --from=<image>},
 * {@code RUN --mount=...,from=<image>}) and applies the shared egress policy to their registries, so a build cannot reach
 * an internal registry with the stored logins (same rule and error as pull and push).
 *
 * <p>This is deliberately not a Dockerfile interpreter. It follows the parts that decide which image is named:
 * parser directive {@code escape}, comment lines, line continuations (also across comments), case-insensitive
 * instructions, flags, multi-stage names and indexes, and {@code ARG}/{@code ENV} substitution ({@code $VAR},
 * {@code ${VAR}}, {@code ${VAR:-default}}, {@code ${VAR-default}}; a build argument of the request overrides a declared
 * default). Anything it cannot resolve statically (undeclared variable, {@code ${VAR:?}}, shell syntax, an invalid
 * reference) is refused with the same {@code REGISTRY_NOT_ALLOWED}, never guessed. Messages never echo the Dockerfile.
 */
final class DockerfileReferenceScanner {

    static final int MAX_DOCKERFILE_BYTES = 1_000_000;
    private static final int MAX_EXPANDED = 1024;
    private static final int MAX_NESTING = 4;
    /** Applied to an already stripped line, so no trailing whitespace is left to backtrack over. */
    private static final Pattern DIRECTIVE = Pattern.compile("^#[ \\t]*([A-Za-z][A-Za-z0-9]*)[ \\t]*=[ \\t]*(.+)$");
    private static final Set<String> PLATFORM_ARGS = Set.of("BUILDPLATFORM", "BUILDOS", "BUILDARCH", "BUILDVARIANT",
            "TARGETPLATFORM", "TARGETOS", "TARGETARCH", "TARGETVARIANT");
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern STAGE_NAME = Pattern.compile("[a-z][a-z0-9_.-]*");
    private static final Pattern DIGITS = Pattern.compile("[0-9]+");

    private DockerfileReferenceScanner() {
    }

    /** Throws 400 REGISTRY_NOT_ALLOWED when any image the Dockerfile pulls is not allowed or cannot be resolved. */
    static void requireAllowedReferences(EgressPolicy egress, String dockerfile, Map<String, String> buildArgs) {
        new Run(egress, buildArgs).scan(dockerfile);
    }

    /**
     * The text of {@code dockerfilePath} in an uploaded tar or tar.gz. The last entry of that name wins, like when the
     * daemon extracts the archive. Missing, non-regular (symlink) or oversize Dockerfiles are 400.
     */
    static String readFromArchive(Path archive, String dockerfilePath) {
        String wanted = normalize(dockerfilePath);
        try (BufferedInputStream raw = new BufferedInputStream(Files.newInputStream(archive))) {
            raw.mark(2);
            int first = raw.read();
            int second = raw.read();
            raw.reset();
            boolean gzip = first == 0x1f && second == 0x8b;
            try (InputStream in = gzip ? new GZIPInputStream(raw) : raw;
                 TarArchiveInputStream tar = new TarArchiveInputStream(in)) {
                byte[] found = null;
                boolean seen = false;
                for (TarArchiveEntry e = tar.getNextEntry(); e != null; e = tar.getNextEntry()) {
                    if (!wanted.equals(normalize(e.getName()))) {
                        continue;
                    }
                    seen = true;
                    found = null;
                    if (e.getLinkFlag() == TarConstants.LF_NORMAL || e.getLinkFlag() == TarConstants.LF_OLDNORM) {
                        found = tar.readNBytes(MAX_DOCKERFILE_BYTES + 1);
                        if (found.length > MAX_DOCKERFILE_BYTES) {
                            throw notUsable("DOCKERFILE_TOO_LARGE", "The Dockerfile is too large");
                        }
                    }
                }
                if (found == null) {
                    throw seen ? notUsable("INVALID_DOCKERFILE_PATH", "The Dockerfile must be a regular file in the build context")
                            : notUsable("DOCKERFILE_NOT_FOUND", "The build context contains no Dockerfile at this path");
                }
                return new String(found, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new HostException(HttpStatus.BAD_REQUEST, "UPLOAD_UNREADABLE", "The build context could not be read");
        }
    }

    private static String normalize(String name) {
        try {
            String n = Path.of(name).normalize().toString();
            while (n.startsWith("/")) {
                n = n.substring(1);
            }
            return n;
        } catch (InvalidPathException e) {
            return "\0" + name;
        }
    }

    private static HostException notUsable(String code, String message) {
        return new HostException(HttpStatus.BAD_REQUEST, code, message);
    }

    private static HostException refused() {
        return new HostException(HttpStatus.BAD_REQUEST, "REGISTRY_NOT_ALLOWED",
                "A base image in the Dockerfile is not permitted or cannot be resolved");
    }

    /** A value that cannot be computed without running something. */
    private static final class Unresolvable extends RuntimeException {
        Unresolvable() {
            super(null, null, false, false);
        }
    }

    private static final class Run {
        private final EgressPolicy egress;
        private final Map<String, String> requestArgs;
        /** Global ARGs (before the first FROM); a null value is declared but unresolvable. */
        private final Map<String, String> globals = new HashMap<>();
        private Map<String, String> stage = null;
        private final Set<String> stageNames = new HashSet<>();
        private int stages;

        Run(EgressPolicy egress, Map<String, String> requestArgs) {
            this.egress = egress;
            this.requestArgs = requestArgs == null ? Map.of() : requestArgs;
        }

        void scan(String dockerfile) {
            requireParseableCharacters(dockerfile);
            String source = dockerfile.startsWith("\uFEFF") ? dockerfile.substring(1) : dockerfile;
            String[] lines = source.split("\r?\n", -1);
            char escape = '\\';
            String syntax = null;
            boolean escapeSeen = false;
            int start = 0;
            // parser directives: only at the top. An unknown directive (e.g. "check") may or may not end the block,
            // depending on the BuildKit version, so after one every contiguous leading comment line is inspected and a
            // later syntax/escape directive is refused (fail closed) instead of being guessed.
            boolean unknownSeen = false;
            for (; start < lines.length; start++) {
                String stripped = lines[start].strip();
                Matcher m = DIRECTIVE.matcher(stripped);
                if (!m.matches()) {
                    if (unknownSeen && stripped.startsWith("#")) {
                        continue;
                    }
                    break;
                }
                String key = m.group(1).toLowerCase(Locale.ROOT);
                String value = m.group(2);
                if (key.equals("escape")) {
                    if (unknownSeen || escapeSeen || !(value.equals("`") || value.equals("\\"))) {
                        throw refused();
                    }
                    escapeSeen = true;
                    escape = value.charAt(0);
                } else if (key.equals("syntax")) {
                    if (unknownSeen || syntax != null) {
                        throw refused();
                    }
                    syntax = value;
                } else {
                    unknownSeen = true;
                }
            }
            if (syntax != null) {
                requireAllowedImage(syntax); // BuildKit pulls the frontend image with the stored logins
            }
            for (String line : logicalLines(lines, start, escape)) {
                instruction(line);
            }
        }

        private void instruction(String line) {
            String trimmed = line.strip();
            int space = firstWhitespace(trimmed);
            String keyword = (space < 0 ? trimmed : trimmed.substring(0, space)).toUpperCase(Locale.ROOT);
            List<String> tokens = words(space < 0 ? "" : trimmed.substring(space));
            switch (keyword) {
                case "ARG" -> declareArgs(tokens);
                case "FROM" -> from(tokens);
                case "COPY", "ADD" -> flags(tokens, "--from=", false);
                case "RUN" -> flags(tokens, "--mount=", true);
                default -> { }
            }
        }

        private Map<String, String> vars() {
            return stage == null ? globals : stage;
        }

        /**
         * Only ARGs before the first FROM are tracked: they are all a FROM can see. Inside a stage ENV beats ARG and ENV is
         * inherited from parent stages and base images, so no variable is ever expanded in a stage (see {@link #flags}).
         * With several pairs in one instruction every value is evaluated against the state before it, so a value with
         * {@code $} is not trusted; neither is a name declared again with another value.
         */
        private void declareArgs(List<String> tokens) {
            if (stage != null) {
                return;
            }
            for (String token : tokens) {
                int eq = token.indexOf('=');
                String name = eq < 0 ? token : token.substring(0, eq);
                if (!NAME.matcher(name).matches()) {
                    continue;
                }
                String value;
                if (requestArgs.containsKey(name)) {
                    value = requestArgs.get(name);
                } else if (eq >= 0) {
                    String raw = unquote(token.substring(eq + 1));
                    value = tokens.size() > 1 && raw.indexOf('$') >= 0 ? null : tryExpand(raw);
                } else {
                    value = null;
                }
                if (globals.containsKey(name) && !java.util.Objects.equals(globals.get(name), value)) {
                    value = null;
                }
                globals.put(name, value);
            }
        }

        private void from(List<String> tokens) {
            stage = null; // FROM sees only the global ARGs; the new stage scope is set below
            int i = 0;
            while (i < tokens.size() && tokens.get(i).startsWith("--")) {
                i++;
            }
            if (i >= tokens.size()) {
                throw refused();
            }
            String image = expandOrRefuse(tokens.get(i));
            String stageName = null;
            if (i + 2 < tokens.size() && tokens.get(i + 1).equalsIgnoreCase("AS")) {
                stageName = tokens.get(i + 2).toLowerCase(Locale.ROOT);
            }
            if (!image.equals("scratch") && !stageNames.contains(image.toLowerCase(Locale.ROOT))) {
                requireAllowedImage(image);
            }
            stages++;
            stage = new HashMap<>();
            if (stageName != null && STAGE_NAME.matcher(stageName).matches()) {
                stageNames.add(stageName);
            }
        }

        /** {@code COPY/ADD --from=} and {@code RUN --mount=type=...,from=}: leading flags only. */
        private void flags(List<String> tokens, String prefix, boolean mount) {
            for (String token : tokens) {
                if (!token.startsWith("--")) {
                    return;
                }
                if (!token.regionMatches(true, 0, prefix, 0, prefix.length())) {
                    continue;
                }
                String value = token.substring(prefix.length());
                if (value.indexOf('$') >= 0) {
                    throw refused(); // stage variables (ENV, inherited ENV, ARG) cannot be known here
                }
                if (mount) {
                    if (value.indexOf('"') >= 0 || value.indexOf('\'') >= 0) {
                        throw refused(); // CSV quoting could hide a from= field
                    }
                    for (String part : value.split(",")) {
                        if (part.regionMatches(true, 0, "from=", 0, 5)) {
                            source(expandOrRefuse(part.substring(5)));
                        }
                    }
                } else {
                    source(expandOrRefuse(value));
                }
            }
        }

        /** A stage (earlier name or index) or an image the daemon pulls. */
        private void source(String from) {
            String lower = from.toLowerCase(Locale.ROOT);
            if (DIGITS.matcher(from).matches() || stageNames.contains(lower)) {
                return;
            }
            requireAllowedImage(from);
        }

        private void requireAllowedImage(String image) {
            if (!ImageReferenceRules.isValidReference(image)) {
                throw refused();
            }
            ImageReferenceRules.requireAllowedRegistry(egress, image);
        }

        private String expandOrRefuse(String text) {
            try {
                return expand(text, 0);
            } catch (Unresolvable e) {
                throw refused();
            }
        }

        private String tryExpand(String text) {
            try {
                return expand(text, 0);
            } catch (Unresolvable e) {
                return null;
            }
        }

        private String expand(String text, int depth) {
            if (depth > MAX_NESTING) {
                throw new Unresolvable();
            }
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c != '$') {
                    out.append(c);
                    continue;
                }
                if (i + 1 >= text.length()) {
                    throw new Unresolvable();
                }
                if (text.charAt(i + 1) == '{') {
                    int end = closingBrace(text, i + 2);
                    out.append(braced(text.substring(i + 2, end), depth));
                    i = end;
                } else {
                    Matcher m = NAME.matcher(text);
                    if (!m.find(i + 1) || m.start() != i + 1) {
                        throw new Unresolvable();
                    }
                    out.append(lookup(m.group()));
                    i = m.end() - 1;
                }
                if (out.length() > MAX_EXPANDED) {
                    throw new Unresolvable();
                }
            }
            return out.toString();
        }

        private static int closingBrace(String text, int from) {
            int depth = 1;
            for (int i = from; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '{') {
                    depth++;
                } else if (c == '}' && --depth == 0) {
                    return i;
                }
            }
            throw new Unresolvable();
        }

        private String braced(String body, int depth) {
            Matcher m = NAME.matcher(body);
            if (!m.lookingAt()) {
                throw new Unresolvable();
            }
            String name = m.group();
            String rest = body.substring(m.end());
            if (rest.isEmpty()) {
                return lookup(name);
            }
            boolean colon = rest.startsWith(":-");
            if (!colon && !rest.startsWith("-")) {
                throw new Unresolvable(); // ${VAR:?}, ${VAR:+alt}, pattern removal ...
            }
            String fallback = rest.substring(colon ? 2 : 1);
            Map<String, String> vars = vars();
            String value = vars.get(name);
            if (PLATFORM_ARGS.contains(name) && !vars.containsKey(name)) {
                throw new Unresolvable(); // predefined; a build arg can set it without an ARG, so no fallback is safe
            }
            if (value != null && !(colon && value.isEmpty())) {
                return value;
            }
            return expand(fallback, depth + 1);
        }

        private String lookup(String name) {
            String value = vars().get(name);
            if (value == null) {
                throw new Unresolvable();
            }
            return value;
        }
    }

    // ---- lexical helpers ----

    /**
     * The Dockerfile parser splits on space and tab only, while Java and Go trim other Unicode whitespace in different
     * ways. Every such character, and any control character, makes the file unreadable here (fail closed) instead of
     * letting the two parsers disagree about where an instruction ends.
     */
    private static void requireParseableCharacters(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r' && (i + 1 >= text.length() || text.charAt(i + 1) != '\n')) {
                throw refused(); // a lone CR is no line break for the daemon
            }
            boolean asciiControl = c < 0x20 && c != '\t' && c != '\n' && c != '\r';
            boolean unicodeSpace = c >= 0x7f && (Character.isWhitespace(c) || Character.isSpaceChar(c) || Character.isISOControl(c));
            if (asciiControl || unicodeSpace || c == 0xFEFF && i > 0) {
                throw refused();
            }
        }
    }

    /** Instructions with comments removed and continuations joined, honouring the {@code escape} directive. */
    private static List<String> logicalLines(String[] lines, int from, char escape) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean continuing = false;
        for (int n = from; n < lines.length; n++) {
            String line = lines[n];
            String stripped = line.strip(); // only space, tab and CR are left: everything else was refused above
            if (stripped.startsWith("#") || stripped.isEmpty()) {
                continue; // comments and blank lines are skipped, also inside a continuation
            }
            if (stripped.charAt(stripped.length() - 1) == escape) {
                String part = line.substring(0, trimEnd(line) - 1);
                current.append(current.isEmpty() ? part.stripLeading() : part);
                continuing = true;
                continue;
            }
            current.append(current.isEmpty() ? stripped : line.stripTrailing());
            result.add(current.toString());
            current.setLength(0);
            continuing = false;
        }
        if (continuing) {
            result.add(current.toString());
        }
        return result;
    }

    private static int trimEnd(String s) {
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) {
            end--;
        }
        return end;
    }

    private static int firstWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    /** Whitespace separated words; a quoted part may contain spaces (quotes are kept for {@link #unquote}). */
    private static List<String> words(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        char quote = 0;
        for (char c : text.toCharArray()) {
            if (quote != 0) {
                word.append(c);
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
                word.append(c);
            } else if (Character.isWhitespace(c)) {
                if (!word.isEmpty()) {
                    out.add(word.toString());
                    word.setLength(0);
                }
            } else {
                word.append(c);
            }
        }
        if (!word.isEmpty()) {
            out.add(word.toString());
        }
        return out;
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            if ((first == '"' || first == '\'') && value.charAt(value.length() - 1) == first) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
}
