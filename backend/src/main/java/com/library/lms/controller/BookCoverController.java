package com.library.lms.controller;

import java.io.IOException;
import java.io.InputStream;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.library.lms.exception.UnsupportedCoverImageException;
import com.library.lms.service.BookCoverService;
import com.library.lms.service.CoverImageStorage;
import com.library.lms.service.CoverImageType;

import java.time.Duration;

/**
 * A book's cover image.
 *
 * <p>Four operations on one sub-resource. Uploading and replacing are the same
 * thing - a book has at most one cover - so POST and PUT both set it, which
 * saves a caller having to know whether one is already there.
 *
 * <p>Every method takes the book id from the path and the caller from the
 * filter chain. The library is never in the request: {@code BookCoverService}
 * derives it from the account, so a book in another library answers 404 exactly
 * as a book that does not exist does.
 *
 * <p><b>No response carries a storage key or a path.</b> Reading a cover
 * returns the bytes; the rest return no body at all.
 */
@RestController
@RequestMapping("/api/books/{bookId}/cover")
public class BookCoverController {

    private final BookCoverService bookCoverService;

    public BookCoverController(BookCoverService bookCoverService) {
        this.bookCoverService = bookCoverService;
    }

    /**
     * POST or PUT /api/books/{bookId}/cover - sets the cover, replacing any.
     *
     * <p>204, because there is nothing useful to return: the caller knows the
     * book, and the cover is read back from this same URL.
     *
     * @param file the image, as multipart form data under {@code file}
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Void> upload(@PathVariable Long bookId,
            @RequestParam("file") MultipartFile file,
            Authentication authentication) {
        return set(bookId, file, authentication);
    }

    @PutMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Void> replace(@PathVariable Long bookId,
            @RequestParam("file") MultipartFile file,
            Authentication authentication) {
        return set(bookId, file, authentication);
    }

    private ResponseEntity<Void> set(Long bookId, MultipartFile file, Authentication authentication) {
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException unreadable) {
            // The part arrived but could not be read. To the caller this is the
            // same as sending something that is not an image.
            throw new UnsupportedCoverImageException(CoverImageType.accepted());
        }

        bookCoverService.put(bookId, file.getContentType(), bytes, authentication.getName());

        return ResponseEntity.noContent().build();
    }

    /** DELETE /api/books/{bookId}/cover - removes it. 204 whether or not there was one. */
    @DeleteMapping
    public ResponseEntity<Void> remove(@PathVariable Long bookId, Authentication authentication) {
        bookCoverService.remove(bookId, authentication.getName());

        return ResponseEntity.noContent().build();
    }

    /**
     * GET /api/books/{bookId}/cover - the image itself.
     *
     * <p>Private caching: a cover belongs to one library and is only served to
     * its members, so a shared cache must not keep it. The bytes stream from
     * storage rather than being read into memory first.
     */
    @GetMapping
    public ResponseEntity<InputStreamResource> read(@PathVariable Long bookId, Authentication authentication) {
        CoverImageStorage.StoredCoverImage cover = bookCoverService.read(bookId, authentication.getName());

        InputStream content = cover.content();

        return ResponseEntity.status(HttpStatus.OK)
                .contentType(MediaType.parseMediaType(cover.contentType()))
                .contentLength(cover.sizeInBytes())
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                // Served as an image and never as a document, whatever a browser
                // might otherwise decide to do with the bytes.
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .header("X-Content-Type-Options", "nosniff")
                .body(new InputStreamResource(content));
    }

}
