package dev.bethge.shipir.ai.chat;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface ChatSessionRepository extends JpaRepository<ChatSession, UUID> {

    Page<ChatSession> findByUserId(UUID userId, Pageable pageable);

    Optional<ChatSession> findByIdAndUserId(UUID id, UUID userId);

    Page<ChatSession> findBySharedWithAdminTrue(Pageable pageable);

    Optional<ChatSession> findByIdAndSharedWithAdminTrue(UUID id);
}
