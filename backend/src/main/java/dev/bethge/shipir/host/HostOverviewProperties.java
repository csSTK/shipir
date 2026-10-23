package dev.bethge.shipir.host;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** Bound from {@code shipir.host.*}. */
@ConfigurationProperties("shipir.host")
record HostOverviewProperties(@DefaultValue("5s") Duration overviewTimeout) {
}
