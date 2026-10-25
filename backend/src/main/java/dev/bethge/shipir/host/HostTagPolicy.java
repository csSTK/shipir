package dev.bethge.shipir.host;

import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Rules for environment tags. Input is normalised (trimmed, lowercase), then checked; the result is the distinct,
 * sorted set that gets stored. The same pattern and length limit are enforced by the {@code host_tags} table.
 */
final class HostTagPolicy {

    static final int MAX_TAGS = 10;

    private static final Pattern TAG = Pattern.compile("^[a-z0-9][a-z0-9-]{0,23}$");

    private HostTagPolicy() {
    }

    /** Null means no tags. Throws {@link HostException} (400) for an invalid tag or more than {@link #MAX_TAGS}. */
    static List<String> normalize(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        TreeSet<String> distinct = new TreeSet<>();
        for (String tag : raw) {
            if (tag == null) {
                throw invalidTag();
            }
            String normalised = tag.strip().toLowerCase(Locale.ROOT);
            if (!TAG.matcher(normalised).matches()) {
                throw invalidTag();
            }
            distinct.add(normalised);
        }
        if (distinct.size() > MAX_TAGS) {
            throw new HostException(HttpStatus.BAD_REQUEST, "TOO_MANY_TAGS",
                    "An environment can have at most " + MAX_TAGS + " tags",
                    List.of("tags: at most " + MAX_TAGS + " distinct tags"));
        }
        return new ArrayList<>(distinct);
    }

    private static HostException invalidTag() {
        return new HostException(HttpStatus.BAD_REQUEST, "INVALID_TAG", "Invalid environment tag",
                List.of("tags: 1-24 characters, a-z, 0-9 or '-', starting with a letter or digit"));
    }
}
