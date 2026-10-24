package dev.bethge.shipir.compose.execution;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Bound from {@code shipir.compose.*}. {@code timeout} limits a single compose invocation; {@code adoptAllowedDirs} are the
 * absolute directories (LOCAL host only) from which adopting a project may read its compose file and {@code .env}
 * (default none: the YAML must be pasted).
 */
@ConfigurationProperties("shipir.compose")
public record ComposeProperties(@DefaultValue("10m") Duration timeout,
                                @DefaultValue List<Path> adoptAllowedDirs) {
}
