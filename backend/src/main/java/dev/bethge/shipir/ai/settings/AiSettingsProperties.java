package dev.bethge.shipir.ai.settings;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Bound from {@code shipir.ai.settings.*}. {@code allowInsecureHttp} exists only so tests can reach a local stub;
 * it stays off in every real deployment (https is the only accepted scheme).
 */
@ConfigurationProperties("shipir.ai.settings")
record AiSettingsProperties(@DefaultValue("false") boolean allowInsecureHttp,
                            @DefaultValue("5s") Duration testTimeout) {
}
