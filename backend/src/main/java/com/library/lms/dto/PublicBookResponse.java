package com.library.lms.dto;

/**
 * A book as an unauthenticated visitor sees it.
 *
 * <p><b>Bibliographic facts only.</b> ID, title, author, ISBN, category,
 * holding library and safe cover information. Deliberately absent are operational
 * details such as copy counts, library IDs, and private transaction or member data.</p>
 */
public record PublicBookResponse(Long id, String title, String author, String isbn, String categoryName,
        String libraryName, boolean hasCover, String coverUrl) {
}

