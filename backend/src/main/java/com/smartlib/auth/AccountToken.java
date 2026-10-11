package com.smartlib.auth;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.Setter;

/** Single-use hashed token for password resets and admin invitations. */
@Entity
@Table(name = "account_tokens")
@Getter
@Setter
public class AccountToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;

    @Enumerated(EnumType.STRING)
    private AccountTokenPurpose purpose;

    private String tokenHash;
    private Instant expiresAt;
    private Instant usedAt;
    private String requestedIp;
    private Instant createdAt;
}
