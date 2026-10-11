package com.smartlib.ai;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "ai_conversations")
@Getter
@Setter
public class AiConversation {

    @Id
    private String id;

    private Long userId;
    private String title;
    private Instant createdAt;
    private Instant lastMessageAt;
}
