package com.library.lms.service;

import java.io.InputStream;
import java.util.Optional;

/**
 * Where cover images are kept.
 *
 * <p>The seam between the application and whatever actually holds the bytes.
 * Everything above this interface deals in <b>keys</b> - opaque strings this
 * storage issues and understands - so moving from a directory on disk to object
 * storage or a CDN is a new implementation of these four methods and nothing
 * else. No caller builds a key, parses one, or assumes it looks like a path.
 *
 * <p><b>A key is never shown to anybody.</b> It describes how this deployment
 * stores files. Callers are given {@code /api/books/{id}/cover}, which stays
 * correct whatever the storage becomes.
 *
 * <p>Implementations must treat a key that came from outside as hostile: it may
 * name a file that no longer exists, and it must never be able to name a file
 * outside the store.
 */
public interface CoverImageStorage {

    /**
     * Stores an image and returns the key that finds it again.
     *
     * @param libraryId the library the book belongs to, so the layout can keep
     *                  one library's files apart from another's
     * @param bookId    the book the image belongs to
     * @param contentType one of the types the caller has already validated
     * @param bytes     the image
     * @return the key to store on the book
     */
    String store(Long libraryId, Long bookId, String contentType, byte[] bytes);

    /** The stored image, or empty when the key names nothing this store holds. */
    Optional<StoredCoverImage> read(String key);

    /**
     * Removes the image a key names.
     *
     * <p>Removing something already gone is not an error: the row and the file
     * can only be updated one after the other, so a caller must be able to
     * finish tidying up after a half-completed change.
     */
    void delete(String key);

    /** One stored image, ready to be written to a response. */
    record StoredCoverImage(InputStream content, String contentType, long sizeInBytes) {
    }
}
