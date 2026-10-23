package dev.bethge.shipir.ai.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** Bound from {@code shipir.ai.*}. {@code readTimeout} is the idle time allowed without any response bytes. */
@ConfigurationProperties("shipir.ai")
public record LlmProperties(@DefaultValue("5s") Duration connectTimeout,
                            @DefaultValue("60s") Duration readTimeout) {
}
