package com.library.lms.service;

/** What a caller seems to be asking the catalogue for. */
public enum CatalogueIntent {

    /** A book by its title, or any words from it. */
    TITLE,

    /** What a named author wrote. */
    AUTHOR,

    /** What a library holds in a named category. */
    CATEGORY,

    /** Whether a copy can be borrowed right now. */
    AVAILABILITY,

    /** What a particular book is. */
    DETAILS,

    /**
     * Something worth reading, rather than one book the caller already named.
     *
     * <p>The only intent whose term may be empty: "suggest me a book" names no
     * subject at all, and the answer is a handful of what the library actually
     * holds. A topic - "books for learning Java" - narrows it, and is matched
     * against title, author and category rather than title alone, because a
     * subject is as likely to be the category as a word in the title.</p>
     */
    RECOMMENDATION
}
