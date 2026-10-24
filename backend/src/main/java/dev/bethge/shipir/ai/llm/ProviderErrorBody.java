package dev.bethge.shipir.ai.llm;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Debug-log helper for non-2xx provider responses. The body is capped and the API key is replaced before it reaches
 * the log. It never goes into {@link LlmException} messages or API responses.
 */
final class ProviderErrorBody {

    /** Characters of provider body kept for the log. */
    static final int MAX_CHARS = 2000;
    /** UTF-8 uses at most 4 bytes per character, so this byte cap always yields at least MAX_CHARS characters. */
    private static final int MAX_BYTES = MAX_CHARS * 4;

    private ProviderErrorBody() {
    }

    /** Reads at most {@link #MAX_BYTES} bytes; a failing or closed stream yields an empty string. */
    static String read(InputStream body, String apiKey) {
        try {
            String text = new String(body.readNBytes(MAX_BYTES), StandardCharsets.UTF_8);
            if (apiKey != null && !apiKey.isEmpty()) {
                text = text.replace(apiKey, "***");
            }
            return text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) : text;
        } catch (IOException e) {
            return "";
        }
    }
}
