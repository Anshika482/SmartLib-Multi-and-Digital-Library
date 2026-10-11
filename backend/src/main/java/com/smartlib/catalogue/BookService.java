package com.smartlib.catalogue;

import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.io.ByteArrayInputStream;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import com.smartlib.audit.AuditActions;
import com.smartlib.audit.AuditService;
import com.smartlib.common.error.ApiException;
import com.smartlib.common.error.ErrorCodes;
import com.smartlib.common.storage.FileSniffer;
import com.smartlib.common.storage.StorageService;
import com.smartlib.common.util.Texts;
import com.smartlib.common.web.PageResponse;
import com.smartlib.library.Library;
import com.smartlib.library.LibraryRepository;
import com.smartlib.security.LibraryScope;

/** Staff-side catalogue management, always scoped to the caller's library. */
@Service
public class BookService {

    private static final List<CopyStatus> MANUAL_FROM = List.of(CopyStatus.AVAILABLE, CopyStatus.DAMAGED, CopyStatus.LOST);
    private static final long MAX_COVER_BYTES = 2L * 1024 * 1024;

    private final BookRepository books;
    private final AuthorRepository authors;
    private final CategoryRepository categories;
    private final LibraryHoldingRepository holdings;
    private final BookCopyRepository copies;
    private final LibraryRepository libraries;
    private final NamedParameterJdbcTemplate jdbc;
    private final CatalogueQueryService catalogue;
    private final CoverResolver coverResolver;
    private final BookMetadataClient metadata;
    private final StorageService storage;
    private final AuditService audit;

    public BookService(BookRepository books, AuthorRepository authors, CategoryRepository categories, LibraryHoldingRepository holdings,
                       BookCopyRepository copies, LibraryRepository libraries, NamedParameterJdbcTemplate jdbc,
                       CatalogueQueryService catalogue, CoverResolver coverResolver, BookMetadataClient metadata,
                       StorageService storage, AuditService audit) {
        this.books = books;
        this.authors = authors;
        this.categories = categories;
        this.holdings = holdings;
        this.copies = copies;
        this.libraries = libraries;
        this.jdbc = jdbc;
        this.catalogue = catalogue;
        this.coverResolver = coverResolver;
        this.metadata = metadata;
        this.storage = storage;
        this.audit = audit;
    }

    // ---------- DTOs ----------

    public record BookInput(
            @NotBlank(message = "Title is required") @Size(max = 300) String title,
            @Size(max = 300) String subtitle,
            @NotEmpty(message = "At least one author is required") @Size(max = 10) List<@NotBlank @Size(max = 160) String> authors,
            Long categoryId,
            @Size(max = 200) String publisher,
            @Min(value = 1400, message = "Enter a valid year") @Max(value = 2100, message = "Enter a valid year") Integer publishedYear,
            @Size(max = 40) String language,
            @Min(1) @Max(20000) Integer pageCount,
            @Size(max = 5000) String description,
            @Size(max = 20) String isbn) {
    }

    public record AddHoldingRequest(Long bookId, @Size(max = 20) String isbn, @Valid BookInput book, @Size(max = 64) String shelfLocation,
                                    @Min(0) @Max(100) Integer copies) {
    }

    public record UpdateHoldingRequest(@Size(max = 64) String shelfLocation) {
    }

    public record AddCopiesRequest(@NotNull @Min(1) @Max(100) Integer count) {
    }

    public record CopyStatusRequest(@NotNull CopyStatus status, @Size(max = 500) String conditionNote) {
    }

    public record HoldingRow(long holdingId, String status, String shelfLocation, CatalogueDtos.BookSummary book, int total,
                             int available, int reserved, int borrowed, int unavailable, long pendingRequests, Instant addedAt) {
    }

    public record CopyRow(long id, String accessionNo, CopyStatus status, String conditionNote, Instant acquiredAt, Instant updatedAt) {
    }

    public record HoldingDetail(HoldingRow holding, CatalogueDtos.BookDetails book, Long categoryId, String description,
                                List<CopyRow> copies, boolean canEditBook, int librariesHoldingBook) {
    }

    public record IsbnLookup(String isbn13, String isbn10, Long existingBookId, boolean alreadyInLibrary,
                             BookMetadataClient.BookMetadata metadata) {
    }

    // ---------- queries ----------

    public PageResponse<HoldingRow> list(LibraryScope scope, String q, String status, int page, int size) {
        MapSqlParameterSource p = new MapSqlParameterSource("lib", scope.libraryId());
        StringBuilder where = new StringBuilder(" WHERE h.library_id = :lib");
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            where.append(" AND h.status = :status");
            p.addValue("status", status.toUpperCase());
        } else if (status == null || status.isBlank()) {
            where.append(" AND h.status = 'ACTIVE'");
        }
        if (q != null && !q.isBlank()) {
            Optional<IsbnUtils.Isbn> isbn = IsbnUtils.looksLikeIsbn(q) ? IsbnUtils.parse(q) : Optional.empty();
            if (isbn.isPresent()) {
                where.append(" AND b.isbn13 = :isbn");
                p.addValue("isbn", isbn.get().isbn13());
            } else {
                where.append(" AND b.search_text LIKE :q");
                p.addValue("q", Texts.likeContains(q.trim()));
            }
        }
        String from = " FROM library_holdings h JOIN books b ON b.id = h.book_id";
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + from + where, p, Long.class);
        p.addValue("limit", size).addValue("offset", (long) page * size);
        List<long[]> raw = new ArrayList<>();
        List<Object[]> meta = new ArrayList<>();
        jdbc.query("""
                SELECT h.id, h.book_id, h.status, h.shelf_location, h.created_at,
                  (SELECT COUNT(*) FROM book_copies c WHERE c.holding_id = h.id AND c.status IN ('AVAILABLE','RESERVED','BORROWED')) AS total,
                  (SELECT COUNT(*) FROM book_copies c WHERE c.holding_id = h.id AND c.status = 'AVAILABLE') AS available,
                  (SELECT COUNT(*) FROM book_copies c WHERE c.holding_id = h.id AND c.status = 'RESERVED') AS reserved,
                  (SELECT COUNT(*) FROM book_copies c WHERE c.holding_id = h.id AND c.status = 'BORROWED') AS borrowed,
                  (SELECT COUNT(*) FROM book_copies c WHERE c.holding_id = h.id AND c.status IN ('LOST','DAMAGED')) AS unavailable,
                  (SELECT COUNT(*) FROM borrow_requests r WHERE r.holding_id = h.id AND r.status = 'PENDING') AS pending
                """ + from + where + " ORDER BY b.title ASC, h.id ASC LIMIT :limit OFFSET :offset", p, rs -> {
            raw.add(new long[]{rs.getLong("id"), rs.getLong("book_id"), rs.getLong("total"), rs.getLong("available"),
                    rs.getLong("reserved"), rs.getLong("borrowed"), rs.getLong("unavailable"), rs.getLong("pending")});
            Timestamp created = rs.getTimestamp("created_at");
            meta.add(new Object[]{rs.getString("status"), rs.getString("shelf_location"), created == null ? null : created.toInstant()});
        });
        Map<Long, CatalogueDtos.BookSummary> summaries = catalogue.summaries(raw.stream().map(r -> r[1]).toList());
        List<HoldingRow> rows = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            long[] r = raw.get(i);
            Object[] m = meta.get(i);
            rows.add(new HoldingRow(r[0], (String) m[0], (String) m[1], summaries.get(r[1]), (int) r[2], (int) r[3], (int) r[4],
                    (int) r[5], (int) r[6], r[7], (Instant) m[2]));
        }
        return PageResponse.of(rows, page, size, total == null ? 0 : total);
    }

    public HoldingDetail detail(LibraryScope scope, long holdingId) {
        LibraryHolding h = holding(scope, holdingId);
        HoldingRow row = list(scope, null, "ALL", 0, 1_000).content().stream().filter(r -> r.holdingId() == holdingId).findFirst()
                .orElseGet(() -> new HoldingRow(h.getId(), h.getStatus().name(), h.getShelfLocation(),
                        catalogue.summaries(List.of(h.getBookId())).get(h.getBookId()), 0, 0, 0, 0, 0, 0, h.getCreatedAt()));
        Book book = books.findById(h.getBookId()).orElseThrow();
        List<CopyRow> copyRows = copies.findByHoldingIdOrderByIdAsc(h.getId()).stream()
                .map(c -> new CopyRow(c.getId(), c.getAccessionNo(), c.getStatus(), c.getConditionNote(), c.getAcquiredAt(), c.getUpdatedAt()))
                .toList();
        CatalogueDtos.BookDetails details = catalogue.details(book.getId()).orElse(null);
        return new HoldingDetail(row, details, book.getCategoryId(), book.getDescription(), copyRows, canEditBook(scope, book.getId()),
                (int) holdings.countLibrariesHoldingBook(book.getId()));
    }

    public IsbnLookup isbnLookup(LibraryScope scope, String raw) {
        IsbnUtils.Isbn isbn = IsbnUtils.parse(raw).orElseThrow(() -> ApiException.badRequest(ErrorCodes.INVALID_ISBN,
                "That doesn't look like a valid ISBN-10 or ISBN-13. Please check the digits."));
        Optional<Book> existing = books.findByIsbn13(isbn.isbn13());
        boolean inLibrary = existing.flatMap(b -> holdings.findByLibraryIdAndBookId(scope.libraryId(), b.getId()))
                .map(h -> h.getStatus() == HoldingStatus.ACTIVE).orElse(false);
        BookMetadataClient.BookMetadata meta = existing.isPresent() ? null : metadata.lookup(isbn.isbn13()).orElse(null);
        return new IsbnLookup(isbn.isbn13(), isbn.isbn10(), existing.map(Book::getId).orElse(null), inLibrary, meta);
    }

    // ---------- commands ----------

    @Transactional
    public HoldingRow addHolding(LibraryScope scope, AddHoldingRequest req) {
        Library library = libraries.findById(scope.libraryId()).orElseThrow();
        Book book;
        boolean created = false;
        if (req.bookId() != null) {
            book = books.findById(req.bookId()).orElseThrow(() -> ApiException.notFound(ErrorCodes.BOOK_NOT_FOUND, "Book not found."));
        } else {
            String isbnRaw = req.isbn() != null && !req.isbn().isBlank() ? req.isbn() : (req.book() == null ? null : req.book().isbn());
            Optional<IsbnUtils.Isbn> isbn = parseOptionalIsbn(isbnRaw);
            Optional<Book> existing = isbn.flatMap(i -> books.findByIsbn13(i.isbn13()));
            if (existing.isPresent()) {
                book = existing.get();
            } else {
                if (req.book() == null) {
                    throw ApiException.validation("book", "Book details are required for a new title");
                }
                book = new Book();
                book.setCreatedByLibraryId(library.getId());
                apply(book, req.book(), isbn.orElse(null));
                book = books.saveAndFlush(book);
                created = true;
                audit.record(AuditActions.BOOK_CREATED, "BOOK", book.getId(), library.getId(), AuditService.details("title", book.getTitle()));
            }
        }
        LibraryHolding holding = holdings.findByLibraryIdAndBookId(library.getId(), book.getId()).orElse(null);
        if (holding != null && holding.getStatus() == HoldingStatus.ACTIVE) {
            throw ApiException.conflict(ErrorCodes.HOLDING_EXISTS, "This title is already in your library's catalogue.")
                    .with("holdingId", holding.getId());
        }
        if (holding == null) {
            holding = new LibraryHolding();
            holding.setLibraryId(library.getId());
            holding.setBookId(book.getId());
        }
        holding.setStatus(HoldingStatus.ACTIVE);
        holding.setShelfLocation(Texts.trimToNull(req.shelfLocation()));
        holding = holdings.saveAndFlush(holding);
        int n = req.copies() == null ? 1 : req.copies();
        createCopies(library, holding, book.getId(), n);
        audit.record(AuditActions.HOLDING_ADDED, "HOLDING", holding.getId(), library.getId(),
                AuditService.details("bookId", book.getId(), "title", book.getTitle(), "copies", n));
        if (created) {
            long bookId = book.getId();
            afterCommit(() -> coverResolver.resolveLater(bookId));
        }
        long id = holding.getId();
        return list(scope, null, "ALL", 0, 1_000).content().stream().filter(r -> r.holdingId() == id).findFirst().orElseThrow();
    }

    @Transactional
    public void updateBook(LibraryScope scope, long bookId, BookInput input) {
        holdings.findByLibraryIdAndBookId(scope.libraryId(), bookId)
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.BOOK_NOT_FOUND, "Book not found in this library."));
        if (!canEditBook(scope, bookId)) {
            throw ApiException.forbidden(ErrorCodes.BOOK_SHARED,
                    "This title is shared with other libraries. Only a platform super administrator can edit its bibliographic details.");
        }
        Book book = books.findById(bookId).orElseThrow();
        String oldIsbn = book.getIsbn13();
        Optional<IsbnUtils.Isbn> isbn = parseOptionalIsbn(input.isbn());
        isbn.flatMap(i -> books.findByIsbn13(i.isbn13())).filter(other -> !other.getId().equals(bookId)).ifPresent(other -> {
            throw ApiException.conflict(ErrorCodes.ISBN_ALREADY_EXISTS, "Another book already uses this ISBN.").with("existingBookId", other.getId());
        });
        apply(book, input, isbn.orElse(null));
        if (!java.util.Objects.equals(oldIsbn, book.getIsbn13()) && book.getCoverSource() != CoverSource.LOCAL) {
            book.setCoverSource(CoverSource.UNCHECKED);
            book.setCoverExternalRef(null);
            afterCommit(() -> coverResolver.resolveLater(bookId));
        }
        books.save(book);
        audit.record(AuditActions.BOOK_UPDATED, "BOOK", bookId, scope.libraryId(), AuditService.details("title", book.getTitle()));
    }

    @Transactional
    public void updateHolding(LibraryScope scope, long holdingId, UpdateHoldingRequest req) {
        LibraryHolding h = holding(scope, holdingId);
        h.setShelfLocation(Texts.trimToNull(req.shelfLocation()));
        holdings.save(h);
        audit.record(AuditActions.HOLDING_UPDATED, "HOLDING", holdingId, scope.libraryId(), null);
    }

    @Transactional
    public void withdrawHolding(LibraryScope scope, long holdingId) {
        LibraryHolding h = holding(scope, holdingId);
        long inUse = copies.countByHoldingIdAndStatusIn(h.getId(), List.of(CopyStatus.RESERVED, CopyStatus.BORROWED));
        Long openRequests = jdbc.queryForObject("SELECT COUNT(*) FROM borrow_requests WHERE holding_id = :h AND status IN ('PENDING','APPROVED')",
                new MapSqlParameterSource("h", h.getId()), Long.class);
        if (inUse > 0 || (openRequests != null && openRequests > 0)) {
            throw ApiException.conflict(ErrorCodes.HOLDING_IN_USE,
                    "This title has copies on loan, reserved, or open requests. Resolve them before withdrawing it.");
        }
        h.setStatus(HoldingStatus.WITHDRAWN);
        holdings.save(h);
        for (BookCopy c : copies.findByHoldingIdOrderByIdAsc(h.getId())) {
            if (c.getStatus() != CopyStatus.WITHDRAWN) {
                c.setStatus(CopyStatus.WITHDRAWN);
                copies.save(c);
            }
        }
        audit.record(AuditActions.HOLDING_WITHDRAWN, "HOLDING", holdingId, scope.libraryId(), null);
    }

    @Transactional
    public List<CopyRow> addCopies(LibraryScope scope, long holdingId, int count) {
        LibraryHolding h = holding(scope, holdingId);
        if (h.getStatus() != HoldingStatus.ACTIVE) {
            throw ApiException.conflict(ErrorCodes.INVALID_STATE, "Re-add this title to the catalogue before adding copies.");
        }
        Library library = libraries.findById(scope.libraryId()).orElseThrow();
        createCopies(library, h, h.getBookId(), count);
        audit.record(AuditActions.COPIES_ADDED, "HOLDING", holdingId, scope.libraryId(), AuditService.details("count", count));
        return copies.findByHoldingIdOrderByIdAsc(h.getId()).stream()
                .map(c -> new CopyRow(c.getId(), c.getAccessionNo(), c.getStatus(), c.getConditionNote(), c.getAcquiredAt(), c.getUpdatedAt()))
                .toList();
    }

    @Transactional
    public CopyRow updateCopyStatus(LibraryScope scope, long copyId, CopyStatusRequest req) {
        BookCopy c = copies.lockByIdAndLibraryId(copyId, scope.libraryId())
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND, "Copy not found."));
        if (!MANUAL_FROM.contains(c.getStatus())) {
            throw ApiException.conflict(ErrorCodes.INVALID_STATE,
                    "This copy is " + c.getStatus().name().toLowerCase() + "; it changes state through the borrowing workflow.");
        }
        if (req.status() == CopyStatus.RESERVED || req.status() == CopyStatus.BORROWED) {
            throw ApiException.validation("status", "Copies become reserved/borrowed only through requests and issues");
        }
        CopyStatus from = c.getStatus();
        c.setStatus(req.status());
        c.setConditionNote(Texts.trimToNull(req.conditionNote()));
        copies.save(c);
        audit.record(AuditActions.COPY_STATUS_CHANGED, "COPY", copyId, scope.libraryId(),
                AuditService.details("from", from.name(), "to", req.status().name()));
        return new CopyRow(c.getId(), c.getAccessionNo(), c.getStatus(), c.getConditionNote(), c.getAcquiredAt(), c.getUpdatedAt());
    }

    @Transactional
    public void uploadCover(LibraryScope scope, long bookId, MultipartFile file) throws IOException {
        holdings.findByLibraryIdAndBookId(scope.libraryId(), bookId)
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.BOOK_NOT_FOUND, "Book not found in this library."));
        if (!canEditBook(scope, bookId)) {
            throw ApiException.forbidden(ErrorCodes.BOOK_SHARED, "This title is shared with other libraries; its cover can only be changed by a super administrator.");
        }
        if (file == null || file.isEmpty() || file.getSize() > MAX_COVER_BYTES) {
            throw ApiException.badRequest(ErrorCodes.UNSUPPORTED_FILE, "Upload a JPEG, PNG or WebP image up to 2 MB.");
        }
        try (InputStream in = file.getInputStream()) {
            byte[] head = FileSniffer.head(in, 64);
            FileSniffer.Sniffed type = FileSniffer.detect(head).filter(FileSniffer::isImage)
                    .orElseThrow(() -> ApiException.badRequest(ErrorCodes.UNSUPPORTED_FILE, "Upload a JPEG, PNG or WebP image."));
            String key = storage.store("covers", new SequenceInputStream(new ByteArrayInputStream(head), in), type.extension());
            Book book = books.findById(bookId).orElseThrow();
            String old = book.getCoverSource() == CoverSource.LOCAL ? book.getCoverKey() : null;
            book.setCoverSource(CoverSource.LOCAL);
            book.setCoverKey(key);
            books.save(book);
            if (old != null) {
                afterCommit(() -> storage.delete(old));
            }
        }
        audit.record(AuditActions.COVER_UPLOADED, "BOOK", bookId, scope.libraryId(), null);
    }

    public boolean canEditBook(LibraryScope scope, long bookId) {
        if (scope.principal().isSuperAdmin()) {
            return true;
        }
        return holdings.countOtherLibrariesHoldingBook(bookId, scope.libraryId()) == 0;
    }

    // ---------- helpers ----------

    private LibraryHolding holding(LibraryScope scope, long holdingId) {
        return holdings.findByIdAndLibraryId(holdingId, scope.libraryId())
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND, "Title not found in this library."));
    }

    private Optional<IsbnUtils.Isbn> parseOptionalIsbn(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(IsbnUtils.parse(raw).orElseThrow(() -> ApiException.validation("isbn",
                "That doesn't look like a valid ISBN-10 or ISBN-13")));
    }

    private void apply(Book book, BookInput in, IsbnUtils.Isbn isbn) {
        book.setTitle(in.title().trim());
        book.setSubtitle(Texts.trimToNull(in.subtitle()));
        book.setPublisher(Texts.trimToNull(in.publisher()));
        book.setPublishedYear(in.publishedYear());
        book.setLanguage(Texts.trimToNull(in.language()));
        book.setPageCount(in.pageCount());
        book.setDescription(Texts.trimToNull(in.description()));
        String categoryName = null;
        if (in.categoryId() != null) {
            Category category = categories.findById(in.categoryId())
                    .orElseThrow(() -> ApiException.validation("categoryId", "Unknown category"));
            book.setCategoryId(category.getId());
            categoryName = category.getName();
        } else {
            book.setCategoryId(null);
        }
        book.setIsbn13(isbn == null ? null : isbn.isbn13());
        book.setIsbn10(isbn == null ? null : isbn.isbn10());
        List<Author> list = new ArrayList<>();
        for (String name : new LinkedHashSet<>(in.authors().stream().map(String::trim).filter(s -> !s.isEmpty()).toList())) {
            list.add(author(name));
        }
        book.getAuthors().clear();
        book.getAuthors().addAll(list);
        book.setSearchText(SearchTerms.buildSearchText(book.getTitle(), book.getSubtitle(), list.stream().map(Author::getName).toList(),
                book.getPublisher(), book.getIsbn13(), book.getIsbn10(), categoryName));
    }

    private Author author(String name) {
        String normalized = Texts.normalizePersonName(name);
        return authors.findByNameNormalized(normalized).orElseGet(() -> {
            Author a = new Author();
            a.setName(name);
            a.setNameNormalized(normalized);
            try {
                return authors.saveAndFlush(a);
            } catch (DataIntegrityViolationException e) {
                return authors.findByNameNormalized(normalized).orElseThrow();
            }
        });
    }

    private void createCopies(Library library, LibraryHolding holding, long bookId, int count) {
        long existing = copies.countByHoldingId(holding.getId());
        for (int i = 1; i <= count; i++) {
            BookCopy c = new BookCopy();
            c.setHoldingId(holding.getId());
            c.setLibraryId(library.getId());
            c.setAccessionNo(String.format("%s-%05d-%02d", library.getCode(), bookId, existing + i));
            c.setStatus(CopyStatus.AVAILABLE);
            c.setAcquiredAt(Instant.now());
            copies.save(c);
        }
        copies.flush();
    }

    private static void afterCommit(Runnable r) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    r.run();
                }
            });
        } else {
            r.run();
        }
    }
}
