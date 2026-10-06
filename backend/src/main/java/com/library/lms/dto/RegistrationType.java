package com.library.lms.dto;

/**
 * What somebody is applying to be.
 *
 * <p><b>This is not a role, and it is deliberately a different type.</b> The
 * request names one of these; the service maps it to a {@code Role} from a
 * fixed table it owns. There is no path from request text to an authority,
 * which is what stops somebody registering themselves as an administrator by
 * editing a field - the field they can edit has no privileged value in it.</p>
 *
 * <p>{@code SUPER_ADMIN} is absent for the same reason: not omitted from a
 * check, but absent from the vocabulary a request can use at all.</p>
 */
public enum RegistrationType {

    /** Joins an existing library and may use it at once. */
    MEMBER,

    /** Applies to work at an existing library. An administrator of that library decides. */
    LIBRARIAN,

    /** Applies to open a new library and administer it. A super administrator decides. */
    ADMIN
}
