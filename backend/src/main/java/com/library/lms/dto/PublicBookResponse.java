package com.library.lms.dto;

/**
 * A book as an unauthenticated visitor sees it.
 *
 * <p>Bibliographic facts only: title, author, ISBN, category and
 * holding library. Operational details such as IDs, copy counts,
 * cover storage information, transactions and member data are
 * deliberately absent.</p>
 */
public record PublicBookResponse(
        String title,
        String author,
        String isbn,
        String categoryName,
        String libraryName) {
}
