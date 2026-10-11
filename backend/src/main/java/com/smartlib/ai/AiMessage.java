package com.smartlib.ai;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "ai_messages")
@Getter
@Setter
public class AiMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String conversationId;
    private String role;
    private String content;
    private String payload;
    private Integer latencyMs;
    private Integer firstTokenMs;
    private Instant createdAt;
}
