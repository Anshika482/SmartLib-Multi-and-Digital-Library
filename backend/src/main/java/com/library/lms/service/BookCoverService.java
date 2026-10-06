package com.library.lms.service;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.library.lms.entity.Book;
import com.library.lms.entity.User;
import com.library.lms.exception.BookNotFoundException;
import com.library.lms.exception.CoverImageNotFoundException;
import com.library.lms.exception.CoverImageTooLargeException;
import com.library.lms.exception.UnsupportedCoverImageException;
import com.library.lms.exception.UserNotFoundException;
import com.library.lms.repository.BookRepository;
import com.library.lms.repository.UserRepository;

/**
 * A book's cover image: putting one on, replacing it, taking it off, reading it.
 *
 * <p><b>Every operation is scoped to the caller's own library</b>, by the same
 * mechanism the rest of the catalogue uses: the book is looked up with
 * {@code findByIdAndLibraryId}, and a book in another library arrives as an
 * empty Optional and leaves as the same 404 a book that never existed gets.
 * There is no path here that takes a library from a request.
 *
 * <p><b>The bytes never enter the database.</b> The row holds a storage key;
 * {@link CoverImageStorage} holds the file. Replacing a cover writes the new
 * file, points the row at it, and only then removes the old one - so a failure
 * anywhere leaves a book pointing at a file that exists.
 *
 * <p><b>The declared content type is not trusted.</b> An upload is accepted
 * only when its bytes actually begin like one of the accepted image types;
 * see {@link CoverImageType}.
 */
@Service
public class BookCoverService {

    private static final Logger log = LoggerFactory.getLogger(BookCoverService.class);

    private final BookRepository bookRepository;

    private final UserRepository userRepository;

    private final CoverImageStorage storage;

    private final long maximumBytes;

    public BookCoverService(BookRepository bookRepository, UserRepository userRepository,
            CoverImageStorage storage,
            @Value("${covers.max-size-bytes:2097152}") long maximumBytes) {
        this.bookRepository = bookRepository;
        this.userRepository = userRepository;
        this.storage = storage;
        this.maximumBytes = maximumBytes;
    }

    /** The largest a cover may be, so a refusal can say. */
    public long maximumBytes() {
        return maximumBytes;
    }

    /**
     * Puts a cover on a book, replacing whatever was there.
     *
     * @throws BookNotFoundException          if the book is not in the caller's library
     * @throws UnsupportedCoverImageException if the bytes are not an accepted image
     * @throws CoverImageTooLargeException    if the upload is over the limit
     */
    @Transactional
    public void put(Long bookId, String declaredContentType, byte[] bytes, String authenticatedUsername) {
        Book book = bookInCallersLibrary(bookId, authenticatedUsername);

        if (bytes == null || bytes.length == 0) {
            throw new UnsupportedCoverImageException(CoverImageType.accepted());
        }

        if (bytes.length > maximumBytes) {
            throw new CoverImageTooLargeException(maximumBytes);
        }

        // The declared type must be one we accept, and the bytes must actually
        // be that kind of image. Either failure is the same mistake to a caller.
        if (!CoverImageType.isAccepted(declaredContentType)) {
            throw new UnsupportedCoverImageException(CoverImageType.accepted());
        }

        CoverImageType actual = CoverImageType.of(bytes)
                .orElseThrow(() -> new UnsupportedCoverImageException(CoverImageType.accepted()));

        String previous = book.getCoverImageKey();

        // Stored under the type the bytes are, not the one they claimed.
        String key = storage.store(book.getLibrary().getId(), book.getId(), actual.contentType(), bytes);

        book.setCoverImageKey(key);
        bookRepository.save(book);

        // Only once the row points at the new file. A failure before this leaves
        // the book on its old cover; a failure after leaves one orphaned file,
        // which is the harmless direction.
        if (previous != null && !previous.equals(key)) {
            storage.delete(previous);
        }

        log.info("Cover set: bookId={} libraryId={} type={} bytes={}",
                book.getId(), book.getLibrary().getId(), actual.contentType(), bytes.length);
    }

    /**
     * Takes the cover off a book.
     *
     * <p>Removing a cover from a book that has none is not an error: the caller
     * asked for it to be gone, and it is.
     */
    @Transactional
    public void remove(Long bookId, String authenticatedUsername) {
        Book book = bookInCallersLibrary(bookId, authenticatedUsername);
        String key = book.getCoverImageKey();

        if (key == null) {
            return;
        }

        book.setCoverImageKey(null);
        bookRepository.save(book);

        storage.delete(key);

        log.info("Cover removed: bookId={} libraryId={}", book.getId(), book.getLibrary().getId());
    }

    /**
     * The cover of a book in the caller's library.
     *
     * @throws BookNotFoundException      if the book is not in the caller's library
     * @throws CoverImageNotFoundException if it has no cover, or the file is gone
     */
    @Transactional(readOnly = true)
    public CoverImageStorage.StoredCoverImage read(Long bookId, String authenticatedUsername) {
        Book book = bookInCallersLibrary(bookId, authenticatedUsername);

        return Optional.ofNullable(book.getCoverImageKey())
                .flatMap(storage::read)
                .orElseThrow(CoverImageNotFoundException::new);
    }

    /**
     * The book, if it is one this caller may touch.
     *
     * <p>The isolation rule, in one place. A book belonging to another library
     * is indistinguishable from one that does not exist - splitting them would
     * turn the id into a directory of a neighbour's stock.
     */
    private Book bookInCallersLibrary(Long bookId, String authenticatedUsername) {
        User caller = userRepository.findByUsername(authenticatedUsername)
                .orElseThrow(() -> new UserNotFoundException(authenticatedUsername));

        return bookRepository.findByIdAndLibraryId(bookId, caller.getLibrary().getId())
                .orElseThrow(() -> new BookNotFoundException(bookId));
    }
}
