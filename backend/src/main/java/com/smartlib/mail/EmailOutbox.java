package com.smartlib.mail;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "email_outbox")
@Getter
@Setter
public class EmailOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String toEmail;
    private String subject;
    private String htmlBody;
    private String textBody;
    private String status;
    private int attempts;
    private Instant nextAttemptAt;
    private String lastError;
    private Instant sentAt;
    private Instant createdAt;
}
