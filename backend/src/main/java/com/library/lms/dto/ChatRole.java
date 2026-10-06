package com.library.lms.dto;

/**
 * Who said a line of a conversation.
 *
 * <p>Two values, and deliberately not a free string: a client that could name
 * the role "system" could try to pass instructions off as the ones the server
 * sets. There is no system role here because the system prompt is the server's
 * alone and is rebuilt on every request.</p>
 */
public enum ChatRole {

    /** The person asking. */
    USER,

    /** The assistant's own earlier answer, as the client reports it. */
    ASSISTANT
}
