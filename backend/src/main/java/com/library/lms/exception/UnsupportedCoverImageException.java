package com.library.lms.exception;

/**
 * The upload is not one of the image types a cover may be.
 *
 * <p>Carries the accepted list, so the message is actionable. Raised both for
 * a type this application does not accept and for bytes that are not actually
 * the type they were declared as - the caller is told the same thing either
 * way, because to them it is the same mistake.
 */
public class UnsupportedCoverImageException extends RuntimeException {

    public UnsupportedCoverImageException(String acceptedTypes) {
        super("A cover must be one of: " + acceptedTypes + ".");
    }
}
