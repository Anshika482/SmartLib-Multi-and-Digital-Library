package com.smartlib.ai;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiMessageRepository extends JpaRepository<AiMessage, Long> {

    List<AiMessage> findByConversationIdOrderByIdDesc(String conversationId, Pageable pageable);

    List<AiMessage> findByConversationIdOrderByIdAsc(String conversationId);
}
