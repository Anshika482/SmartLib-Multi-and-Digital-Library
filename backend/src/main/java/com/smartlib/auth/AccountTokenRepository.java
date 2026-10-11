package com.smartlib.auth;

import java.time.Instant;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface AccountTokenRepository extends JpaRepository<AccountToken, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AccountToken> findByTokenHashAndPurpose(String tokenHash, AccountTokenPurpose purpose);

    /** Non-locking lookup for read-only validation endpoints. */
    @Query("select t from AccountToken t where t.tokenHash = :tokenHash and t.purpose = :purpose")
    Optional<AccountToken> peek(String tokenHash, AccountTokenPurpose purpose);

    @Modifying
    @Query("update AccountToken t set t.usedAt = :now where t.userId = :userId and t.purpose = :purpose and t.usedAt is null")
    int invalidateOutstanding(Long userId, AccountTokenPurpose purpose, Instant now);
}
