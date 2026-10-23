package dev.bethge.shipir.ai.chat;

import dev.bethge.shipir.streaming.ChatTopicAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Sends {@link ChatStreamEvent}s to the session topic. A broken stream never fails a run. */
@Component
class ChatEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(ChatEventPublisher.class);

    private final SimpMessagingTemplate template;

    ChatEventPublisher(@Lazy SimpMessagingTemplate template) {
        this.template = template;
    }

    void publish(UUID sessionId, ChatStreamEvent event) {
        try {
            template.convertAndSend(ChatTopicAccess.TOPIC_PREFIX + sessionId, event);
        } catch (RuntimeException e) {
            log.debug("Chat event not delivered for session {}: {}", sessionId, e.getClass().getSimpleName());
        }
    }
}
