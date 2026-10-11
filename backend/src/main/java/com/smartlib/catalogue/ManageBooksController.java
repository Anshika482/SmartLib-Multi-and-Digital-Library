package com.smartlib.catalogue;

import java.io.IOException;
import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.smartlib.common.web.PageResponse;
import com.smartlib.security.LibraryScope;

@RestController
@RequestMapping("/api/manage/libraries/{libraryId}")
public class ManageBooksController {

    private final BookService books;

    public ManageBooksController(BookService books) {
        this.books = books;
    }

    @GetMapping("/holdings")
    public PageResponse<BookService.HoldingRow> list(LibraryScope scope, @RequestParam(required = false) String q,
                                                     @RequestParam(required = false) String status,
                                                     @RequestParam(required = false) Integer page,
                                                     @RequestParam(required = false) Integer size) {
        return books.list(scope, q, status, PageResponse.clampPage(page), PageResponse.clampSize(size, 20, 100));
    }

    @PostMapping("/holdings")
    public ResponseEntity<BookService.HoldingRow> add(LibraryScope scope, @Valid @RequestBody BookService.AddHoldingRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(books.addHolding(scope, req));
    }

    @GetMapping("/holdings/{holdingId}")
    public BookService.HoldingDetail detail(LibraryScope scope, @PathVariable long holdingId) {
        return books.detail(scope, holdingId);
    }

    @PutMapping("/holdings/{holdingId}")
    public ResponseEntity<Void> update(LibraryScope scope, @PathVariable long holdingId,
                                       @Valid @RequestBody BookService.UpdateHoldingRequest req) {
        books.updateHolding(scope, holdingId, req);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/holdings/{holdingId}")
    public ResponseEntity<Void> withdraw(LibraryScope scope, @PathVariable long holdingId) {
        books.withdrawHolding(scope, holdingId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/holdings/{holdingId}/copies")
    public List<BookService.CopyRow> addCopies(LibraryScope scope, @PathVariable long holdingId,
                                               @Valid @RequestBody BookService.AddCopiesRequest req) {
        return books.addCopies(scope, holdingId, req.count());
    }

    @PatchMapping("/copies/{copyId}")
    public BookService.CopyRow updateCopy(LibraryScope scope, @PathVariable long copyId,
                                          @Valid @RequestBody BookService.CopyStatusRequest req) {
        return books.updateCopyStatus(scope, copyId, req);
    }

    @PutMapping("/books/{bookId}")
    public ResponseEntity<Void> updateBook(LibraryScope scope, @PathVariable long bookId, @Valid @RequestBody BookService.BookInput req) {
        books.updateBook(scope, bookId, req);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(path = "/books/{bookId}/cover", consumes = "multipart/form-data")
    public ResponseEntity<Void> uploadCover(LibraryScope scope, @PathVariable long bookId, @RequestPart("file") MultipartFile file)
            throws IOException {
        books.uploadCover(scope, bookId, file);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/isbn-lookup")
    public BookService.IsbnLookup isbnLookup(LibraryScope scope, @RequestParam String isbn) {
        return books.isbnLookup(scope, isbn);
    }
}
