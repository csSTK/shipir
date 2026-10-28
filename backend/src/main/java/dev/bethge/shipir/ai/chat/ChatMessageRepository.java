package dev.bethge.shipir.ai.chat;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {

    List<ChatMessage> findBySessionIdOrderBySeqAsc(UUID sessionId);

    @Query("select coalesce(max(m.seq), 0) from ChatMessage m where m.sessionId = :sessionId")
    int maxSeq(UUID sessionId);

    @Query("select count(m) from ChatMessage m where m.status = dev.bethge.shipir.ai.chat.ChatMessageStatus.PENDING "
            + "and m.sessionId in (select s.id from ChatSession s where s.userId = :userId)")
    long countPendingByUserId(UUID userId);
}
