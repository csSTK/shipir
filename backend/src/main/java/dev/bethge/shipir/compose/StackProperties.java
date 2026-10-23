package dev.bethge.shipir.compose;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Path;

/** Bound from {@code shipir.stacks.*}. {@code baseDir} holds {@code <hostId>/<stackName>/} directories. */
@ConfigurationProperties("shipir.stacks")
public record StackProperties(@DefaultValue("./data/stacks") Path baseDir) {
}
