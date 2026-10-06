package com.library.lms.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.library.lms.exception.CoverImageStorageException;

/**
 * Cover images in a directory on disk.
 *
 * <p>The default implementation, and the one a single-node deployment wants.
 * The layout is {@code <root>/<libraryId>/<bookId>/<random>.<ext>}: a library's
 * files sit under its own id, which makes a per-library export or deletion a
 * directory operation, and the random name means a key cannot be guessed from a
 * book id.
 *
 * <p><b>A key from outside is treated as hostile.</b> Every read and delete
 * resolves the key against the root and then checks the result is still inside
 * it, so a key carrying {@code ../} names nothing. Keys are only ever issued by
 * {@link #store}, but the check does not depend on that remaining true.
 *
 * <p><b>Write then move.</b> A new file is written to a temporary name in the
 * same directory and moved into place, so a reader never sees a half-written
 * image and a failed upload leaves nothing behind.
 */
@Component
public class FilesystemCoverImageStorage implements CoverImageStorage {

    /** The extension each accepted type is stored with. */
    private static final Map<String, String> EXTENSIONS = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp");

    private static final Logger log = LoggerFactory.getLogger(FilesystemCoverImageStorage.class);

    private final Path root;

    public FilesystemCoverImageStorage(@Value("${covers.storage.directory:./data/covers}") String directory) {
        this.root = Path.of(directory).toAbsolutePath().normalize();
    }

    @Override
    public String store(Long libraryId, Long bookId, String contentType, byte[] bytes) {
        String extension = EXTENSIONS.getOrDefault(contentType.toLowerCase(Locale.ROOT), "bin");
        String key = libraryId + "/" + bookId + "/" + UUID.randomUUID() + "." + extension;

        Path destination = resolve(key);

        try {
            Files.createDirectories(destination.getParent());

            // Written beside the destination and moved, so a reader never sees a
            // partial file and a failure leaves no half-image behind.
            Path partial = Files.createTempFile(destination.getParent(), "upload-", ".part");
            try {
                Files.write(partial, bytes);
                Files.move(partial, destination, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException failure) {
                Files.deleteIfExists(partial);
                throw failure;
            }
        } catch (IOException failure) {
            // The path is logged, never returned: it describes this deployment.
            log.error("Could not store a cover image at {}", destination, failure);
            throw new CoverImageStorageException();
        }

        return key;
    }

    @Override
    public Optional<StoredCoverImage> read(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }

        Path file;
        try {
            file = resolve(key);
        } catch (CoverImageStorageException outsideTheStore) {
            return Optional.empty();
        }

        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }

        try {
            long size = Files.size(file);
            InputStream content = Files.newInputStream(file);
            return Optional.of(new StoredCoverImage(content, contentTypeOf(key), size));
        } catch (IOException failure) {
            log.error("Could not read a cover image at {}", file, failure);
            throw new CoverImageStorageException();
        }
    }

    @Override
    public void delete(String key) {
        if (key == null || key.isBlank()) {
            return;
        }

        try {
            Files.deleteIfExists(resolve(key));
        } catch (CoverImageStorageException outsideTheStore) {
            // Nothing this store holds, so nothing to remove.
            log.warn("Ignored a delete for a key outside the cover store");
        } catch (IOException failure) {
            log.error("Could not delete a cover image for a stored key", failure);
            throw new CoverImageStorageException();
        }
    }

    /**
     * The file a key names, proven to be inside the store.
     *
     * @throws CoverImageStorageException if the key escapes the root
     */
    private Path resolve(String key) {
        Path candidate = root.resolve(key).normalize();

        if (!candidate.startsWith(root)) {
            // A key is only ever issued by store(), so this is unreachable
            // today. It is here because "unreachable today" is not a property a
            // path check should rely on.
            throw new CoverImageStorageException();
        }

        return candidate;
    }

    /** The type a stored file is served as, from the extension this class wrote. */
    private static String contentTypeOf(String key) {
        String lower = key.toLowerCase(Locale.ROOT);

        for (Map.Entry<String, String> entry : EXTENSIONS.entrySet()) {
            if (lower.endsWith("." + entry.getValue())) {
                return entry.getKey();
            }
        }

        return "application/octet-stream";
    }
}
