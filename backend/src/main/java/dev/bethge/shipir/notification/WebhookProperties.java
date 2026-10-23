package dev.bethge.shipir.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** Bound from {@code shipir.notification.*}; the timeout applies to connect and to the whole request. */
@ConfigurationProperties("shipir.notification")
record WebhookProperties(@DefaultValue("5s") Duration webhookTimeout) {
}
