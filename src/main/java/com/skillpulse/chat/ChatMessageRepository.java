package com.skillpulse.chat;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
    List<ChatMessage> findByConversationIdOrderByIdAsc(Long conversationId);

    /** Newest first; the service reverses it. Used to build the model's history from what the server stored. */
    List<ChatMessage> findByConversationIdOrderByIdDesc(Long conversationId, Pageable pageable);

    long countByConversationId(Long conversationId);

    /** Learner messages the router put in one of the given categories, newest first, with their owner loaded. */
    @Query("select m from ChatMessage m join fetch m.conversation c join fetch c.user "
            + "where m.role = 'user' and m.category in :categories order by m.id desc")
    List<ChatMessage> findLearnerMessagesByCategories(@Param("categories") Collection<String> categories,
                                                      Pageable pageable);

    /** Rows of [category, count] over all learner messages that were routed. */
    @Query("select m.category, count(m) from ChatMessage m "
            + "where m.role = 'user' and m.category is not null group by m.category")
    List<Object[]> countLearnerMessagesByCategory();
}
