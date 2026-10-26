package dev.bethge.shipir.common.util;

import java.util.regex.Pattern;

/**
 * Makes daemon output safe to show as plain text: control characters, C1 controls (U+0080-009F) and bidi
 * embedding, override and isolate characters (U+202A-202E, U+2066-2069) become spaces, so a log line cannot
 * hide or reorder text in the UI. Tabs are kept.
 */
public final class OutputSanitizer {

    private static final Pattern UNSAFE = Pattern.compile(
            "[\\p{Cntrl}\\x{0080}-\\x{009F}\\x{202A}-\\x{202E}\\x{2066}-\\x{2069}&&[^\\t]]");

    private OutputSanitizer() {
    }

    public static String neutralize(String text) {
        return UNSAFE.matcher(text).replaceAll(" ");
    }
}
