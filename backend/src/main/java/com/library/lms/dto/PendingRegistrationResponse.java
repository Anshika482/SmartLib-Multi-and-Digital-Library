package com.library.lms.dto;

import java.time.LocalDateTime;

import com.library.lms.entity.Role;

/**
 * A registration waiting for a decision, as the person deciding sees it.
 *
 * <p>Enough to judge the application and nothing more: who applied, for what,
 * where, and when. No password, no hash, no token - and the email is included
 * because the approver needs to recognise a colleague, which is the same
 * judgement {@code GET /api/users} already supports for staff.</p>
 */
public record PendingRegistrationResponse(Long userId, String username, String email, String fullName,
        Role role, Long libraryId, String libraryName, LocalDateTime appliedAt) {
}
