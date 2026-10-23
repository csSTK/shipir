package dev.bethge.shipir.ai.chat;

import dev.bethge.shipir.auth.UserRepository;
import dev.bethge.shipir.streaming.ChatTopicAccess;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Only the owner of a session may follow its live stream (not even ADMIN: shared sessions are read via the API). */
@Component
class ChatTopicAccessImpl implements ChatTopicAccess {

    private final UserRepository users;
    private final ChatSessionService sessions;

    ChatTopicAccessImpl(UserRepository users, ChatSessionService sessions) {
        this.users = users;
        this.sessions = sessions;
    }

    @Override
    public boolean maySubscribe(String username, String sessionId) {
        UUID id;
        try {
            id = UUID.fromString(sessionId);
        } catch (IllegalArgumentException e) {
            return false;
        }
        return users.findByUsername(username).map(u -> {
            try {
                sessions.findOwned(u.getId(), id);
                return true;
            } catch (ChatSessionException e) {
                return false;
            }
        }).orElse(false);
    }
}
