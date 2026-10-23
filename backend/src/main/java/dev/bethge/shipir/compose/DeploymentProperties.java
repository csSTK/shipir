package dev.bethge.shipir.compose;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Path;

/**
 * Bound from {@code shipir.stacks.*}. The legacy name is kept so existing {@code SHIPIR_STACKS_BASE_DIR} setups work;
 * the on-disk layout is unchanged. {@code baseDir} holds {@code <hostId>/<deploymentName>/} directories.
 */
@ConfigurationProperties("shipir.stacks")
public record DeploymentProperties(@DefaultValue("./data/stacks") Path baseDir) {
}
