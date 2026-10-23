package dev.bethge.shipir.streaming;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.broker.OrderedMessageChannelDecorator;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

/** Sends one JSON frame directly to a single STOMP subscription, in publish order per session. */
@Component
class StompFrameSender {

    private final MessageChannel clientOutbound;
    private final JsonMapper mapper;

    StompFrameSender(@Lazy @Qualifier("clientOutboundChannel") MessageChannel clientOutbound, JsonMapper mapper) {
        this.clientOutbound = new OrderedMessageChannelDecorator(clientOutbound,
                org.apache.commons.logging.LogFactory.getLog(StompFrameSender.class));
        this.mapper = mapper;
    }

    void send(String sessionId, String subscriptionId, String destination, Object payload) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        accessor.setSessionId(sessionId);
        accessor.setSubscriptionId(subscriptionId);
        accessor.setDestination(destination);
        accessor.setContentType(MimeTypeUtils.APPLICATION_JSON);
        accessor.setLeaveMutable(true);
        byte[] body = mapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8);
        clientOutbound.send(MessageBuilder.createMessage(body, accessor.getMessageHeaders()));
    }
}
