package com.library.lms.entity;

import java.util.Set;

/**
 * Where a member's request for a book has got to.
 *
 * <p>The order below is the order the column declares, and new values are only
 * ever appended - MySQL stores an ENUM by position, so reordering these would
 * silently change what every stored row means.
 *
 * <p>The machine, in full:
 *
 * <pre>
 *   REQUESTED --approve--> APPROVED --issue--> FULFILLED
 *        |                     |
 *        |--reject--> REJECTED |
 *        |                     |
 *        '------- cancel ------'--> CANCELLED
 * </pre>
 *
 * <p><b>Approval does not issue the book.</b> They are two states because they
 * are two decisions by two different acts: a member of staff agreeing the
 * member may have the copy, and a copy actually leaving the shelf. Collapsing
 * them would mean a book was recorded as borrowed by somebody who had not yet
 * come to collect it, and its availability would drop for a loan that might
 * never be picked up.
 *
 * <p>{@link #REQUESTED} and {@link #APPROVED} are the <b>active</b> states: a
 * member may hold only one active request per book, and only an active request
 * can be cancelled. The other three are terminal, and nothing moves out of
 * them - which is what makes a replayed or duplicated call a refusal rather
 * than a second effect.
 */
public enum BorrowRequestStatus {

    /** Waiting on a member of staff. */
    REQUESTED,

    /** Agreed, and waiting for the copy to be handed over. */
    APPROVED,

    /** Refused by staff. Terminal. */
    REJECTED,

    /** Withdrawn by the member before collection. Terminal. */
    CANCELLED,

    /** The copy was issued; the loan carries it from here. Terminal. */
    FULFILLED;

    /**
     * The states in which a request is still going somewhere.
     *
     * <p>Named once, here, so the duplicate check, the cancel rule and the
     * queries cannot drift apart about what "active" means.</p>
     */
    public static final Set<BorrowRequestStatus> ACTIVE = Set.of(REQUESTED, APPROVED);

    /** Whether a request in this state is still live. */
    public boolean isActive() {
        return ACTIVE.contains(this);
    }
}
