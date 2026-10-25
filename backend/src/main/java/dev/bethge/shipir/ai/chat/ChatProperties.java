package dev.bethge.shipir.ai.chat;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Bound from {@code shipir.ai.chat.*}.
 *
 * @param maxIterations        model calls per run (tool loops end there)
 * @param fullToolResults      how many of the newest tool results are sent in full, older ones are replaced by a stub
 *                             in the request (stored history is unchanged); a high value disables trimming
 * @param maxRateLimitWait     on a provider rate limit (429) the run waits and retries the same model call if the
 *                             wait is at most this long
 * @param maxRateLimitRetries  retries per model call; {@code 0} disables waiting
 * @param messagesPerMinute    messages one user may send per rolling minute across all their chats
 * @param maxConcurrentRuns    runs one user may have active at the same time (one per chat is enforced separately)
 * @param maxHistoryMessages   newest stored messages resent to the provider on each call
 * @param maxHistoryChars      character budget of the resent history (about 4 chars per token, 60000 is ~15k tokens)
 */
@ConfigurationProperties("shipir.ai.chat")
record ChatProperties(@DefaultValue("8") int maxIterations, @DefaultValue("2") int fullToolResults,
                      @DefaultValue("30s") Duration maxRateLimitWait, @DefaultValue("2") int maxRateLimitRetries,
                      @DefaultValue("20") int messagesPerMinute,
                      @DefaultValue("3") int maxConcurrentRuns,
                      @DefaultValue("40") int maxHistoryMessages,
                      @DefaultValue("60000") int maxHistoryChars) {
}
