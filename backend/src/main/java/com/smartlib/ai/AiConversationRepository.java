package com.smartlib.ai;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AiConversationRepository extends JpaRepository<AiConversation, String> {

    Optional<AiConversation> findByIdAndUserId(String id, Long userId);

    List<AiConversation> findTop30ByUserIdOrderByLastMessageAtDesc(Long userId);
}
