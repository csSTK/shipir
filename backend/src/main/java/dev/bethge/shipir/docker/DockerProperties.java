package dev.bethge.shipir.docker;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** Bound from {@code shipir.docker.*}. */
@ConfigurationProperties("shipir.docker")
public record DockerProperties(
        @DefaultValue("unix:///var/run/docker.sock") String localSocket,
        @DefaultValue("3s") Duration connectTimeout,
        @DefaultValue("10s") Duration readTimeout) {
}
