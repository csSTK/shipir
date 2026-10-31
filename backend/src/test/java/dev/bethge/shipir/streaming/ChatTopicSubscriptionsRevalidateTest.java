package dev.bethge.shipir.streaming;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.MessageChannel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** {@link ChatTopicSubscriptions#revalidate}: a throwing predicate fails closed for that subscription only. */
class ChatTopicSubscriptionsRevalidateTest {

    private final MessageChannel inbound = mock(MessageChannel.class);
    private final ChatTopicSubscriptions subscriptions = new ChatTopicSubscriptions(inbound);

    @Test
    void throwingPredicateEndsThatSubscriptionAndStillChecksTheOthers() {
        subscriptions.add("alice", "broken", "s1", "a");
        subscriptions.add("alice", "gone", "s1", "b");
        subscriptions.add("alice", "kept", "s1", "c");
        subscriptions.add("bob", "broken", "s2", "d");

        subscriptions.revalidate("alice", chatId -> {
            if (chatId.equals("broken")) {
                throw new IllegalStateException("db down");
            }
            return chatId.equals("kept");
        });

        assertThat(subscriptions.count()).isEqualTo(2); // alice/kept and bob's untouched one
        verify(inbound, times(2)).send(any());
    }
}
