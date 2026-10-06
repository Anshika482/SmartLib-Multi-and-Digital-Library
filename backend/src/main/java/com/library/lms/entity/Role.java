package com.library.lms.entity;

/**
 * The access levels a {@link User} can hold.
 *
 * <p>The names are fixed by the existing {@code users.role} column, which is a
 * MySQL {@code ENUM}. They must match those strings exactly, character for
 * character, because the entity stores the constant's <i>name</i> - see the
 * {@code @Enumerated(STRING)} on {@link User#getRole()}. Renaming a constant
 * here would make existing rows unreadable.</p>
 *
 * <p><b>Adding a constant needs a migration.</b> MySQL will not store a value
 * the {@code ENUM} does not list, and Hibernate's {@code ddl-auto=update} does
 * not widen one - it only adds columns and tables. {@code V9} widens the column
 * for {@link #ROLE_SUPER_ADMIN}; a database built before that migration must
 * have it applied, or the first row with the new value fails to insert.</p>
 *
 * <p>The {@code ROLE_} prefix is Spring Security's convention for an authority,
 * and {@code SecurityConfig} matches on these strings directly.</p>
 */
public enum Role {

    /** Full access within one library, including managing its other users. */
    ROLE_ADMIN,

    /** Manages the catalogue and issues or returns books. */
    ROLE_LIBRARIAN,

    /** Borrows books. */
    ROLE_MEMBER,

    /**
     * System level, across every library.
     *
     * <p>Never self-registered, and never produced by any request: no
     * registration type maps to it, so there is no input that can ask for it.
     * It exists to decide applications that would open a new library, which is
     * the one decision no single library's administrator should make.</p>
     *
     * <p>The account still carries a {@code library_id}, because that column is
     * NOT NULL and records where the account was created. The cross-library
     * authority comes from this role, not from the absence of a library.</p>
     *
     * <p><b>Declared last, and that is deliberate.</b> MySQL sorts an ENUM by
     * each value's position in its definition, so appending leaves the ordinal
     * of every value already stored exactly where it was. {@code V9} appends it
     * in the same place, and {@code FlywayMigrationIntegrationTest} checks the
     * two orders still agree.</p>
     */
    ROLE_SUPER_ADMIN
}
