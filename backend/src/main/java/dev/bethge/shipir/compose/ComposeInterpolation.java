package dev.bethge.shipir.compose;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort Compose variable interpolation ({@code $VAR}, {@code ${VAR}}, {@code ${VAR:-d}}, {@code ${VAR-d}},
 * {@code ${VAR:+alt}}, {@code ${VAR+alt}}, {@code $$}, nested defaults like {@code ${A:-${B}}}) of a stored compose text
 * against a plain variable map (the deployment's {@code .env}). Used only to learn which image and network names a
 * stored file refers to, never to run it. Only the operand text of a default is expanded recursively (it is template
 * source); a substituted variable value is never expanded again. A reference that cannot be resolved (unset variable
 * without default, {@code ${VAR:?msg}}) stays as written, so the caller still sees an unresolved token.
 */
final class ComposeInterpolation {

    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern BRACED = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)(?:(:?[-+?])(.*))?", Pattern.DOTALL);
    private static final int MAX_DEPTH = 16;

    private ComposeInterpolation() {
    }

    static String apply(String text, Map<String, String> env) {
        return text == null ? "" : expand(text, env == null ? Map.of() : env, 0);
    }

    private static String expand(String text, Map<String, String> vars, int depth) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c != '$' || i + 1 >= text.length()) {
                out.append(c);
                i++;
                continue;
            }
            char next = text.charAt(i + 1);
            if (next == '$') {
                out.append('$');
                i += 2;
            } else if (next == '{') {
                int end = closingBrace(text, i + 1);
                if (end < 0 || depth >= MAX_DEPTH) {
                    out.append(c);
                    i++;
                    continue;
                }
                out.append(braced(text.substring(i, end + 1), text.substring(i + 2, end), vars, depth));
                i = end + 1;
            } else {
                Matcher m = NAME.matcher(text).region(i + 1, text.length());
                if (m.lookingAt()) {
                    String value = vars.get(m.group());
                    out.append(value == null ? text.substring(i, m.end()) : value);
                    i = m.end();
                } else {
                    out.append(c);
                    i++;
                }
            }
        }
        return out.toString();
    }

    /** Index of the brace closing the one at {@code open}, counting nested {@code ${...}}; -1 when unbalanced. */
    private static int closingBrace(String text, int open) {
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static String braced(String original, String inner, Map<String, String> vars, int depth) {
        Matcher m = BRACED.matcher(inner);
        if (!m.matches()) {
            return original;
        }
        String value = vars.get(m.group(1));
        String op = m.group(2);
        if (op == null) {
            return value == null ? original : value;
        }
        boolean present = value != null && !(op.startsWith(":") && value.isEmpty());
        String operand = m.group(3);
        return switch (op.substring(op.length() - 1)) {
            case "-" -> present ? value : expand(operand, vars, depth + 1);
            case "+" -> present ? expand(operand, vars, depth + 1) : "";
            default -> present ? value : original;
        };
    }
}
