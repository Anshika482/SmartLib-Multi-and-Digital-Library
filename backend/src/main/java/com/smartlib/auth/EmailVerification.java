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

/** One issuance of a verification email: a hashed 6-digit OTP plus a hashed link token. */
@Entity
@Table(name = "email_verifications")
@Getter
@Setter
public class EmailVerification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;
    private String otpHash;
    private String linkTokenHash;
    private Instant otpExpiresAt;
    private Instant linkExpiresAt;
    private int attemptCount;

    @Enumerated(EnumType.STRING)
    private VerificationStatus status;

    private Instant sentAt;
    private Instant consumedAt;
    private String consumedVia;
    private Instant createdAt;
}
