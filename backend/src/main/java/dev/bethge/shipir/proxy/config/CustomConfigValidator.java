package dev.bethge.shipir.proxy.config;

import dev.bethge.shipir.compose.generator.model.ProxyType;
import dev.bethge.shipir.proxy.ProxyException;
import dev.bethge.shipir.proxy.render.RenderedFile;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Checks the user-editable custom proxy file before it is stored or sent to the proxy. A violation is a 400
 * {@code INVALID_PROXY_CONFIG} carrying the validator's own message.
 */
interface CustomConfigValidator {

    String INVALID = "INVALID_PROXY_CONFIG";
    /** Upper bound of the custom file in bytes (UTF-8). */
    int MAX_BYTES = 128 * 1024;

    ProxyType type();

    /**
     * @param managed the currently rendered managed files, for validators that check the custom file together with them
     * @throws ProxyException the content is not acceptable
     */
    void validate(UUID hostId, String content, List<RenderedFile> managed);

    static void checkSize(String content) {
        if (content.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw invalid("The custom configuration is too large (limit " + MAX_BYTES / 1024 + " KiB)");
        }
    }

    static ProxyException invalid(String message) {
        return new ProxyException(HttpStatus.BAD_REQUEST, INVALID, message);
    }
}
