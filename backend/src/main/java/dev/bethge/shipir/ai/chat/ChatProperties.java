package dev.bethge.shipir.ai.chat;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Bound from {@code shipir.ai.chat.*}: {@code maxIterations} caps model calls per run (tool loops end there);
 * {@code fullToolResults} is how many of the newest tool results are sent in full, older ones are replaced by a stub
 * in the request (stored history is unchanged). A high value disables trimming. On a rate limit (429) the run waits
 * and retries the same model call if the wait is at most {@code maxRateLimitWait}, up to {@code maxRateLimitRetries}
 * times per call; {@code 0} retries disables waiting.
 */
@ConfigurationProperties("shipir.ai.chat")
record ChatProperties(@DefaultValue("8") int maxIterations, @DefaultValue("2") int fullToolResults,
                      @DefaultValue("30s") Duration maxRateLimitWait, @DefaultValue("2") int maxRateLimitRetries) {
}
