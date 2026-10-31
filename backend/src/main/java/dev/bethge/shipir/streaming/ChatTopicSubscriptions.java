package dev.bethge.shipir.streaming;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * Remembers who follows which {@code /topic/chat/{sessionId}}. Chat frames go through the simple broker, which does
 * not know users, so after an access change a subscription the user may no longer hold is removed from the broker
 * with a synthetic UNSUBSCRIBE on the inbound channel (the same frame a client would send).
 */
@Component
class ChatTopicSubscriptions {

    private record Subscription(String username, String chatSessionId, String sessionId, String subscriptionId) {
    }

    private final List<Subscription> subscriptions = new CopyOnWriteArrayList<>();
    private final MessageChannel clientInbound;

    ChatTopicSubscriptions(@Lazy @Qualifier("clientInboundChannel") MessageChannel clientInbound) {
        this.clientInbound = clientInbound;
    }

    void add(String username, String chatSessionId, String sessionId, String subscriptionId) {
        subscriptions.add(new Subscription(username, chatSessionId, sessionId, subscriptionId));
    }

    /** {@code subscriptionId} null = every subscription of the session. */
    void remove(String sessionId, String subscriptionId) {
        subscriptions.removeIf(s -> s.sessionId.equals(sessionId)
                && (subscriptionId == null || s.subscriptionId.equals(subscriptionId)));
    }

    int count() {
        return subscriptions.size();
    }

    /** Ends the user's subscriptions to chat sessions they may no longer follow; the STOMP connection stays. */
    void revalidate(String username, Predicate<String> chatSessionStillAllowed) {
        for (Subscription s : subscriptions) {
            if (!username.equals(s.username) || chatSessionStillAllowed.test(s.chatSessionId)) {
                continue;
            }
            if (!subscriptions.remove(s)) {
                continue; // a concurrent revalidation already ended it
            }
            sendUnsubscribe(s.sessionId, s.subscriptionId);
        }
    }

    /**
     * Forgets the subscription and sends the synthetic UNSUBSCRIBE even if it was already forgotten: used after the
     * broker handled a SUBSCRIBE whose access is gone, where an earlier synthetic UNSUBSCRIBE may have been processed
     * before the broker knew the subscription.
     */
    void end(String sessionId, String subscriptionId) {
        remove(sessionId, subscriptionId);
        sendUnsubscribe(sessionId, subscriptionId);
    }

    private void sendUnsubscribe(String sessionId, String subscriptionId) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.UNSUBSCRIBE);
        accessor.setSessionId(sessionId);
        accessor.setSubscriptionId(subscriptionId);
        accessor.setLeaveMutable(true);
        clientInbound.send(MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders()));
    }
}
