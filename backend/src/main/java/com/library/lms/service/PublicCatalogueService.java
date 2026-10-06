package com.library.lms.service;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.library.lms.dto.PagedResponse;
import com.library.lms.dto.PublicBookResponse;
import com.library.lms.dto.PublicLibraryResponse;
import com.library.lms.entity.Book;
import com.library.lms.entity.Library;
import com.library.lms.exception.InvalidPaginationException;
import com.library.lms.repository.BookRepository;
import com.library.lms.repository.BookSpecifications;
import com.library.lms.repository.LibraryRepository;

/**
 * What anyone may read without signing in.
 *
 * <p>A library's catalogue is public in the way a library's catalogue has
 * always been public: you may look up whether a title exists and who wrote it,
 * without being a member. Everything that makes the system a management system
 * - members, loans, fines, payments, digital resource locations, copy counts -
 * stays behind the gate.</p>
 *
 * <p><b>This service reads two tables and maps to two narrow records.</b> It
 * holds no reference to users, transactions or digital resources, so it cannot
 * leak them even if a future change asks it to. The search runs across every
 * library rather than one, which is the one respect in which it differs from
 * the authenticated catalogue - and the reason it may only return the fields
 * a stranger is entitled to see.</p>
 *
 * <p>Read-only in every sense: {@code @Transactional(readOnly = true)}, no
 * write method, and no way to reach one.</p>
 */
@Service
@Transactional(readOnly = true)
public class PublicCatalogueService {

    /** Smaller than the authenticated ceiling: nobody unauthenticated needs to page fast. */
    static final int MAX_PAGE_SIZE = 24;

    static final int MAX_KEYWORD_LENGTH = 100;

    private final BookRepository bookRepository;

    private final LibraryRepository libraryRepository;

    /** Only for the one question of whether a library is open; no write path. */
    private final RegistrationService registrationService;

    public PublicCatalogueService(BookRepository bookRepository, LibraryRepository libraryRepository,
            RegistrationService registrationService) {
        this.bookRepository = bookRepository;
        this.libraryRepository = libraryRepository;
        this.registrationService = registrationService;
    }

    /**
     * One page of the catalogue, across every library, newest titles last.
     *
     * <p>Sorted by title so the order is stable and says nothing about when a
     * book was added or how a library is organised. There is no sort parameter:
     * a public endpoint needs one order, and fewer knobs is less surface.</p>
     *
     * @param keyword matched against title, author and ISBN; blank means all
     * @throws InvalidPaginationException if page or size is out of range
     */
    public PagedResponse<PublicBookResponse> search(String keyword, int page, int size) {
        validatePageRequest(page, size);

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "title").and(Sort.by("id")));

        Specification<Book> specification = BookSpecifications.always();
        String trimmed = keyword == null ? "" : keyword.trim();

        if (!trimmed.isEmpty()) {
            if (trimmed.length() > MAX_KEYWORD_LENGTH) {
                throw new InvalidPaginationException(
                        "Search term must be " + MAX_KEYWORD_LENGTH + " characters or fewer.");
            }
            specification = specification.and(BookSpecifications.matchesKeyword(trimmed));
        }

        Page<Book> books = bookRepository.findAll(specification, pageable);

        List<PublicBookResponse> content = books.getContent().stream().map(PublicCatalogueService::toPublicBook).toList();

        return new PagedResponse<>(content, books.getNumber(), books.getSize(), books.getTotalElements(),
                books.getTotalPages());
    }

    /**
     * The libraries a visitor may see and join, by name, with a real count.
     *
     * <p>A library whose own administrator is still waiting on a decision is
     * left out. It exists as a row, but nobody runs it yet, so listing it would
     * invite members to join something that is not open - and would leak that
     * an application is in flight.
     */
    public List<PublicLibraryResponse> libraries() {
        return libraryRepository.findAll(Sort.by(Sort.Direction.ASC, "name")).stream()
                .filter(library -> registrationService.hasApprovedAdministrator(library.getId()))
                .map(this::toPublicLibrary)
                .toList();
    }

    /**
     * Maps to the public shape.
     *
     * <p>Field by field rather than by a mapper: the point of this method is
     * that adding a column to {@link Book} does not add it to the public API.</p>
     */
    private static PublicBookResponse toPublicBook(Book book) {
        return new PublicBookResponse(
                book.getTitle(),
                book.getAuthor(),
                book.getIsbn(),
                book.getCategory() == null ? null : book.getCategory().getName(),
                book.getLibrary() == null ? null : book.getLibrary().getName());
    }

    private PublicLibraryResponse toPublicLibrary(Library library) {
        return new PublicLibraryResponse(library.getId(), library.getName(),
                bookRepository.count(BookSpecifications.belongsToLibrary(library.getId())));
    }

    /** The same shape of check the authenticated catalogue makes, with a tighter ceiling. */
    private static void validatePageRequest(int page, int size) {
        if (page < 0) {
            throw new InvalidPaginationException("Page must be 0 or greater, but was " + page);
        }
        if (size < 1) {
            throw new InvalidPaginationException("Size must be 1 or greater, but was " + size);
        }
        if (size > MAX_PAGE_SIZE) {
            throw new InvalidPaginationException("Size must be " + MAX_PAGE_SIZE + " or fewer, but was " + size);
        }
    }
}
