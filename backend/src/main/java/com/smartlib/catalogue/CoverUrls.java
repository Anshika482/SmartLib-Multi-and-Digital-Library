package com.smartlib.catalogue;

import java.time.Instant;

import org.springframework.stereotype.Component;

import com.smartlib.common.config.AppProperties;

/** Resolves the display URL for a book cover: local upload -> resolved external cover -> (frontend placeholder). */
@Component
public class CoverUrls {

    private final String coversBase;

    public CoverUrls(AppProperties properties) {
        this.coversBase = properties.covers().openLibraryCoversUrl();
    }

    public String coverUrl(long bookId, String source, String coverKey, String externalRef, Instant updatedAt) {
        if (source == null) {
            return null;
        }
        return switch (source) {
            case "LOCAL" -> coverKey == null ? null : "/api/public/covers/" + bookId + "?v=" + (updatedAt == null ? 0 : updatedAt.toEpochMilli());
            case "OPEN_LIBRARY" -> externalRef == null ? null : coversBase + "/b/id/" + externalRef + "-M.jpg";
            case "GOOGLE_BOOKS" -> externalRef;
            default -> null;
        };
    }

    /** Direct ISBN cover attempt used by the frontend while (or if) a cover has not been resolved yet. */
    public String isbnCoverUrl(String isbn13, String source) {
        if (isbn13 == null || "LOCAL".equals(source) || "NONE".equals(source)) {
            return null;
        }
        return coversBase + "/b/isbn/" + isbn13 + "-M.jpg?default=false";
    }
}
