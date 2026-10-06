package com.library.lms.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import lombok.Getter;
import lombok.Setter;

/**
 * One registration, whichever kind it is.
 *
 * <p>The constraints mirror {@code CreateUserRequest} exactly - same lengths,
 * same BCrypt 72-byte password ceiling - so an account made by registering and
 * one made by an administrator are subject to the same rules.</p>
 *
 * <p>There is no role field. {@link #type} says what is being applied for, and
 * the service decides what that is worth.</p>
 *
 * <p>{@code libraryId} is required for a member or a librarian, and
 * {@code libraryName} for an administrator opening a new one. Which one is
 * required is checked in the service, where the type is known, rather than by
 * an annotation that cannot see it.</p>
 */
@Getter
@Setter
public class RegistrationRequest {

    @NotNull(message = "A registration type is required")
    private RegistrationType type;

    @NotBlank(message = "Username is required")
    @Size(min = 3, max = 255, message = "Username must be between 3 and 255 characters")
    private String username;

    @NotBlank(message = "Email is required")
    @Email(message = "Email must be a valid address")
    @Size(max = 255, message = "Email must not exceed 255 characters")
    private String email;

    @NotBlank(message = "Full name is required")
    @Size(max = 255, message = "Full name must not exceed 255 characters")
    private String fullName;

    @NotBlank(message = "Password is required")
    @Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters")
    private String password;

    /** The library being joined. Required for MEMBER and LIBRARIAN, ignored otherwise. */
    private Long libraryId;

    /** The library being opened. Required for ADMIN, ignored otherwise. */
    @Size(max = 100, message = "Library name must not exceed 100 characters")
    private String libraryName;
}
