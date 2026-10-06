package com.library.lms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.RecordComponent;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import com.library.lms.dto.PagedResponse;
import com.library.lms.dto.PublicBookResponse;
import com.library.lms.entity.Book;
import com.library.lms.entity.Category;
import com.library.lms.entity.Library;
import com.library.lms.exception.InvalidPaginationException;
import com.library.lms.repository.BookRepository;
import com.library.lms.repository.LibraryRepository;

/**
 * What a stranger is allowed to learn.
 *
 * <p>The assertions that matter here are about what is <i>absent</i>: the
 * public record must not grow a field that belongs behind the gate, and the
 * service must not hold a repository that could fetch one.</p>
 */
class PublicCatalogueServiceTest {

    private final BookRepository bookRepository = mock(BookRepository.class);

    private final LibraryRepository libraryRepository = mock(LibraryRepository.class);

    /** Only consulted for whether a library is open to join. */
    private final RegistrationService registrationService = mock(RegistrationService.class);

    private final PublicCatalogueService service =
            new PublicCatalogueService(bookRepository, libraryRepository, registrationService);

    private static Book book() {
        Library library = new Library();
        library.setId(7L);
        library.setName("Central Library");

        Category category = new Category();
        category.setId(3L);
        category.setName("Science Fiction");

        Book book = new Book();
        book.setId(31L);
        book.setTitle("Dune");
        book.setAuthor("Frank Herbert");
        book.setIsbn("9780441013593");
        book.setCategory(category);
        book.setLibrary(library);
        book.setTotalCopies(5);
        book.setAvailableCopies(2);
        return book;
    }

    private void stubOneBook() {
        when(bookRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(book()), Pageable.ofSize(12), 1));
    }

    // ---------- what the response may carry ----------

    @Test
    void theResponseCarriesBibliographicFieldsOnly() {
        List<String> fields = java.util.Arrays.stream(PublicBookResponse.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        assertThat(fields)
                .as("the public shape is fixed; adding to it is a deliberate act, not a side effect")
                .containsExactlyInAnyOrder("title", "author", "isbn", "categoryName", "libraryName");
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "availableCopies", "totalCopies", "libraryId", "categoryId", "version"})
    void theResponseNeverCarriesSomethingOperational(String forbidden) {
        assertThat(java.util.Arrays.stream(PublicBookResponse.class.getRecordComponents())
                .map(RecordComponent::getName))
                .doesNotContain(forbidden);
    }

    @Test
    void aBookIsMappedToItsBibliographicFactsAndNothingElse() {
        stubOneBook();

        PublicBookResponse found = service.search(null, 0, 12).getContent().get(0);

        assertThat(found.title()).isEqualTo("Dune");
        assertThat(found.author()).isEqualTo("Frank Herbert");
        assertThat(found.isbn()).isEqualTo("9780441013593");
        assertThat(found.categoryName()).isEqualTo("Science Fiction");
        assertThat(found.libraryName()).isEqualTo("Central Library");
    }

    @Test
    void aBookWithNoCategoryOrLibraryDoesNotBlowUp() {
        Book orphan = new Book();
        orphan.setTitle("Untitled");
        orphan.setAuthor("Unknown");
        when(bookRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(orphan), Pageable.ofSize(12), 1));

        PublicBookResponse found = service.search(null, 0, 12).getContent().get(0);

        assertThat(found.categoryName()).isNull();
        assertThat(found.libraryName()).isNull();
    }

    // ---------- the search itself ----------

    @Test
    void theSearchIsNotScopedToOneLibrary() {
        stubOneBook();

        service.search("dune", 0, 12);

        // A public visitor has no library, so the filter must not try to guess one.
        // What it may see is limited by the response shape, not by a tenant id.
        verify(bookRepository).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    void resultsAreOrderedByTitleSoTheOrderRevealsNothing() {
        stubOneBook();

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        service.search(null, 0, 12);
        verify(bookRepository).findAll(any(Specification.class), pageable.capture());

        assertThat(pageable.getValue().getSort().getOrderFor("title")).isNotNull();
        assertThat(pageable.getValue().getSort().getOrderFor("createdAt"))
                .as("when a book was added is not a visitor's business")
                .isNull();
    }

    @Test
    void thePageCarriesTheRealTotals() {
        stubOneBook();

        PagedResponse<PublicBookResponse> page = service.search(null, 0, 12);

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getPage()).isZero();
    }

    // ---------- limits ----------

    @Test
    void aPageSizeAboveThePublicCeilingIsRefused() {
        assertThatThrownBy(() -> service.search(null, 0, PublicCatalogueService.MAX_PAGE_SIZE + 1))
                .isInstanceOf(InvalidPaginationException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, -10})
    void aNegativePageIsRefused(int page) {
        assertThatThrownBy(() -> service.search(null, page, 12))
                .isInstanceOf(InvalidPaginationException.class);
    }

    @Test
    void anAbsurdlyLongSearchTermIsRefusedRatherThanRun() {
        assertThatThrownBy(() -> service.search("x".repeat(PublicCatalogueService.MAX_KEYWORD_LENGTH + 1), 0, 12))
                .isInstanceOf(InvalidPaginationException.class);
    }

    // ---------- the boundary itself ----------

    @Test
    void theServiceCanOnlyReachBooksAndLibraries() {
        assertThat(java.util.Arrays.stream(PublicCatalogueService.class.getDeclaredFields())
                .map(field -> field.getType().getSimpleName())
                .filter(type -> type.endsWith("Repository"))
                .toList())
                .as("no route to users, transactions, payments or digital resources")
                .containsExactlyInAnyOrder("BookRepository", "LibraryRepository");
    }

    @Test
    void theServiceHasNoWriteMethod() {
        assertThat(java.util.Arrays.stream(PublicCatalogueService.class.getDeclaredMethods())
                .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
                .map(java.lang.reflect.Method::getName))
                .containsExactlyInAnyOrder("search", "libraries");
    }
}
