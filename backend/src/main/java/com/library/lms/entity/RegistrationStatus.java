package com.library.lms.entity;

/**
 * How an account came to exist, and whether anybody still has to agree to it.
 *
 * <p>Separate from {@code enabled}, which stays exactly what it was: the switch
 * that decides whether authentication succeeds. The two answer different
 * questions - {@code enabled} says whether the account may sign in now, this
 * says why. An administrator disabling a member and a librarian application
 * nobody has looked at both have {@code enabled = false}; only this tells them
 * apart, and only this distinguishes either from a refusal.</p>
 *
 * <p>{@link #APPROVED} is the default for a reason: every account that existed
 * before registration did, and every account an administrator creates directly,
 * is approved by definition. A new column with this default changes nothing
 * about accounts already in the database.</p>
 */
public enum RegistrationStatus {

    /** Nobody needs to agree to this account. It signs in on its own credentials. */
    APPROVED,

    /** Applied for and waiting. The account exists, is disabled, and cannot sign in. */
    PENDING,

    /** Applied for and refused. The account stays disabled; the row is kept so the
     *  username and email remain taken and the decision remains auditable. */
    REJECTED
}
