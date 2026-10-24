package dev.bethge.shipir.registry;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Bound from {@code shipir.registry.*}. {@code allowInsecureHttp} exists only so tests can reach a local stub;
 * it stays off in every real deployment (https is the only accepted scheme).
 */
@ConfigurationProperties("shipir.registry")
record RegistryProperties(@DefaultValue("false") boolean allowInsecureHttp,
                          @DefaultValue("10s") Duration timeout) {
}
