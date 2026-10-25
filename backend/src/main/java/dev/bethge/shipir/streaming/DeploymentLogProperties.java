package dev.bethge.shipir.streaming;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** Bound from {@code shipir.streaming.deployment-logs.*}: limits of the combined log topic of a Compose project. */
@ConfigurationProperties("shipir.streaming.deployment-logs")
public record DeploymentLogProperties(
        /** Lines per container sent when the first subscriber arrives (a SUBSCRIBE header may override). */
        @DefaultValue("100") int tail,
        /** Merged lines kept per project for subscribers that join later. */
        @DefaultValue("1000") int bufferLines,
        /** Longer lines are cut and marked. */
        @DefaultValue("8192") int maxLineLength,
        /** Containers followed per project; further ones are reported once and ignored. */
        @DefaultValue("20") int maxContainers,
        /** Lines above this rate per container are dropped and summarized in one marker line. */
        @DefaultValue("500") int maxLinesPerSecondPerContainer,
        /** Lines above this rate for the whole project are dropped and summarized in one marker line. */
        @DefaultValue("2000") int maxLinesPerSecond,
        /** How often the container list of a followed project is re-read to pick up containers that start later. */
        @DefaultValue("5s") Duration refreshInterval) {
}
