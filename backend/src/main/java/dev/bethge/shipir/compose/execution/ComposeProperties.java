package dev.bethge.shipir.compose.execution;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** Bound from {@code shipir.compose.*}. {@code timeout} limits a single compose invocation. */
@ConfigurationProperties("shipir.compose")
public record ComposeProperties(@DefaultValue("10m") Duration timeout) {
}
