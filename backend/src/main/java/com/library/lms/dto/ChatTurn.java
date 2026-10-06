package com.library.lms.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * One earlier line of the conversation, as the client remembers it.
 *
 * <p><b>This is client-supplied and therefore untrusted.</b> Nothing about it is
 * believed: an {@code ASSISTANT} turn is only a claim that the assistant said
 * it, and neither role carries any authority. The caller's library and role are
 * resolved from their token on every request, so no amount of invented history
 * can widen what they may see.
 *
 * <p>Held in the request rather than on the server because a conversation is the
 * client's to remember. Nothing is stored, so there is no transcript to leak, to
 * mix between a visitor and a member, or to keep beyond its usefulness.
 *
 * @param role    who said it
 * @param message what was said, bounded like the question itself
 */
public record ChatTurn(
        @NotNull(message = "Each earlier message needs a role")
        ChatRole role,

        @NotBlank(message = "An earlier message cannot be blank")
        @Size(max = 1000, message = "An earlier message must not exceed 1000 characters")
        String message) {
}
