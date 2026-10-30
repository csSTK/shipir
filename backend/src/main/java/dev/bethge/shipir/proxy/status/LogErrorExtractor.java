package dev.bethge.shipir.proxy.status;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the latest error line about a domain in the proxy's recent log. Log lines can contain text an attacker
 * influences and information about routes the asking user may not see (router and service names, other hostnames,
 * addresses, URLs). So the message is rebuilt from the {@code msg} and {@code error} fields only (or, for the console
 * format, the text before the first {@code key=value} field); every other field is dropped. What remains is
 * sanitized: printable ASCII, URLs, paths, anything with an {@code @} (e-mail, {@code name@docker}), IPv6 and IPv4
 * addresses and host names other than the asked domain are replaced, long opaque tokens removed, capped at
 * {@link #MAX_MESSAGE}.
 */
final class LogErrorExtractor {

    static final int MAX_MESSAGE = 200;
    private static final String GENERIC = "Certificate request failed, see the proxy log";

    private static final Pattern ERROR_LEVEL = Pattern.compile(
            "(?i)level[\"=:\\s]+(error|fatal|err)\\b|\\b(ERR|ERROR|FTL|FATAL)\\b");
    private static final Pattern ANSI = Pattern.compile("\u001b\\[[0-9;]*[A-Za-z]");
    private static final Pattern NON_PRINTABLE = Pattern.compile("[^\\x20-\\x7E]");
    private static final Pattern URL = Pattern.compile("(?i)\\b[a-z][a-z0-9+.-]*://\\S*");
    private static final Pattern AT_TOKEN = Pattern.compile("\\S*@\\S*");
    private static final Pattern PATH_TOKEN = Pattern.compile("\\S*/\\S*");
    private static final Pattern LONG_TOKEN = Pattern.compile("\\S{40,}");
    private static final Pattern IPV6 = Pattern.compile("(?i)(?<![A-Za-z0-9])(?:[0-9a-f]{0,4}:){2,7}[0-9a-f]{0,4}");
    private static final Pattern ADDRESS = Pattern.compile(
            "\\b\\d{1,3}(?:\\.\\d{1,3}){3}\\b|(?:[A-Za-z0-9_-]+\\.)+[A-Za-z]{2,}");
    private static final Pattern FIELD_ERROR = field("error");
    private static final Pattern FIELD_MSG = field("msg");
    private static final Pattern PREFIX = Pattern.compile("^\\s*(?:\\S*\\d{4}-\\d\\d-\\d\\d\\S*\\s+)?"
            + "(?:(?i:ERR|ERROR|FTL|FATAL)\\s+)?");
    private static final Pattern FIRST_FIELD = Pattern.compile("\\s[A-Za-z_][A-Za-z0-9_.]*=");

    private LogErrorExtractor() {
    }

    private static Pattern field(String name) {
        return Pattern.compile("(?:\\b" + name + "=|\"" + name + "\"\\s*:\\s*)(?:\"((?:[^\"\\\\]|\\\\.)*)\"|([^\\s\"]+))");
    }

    static Optional<String> lastError(List<String> lines, String domain) {
        Pattern mentions = Pattern.compile("(?i)(?<![A-Za-z0-9.*_-])" + Pattern.quote(domain) + "(?![A-Za-z0-9_-]|\\.[A-Za-z0-9])");
        for (int i = lines.size() - 1; i >= 0; i--) {
            String line = lines.get(i);
            if (line != null && ERROR_LEVEL.matcher(line).find() && mentions.matcher(line).find()) {
                return Optional.of(sanitize(rebuild(line), domain));
            }
        }
        return Optional.empty();
    }

    /** msg and error fields only; console format: the text before the first field; otherwise a generic text. */
    private static String rebuild(String line) {
        String msg = value(FIELD_MSG, line);
        String error = value(FIELD_ERROR, line);
        if (msg != null || error != null) {
            return msg != null && error != null ? msg + ": " + error : msg != null ? msg : error;
        }
        if (line.stripLeading().startsWith("{")) {
            return GENERIC;
        }
        Matcher prefix = PREFIX.matcher(line);
        String rest = line.substring(prefix.lookingAt() ? prefix.end() : 0);
        Matcher field = FIRST_FIELD.matcher(" " + rest);
        String text = field.find() ? (" " + rest).substring(0, field.start()) : rest;
        return text.isBlank() ? GENERIC : text;
    }

    private static String value(Pattern pattern, String line) {
        Matcher m = pattern.matcher(line);
        if (!m.find()) {
            return null;
        }
        String v = m.group(1) != null ? m.group(1).replace("\\\"", "\"") : m.group(2);
        return v == null || v.isBlank() ? null : v;
    }

    private static String sanitize(String text, String domain) {
        String s = ANSI.matcher(text).replaceAll("");
        s = NON_PRINTABLE.matcher(s).replaceAll(" ");
        s = URL.matcher(s).replaceAll("<url>");
        s = AT_TOKEN.matcher(s).replaceAll("<redacted>");
        s = PATH_TOKEN.matcher(s).replaceAll("<path>");
        s = LONG_TOKEN.matcher(s).replaceAll("<redacted>");
        s = IPV6.matcher(s).replaceAll("<ip>");
        Matcher m = ADDRESS.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String found = m.group();
            String lower = found.toLowerCase(Locale.ROOT);
            boolean keep = lower.equals(domain.toLowerCase(Locale.ROOT)) || lower.equals("letsencrypt.org")
                    || lower.endsWith(".letsencrypt.org");
            m.appendReplacement(out, Matcher.quoteReplacement(keep ? found : "<host>"));
        }
        m.appendTail(out);
        s = out.toString().replaceAll("\\s+", " ").trim();
        return s.length() <= MAX_MESSAGE ? s : s.substring(0, MAX_MESSAGE - 3) + "...";
    }
}
