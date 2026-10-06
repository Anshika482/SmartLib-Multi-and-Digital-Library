package com.library.lms.dto;

import com.library.lms.entity.RegistrationStatus;

/**
 * What a registration produced.
 *
 * <p>No id, no token and no account detail. A registration that is waiting on
 * somebody has nothing to hand back yet, and one that is approved is told to
 * sign in like anybody else - issuing a token here would make registering a way
 * to become authenticated in one step.</p>
 *
 * @param status  APPROVED when the account may sign in now, PENDING when it waits
 * @param message one sentence for the person, safe to show as it is
 */
public record RegistrationResponse(RegistrationStatus status, String message) {
}
