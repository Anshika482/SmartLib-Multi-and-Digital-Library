package com.library.lms.service;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * The image types a cover may be, and how to tell one from the bytes.
 *
 * <p><b>The declared type is not trusted.</b> A multipart part carries whatever
 * content type the caller typed; it is a hint, not a fact. Each constant below
 * also knows its file signature, and an upload is accepted only when the bytes
 * actually begin like the type they claim to be. That is what stops a script or
 * an HTML file being stored under an image name and later served back.
 *
 * <p>Three types, chosen because they are what browsers display and what a
 * scanner or a phone produces. Anything else - SVG especially, which is a
 * document that can carry script - is refused.
 */
public enum CoverImageType {

    /** FF D8 FF - every JPEG begins with a start-of-image marker. */
    JPEG("image/jpeg", new int[]{0xFF, 0xD8, 0xFF}),

    /** 89 50 4E 47 0D 0A 1A 0A - the PNG signature. */
    PNG("image/png", new int[]{0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}),

    /** RIFF....WEBP - checked in two pieces, since bytes 4-7 are the length. */
    WEBP("image/webp", new int[]{0x52, 0x49, 0x46, 0x46});

    private final String contentType;

    private final int[] signature;

    CoverImageType(String contentType, int[] signature) {
        this.contentType = contentType;
        this.signature = signature;
    }

    public String contentType() {
        return contentType;
    }

    /** The types a person should be told about, for an error message. */
    public static String accepted() {
        return Arrays.stream(values()).map(CoverImageType::contentType).reduce((a, b) -> a + ", " + b).orElse("");
    }

    /**
     * The type these bytes actually are, whatever they were declared as.
     *
     * @return the matching type, or empty when the bytes are not one of them
     */
    public static Optional<CoverImageType> of(byte[] bytes) {
        if (bytes == null) {
            return Optional.empty();
        }

        for (CoverImageType type : values()) {
            if (type.matches(bytes)) {
                return Optional.of(type);
            }
        }

        return Optional.empty();
    }

    /** Whether a declared content type is one this application accepts at all. */
    public static boolean isAccepted(String declaredContentType) {
        if (declaredContentType == null) {
            return false;
        }

        String declared = declaredContentType.toLowerCase(Locale.ROOT).trim();

        return Arrays.stream(values()).anyMatch(type -> type.contentType.equals(declared));
    }

    private boolean matches(byte[] bytes) {
        if (bytes.length < signature.length) {
            return false;
        }

        for (int index = 0; index < signature.length; index++) {
            if ((bytes[index] & 0xFF) != signature[index]) {
                return false;
            }
        }

        // RIFF alone is also AVI and WAV; a WebP says so at bytes 8-11.
        if (this == WEBP) {
            return bytes.length >= 12
                    && (bytes[8] & 0xFF) == 0x57
                    && (bytes[9] & 0xFF) == 0x45
                    && (bytes[10] & 0xFF) == 0x42
                    && (bytes[11] & 0xFF) == 0x50;
        }

        return true;
    }
}
