package dev.bethge.shipir.compose;

import dev.bethge.shipir.compose.dto.EnvEntry;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Renders env entries to .env text and parses that text back. Parses it back, see {@link #parse}. */
public final class EnvFile {

    private static final Pattern KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,254}");
    private static final Pattern PLAIN = Pattern.compile("[A-Za-z0-9_./:@%+,-]*");

    private EnvFile() {
    }

    static String render(List<EnvEntry> entries) {
        Set<String> seen = new HashSet<>();
        StringBuilder out = new StringBuilder();
        for (EnvEntry e : entries) {
            if (e.key() == null || !KEY.matcher(e.key()).matches()) {
                throw invalid("Invalid variable name");
            }
            String value = e.value() == null ? "" : e.value();
            if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\0') >= 0) {
                throw invalid("Variable values must not contain line breaks");
            }
            if (!seen.add(e.key())) {
                throw invalid("Duplicate variable name");
            }
            out.append(e.key()).append('=').append(quote(value)).append('\n');
        }
        return out.toString();
    }

    /**
     * Understands what {@link #render} writes plus the common forms of a hand-written or Git-supplied file: CRLF line
     * ends, an {@code export} prefix, spaces around the key, and an inline {@code # comment} after a value. Not a full
     * dotenv implementation (no multi-line values, no variable expansion).
     */
    public static List<EnvEntry> parse(String text) {
        List<EnvEntry> entries = new ArrayList<>();
        for (String raw : text.split("\r?\n")) {
            String line = raw.strip();
            if (line.startsWith("export ") || line.startsWith("export\t")) {
                line = line.substring("export".length()).stripLeading();
            }
            int eq = line.indexOf('=');
            if (line.isBlank() || line.startsWith("#") || eq <= 0) {
                continue;
            }
            entries.add(new EnvEntry(line.substring(0, eq).strip(), unquote(stripInlineComment(line.substring(eq + 1).strip()))));
        }
        return entries;
    }

    /** Drops a trailing comment: after the closing quote of a quoted value, or from the first whitespace-then-# otherwise. */
    private static String stripInlineComment(String value) {
        if (value.startsWith("'") || value.startsWith("\"")) {
            char quote = value.charAt(0);
            for (int i = 1; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c == '\\' && quote == '"') {
                    i++;
                } else if (c == quote) {
                    return value.substring(0, i + 1);
                }
            }
            return value;
        }
        for (int i = 1; i < value.length(); i++) {
            if (value.charAt(i) == '#' && Character.isWhitespace(value.charAt(i - 1))) {
                return value.substring(0, i).stripTrailing();
            }
        }
        return value.startsWith("#") ? "" : value;
    }

    private static String quote(String value) {
        if (PLAIN.matcher(value).matches()) {
            return value;
        }
        if (value.indexOf('\'') < 0) {
            return "'" + value + "'";
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\"";
    }

    private static String unquote(String raw) {
        if (raw.length() >= 2 && raw.startsWith("'") && raw.endsWith("'")) {
            return raw.substring(1, raw.length() - 1);
        }
        if (raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
            StringBuilder sb = new StringBuilder();
            String inner = raw.substring(1, raw.length() - 1);
            for (int i = 0; i < inner.length(); i++) {
                char c = inner.charAt(i);
                if (c == '\\' && i + 1 < inner.length()) {
                    c = inner.charAt(++i);
                }
                sb.append(c);
            }
            return sb.toString();
        }
        return raw;
    }

    private static DeploymentException invalid(String message) {
        return new DeploymentException(HttpStatus.BAD_REQUEST, "INVALID_ENV", message);
    }
}
