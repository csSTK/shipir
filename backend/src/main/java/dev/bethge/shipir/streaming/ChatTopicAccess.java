package dev.bethge.shipir.streaming;

/**
 * Port: may this user follow the live stream of a chat session? Implemented by the chat feature (owner only), so
 * {@code streaming/} needs no knowledge of chat sessions.
 */
public interface ChatTopicAccess {

    String TOPIC_PREFIX = "/topic/chat/";

    boolean maySubscribe(String username, String sessionId);
}
