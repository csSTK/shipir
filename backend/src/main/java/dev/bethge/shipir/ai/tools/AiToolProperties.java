package dev.bethge.shipir.ai.tools;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound from {@code shipir.ai.tools.*}.
 *
 * @param maxResultChars cap on the characters of one tool result sent back to the model (about 1K tokens per 4000
 *                       chars). Longer results are cut and carry a note. Default {@value #DEFAULT_MAX_RESULT_CHARS}
 *                       (also used when unset or not positive).
 */
@ConfigurationProperties("shipir.ai.tools")
public record AiToolProperties(Integer maxResultChars) {

    static final int DEFAULT_MAX_RESULT_CHARS = 4000;

    public AiToolProperties {
        if (maxResultChars == null || maxResultChars <= 0) {
            maxResultChars = DEFAULT_MAX_RESULT_CHARS;
        }
    }
}
