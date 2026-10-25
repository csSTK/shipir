package dev.bethge.shipir.host;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * Bound from {@code shipir.images.*}: {@code maxBuildContext} caps an uploaded or cloned build context (200MB, binary
 * units), {@code buildTimeout} the whole build, {@code buildTempDir} where contexts are
 * spooled (empty = the system temp directory).
 */
@ConfigurationProperties("shipir.images")
record ImageBuildProperties(@DefaultValue("200MB") DataSize maxBuildContext,
                            @DefaultValue("30m") Duration buildTimeout,
                            @DefaultValue("") String buildTempDir) {
}
