package com.skillpulse.chat;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChatConversationRepository extends JpaRepository<ChatConversation, Long> {
    List<ChatConversation> findByUserIdOrderByUpdatedAtDesc(Long userId, Pageable pageable);

    /** Ownership check and lookup in one query: another learner's conversation is simply "not found". */
    Optional<ChatConversation> findByIdAndUserId(Long id, Long userId);

    long countByUserId(Long userId);
}
