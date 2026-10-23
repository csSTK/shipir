package dev.bethge.shipir.ai.chat;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Bound from {@code shipir.ai.chat.*}: {@code maxIterations} caps model calls per run (tool loops end there). */
@ConfigurationProperties("shipir.ai.chat")
record ChatProperties(@DefaultValue("8") int maxIterations) {
}
