package dev.bethge.shipir.compose;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort Compose variable interpolation ({@code $VAR}, {@code ${VAR}}, {@code ${VAR:-d}}, {@code ${VAR-d}},
 * {@code ${VAR:+alt}}, {@code ${VAR+alt}}, {@code $$}) of a stored compose text against a plain variable map (the
 * deployment's {@code .env}). Used only to learn which image and network names a stored file refers to, never to run it.
 * Single pass: a substituted value is not expanded again. A reference that cannot be resolved (unset variable without
 * default, {@code ${VAR:?msg}}) stays as written, so the caller still sees an unresolved token instead of a guess.
 */
final class ComposeInterpolation {

    private static final Pattern REF = Pattern.compile(
            "\\$(?:(\\$)|\\{([A-Za-z_][A-Za-z0-9_]*)(?:(:?[-+?])([^}]*))?\\}|([A-Za-z_][A-Za-z0-9_]*))");

    private ComposeInterpolation() {
    }

    static String apply(String text, Map<String, String> env) {
        if (text == null) {
            return "";
        }
        Map<String, String> vars = env == null ? Map.of() : env;
        Matcher m = REF.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String replacement;
            if (m.group(1) != null) {
                replacement = "$";
            } else if (m.group(5) != null) {
                String value = vars.get(m.group(5));
                replacement = value == null ? m.group(0) : value;
            } else {
                replacement = braced(m, vars);
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String braced(Matcher m, Map<String, String> vars) {
        String value = vars.get(m.group(2));
        String op = m.group(3);
        String operand = m.group(4);
        if (op == null) {
            return value == null ? m.group(0) : value;
        }
        boolean strict = op.startsWith(":"); // ":" also treats an empty value as unset
        boolean present = value != null && !(strict && value.isEmpty());
        return switch (op.substring(op.length() - 1)) {
            case "-" -> present ? value : operand;
            case "+" -> present ? operand : "";
            default -> present ? value : m.group(0);
        };
    }
}
