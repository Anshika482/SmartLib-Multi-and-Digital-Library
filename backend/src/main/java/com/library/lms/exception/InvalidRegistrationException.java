package com.library.lms.exception;

/**
 * A registration that cannot be carried out as asked.
 *
 * <p>Carries a sentence written for the person: which field is missing for the
 * kind of registration they chose. It never says whether a username or an email
 * is already taken - {@code DuplicateAccountException} answers that with one
 * fixed sentence covering both, so registering cannot be used to find out which
 * accounts exist.</p>
 */
public class InvalidRegistrationException extends RuntimeException {

    public InvalidRegistrationException(String message) {
        super(message);
    }
}
