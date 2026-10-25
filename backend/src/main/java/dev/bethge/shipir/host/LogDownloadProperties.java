package dev.bethge.shipir.host;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/** Bound from {@code shipir.log-download.*}: caps for one container log download and for downloads running at once. */
@ConfigurationProperties("shipir.log-download")
record LogDownloadProperties(@DefaultValue("50MB") DataSize maxBytes, @DefaultValue("2m") Duration timeout,
                          /** Downloads running at the same time on this server (each holds a request thread). */
                          @DefaultValue("8") int maxConcurrent,
                          /** Downloads running at the same time per user. */
                          @DefaultValue("2") int maxPerUser) {
}
