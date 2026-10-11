package com.smartlib.audit;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "audit_logs")
@Getter
@Setter
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Instant occurredAt;
    private Long actorUserId;
    private String actorRole;
    private String actorEmail;
    private Long libraryId;
    private String action;
    private String entityType;
    private String entityId;
    private String outcome;
    private String ip;
    private String userAgent;
    private String requestId;
    private String details;
}
