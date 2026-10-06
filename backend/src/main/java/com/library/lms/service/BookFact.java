package com.library.lms.service;

/**
 * One book, reduced to what an assistant may be told about it.
 *
 * <p><b>This is the whole of what leaves the database for a chat answer.</b>
 * A catalogue entry and two counts - nothing about who borrowed it, who is
 * waiting for it, what anyone owes on it, or which account asked. There is no
 * field here that could carry an address, a hash or a token, and the type
 * exists so that adding one would be a deliberate act rather than an
 * accident.</p>
 *
 * @param availableCopies how many are on the shelf now
 * @param totalCopies     how many the library owns
 */
public record BookFact(String title, String author, String category, String isbn, Integer availableCopies,
        Integer totalCopies) {

    /**
     * A fact drawn from the public catalogue, where copy counts are not
     * published.
     *
     * <p>Null counts are the difference between "nothing is on the shelf" and
     * "how many are on the shelf is not something a visitor is told". The
     * description below omits the clause entirely rather than printing a zero
     * that would read as a false claim about availability.</p>
     */
    public static BookFact bibliographic(String title, String author, String category, String isbn) {
        return new BookFact(title, author, category, isbn, null, null);
    }

    /** Whether copy counts are known at all. False for a public catalogue fact. */
    public boolean hasCopyCounts() {
        return availableCopies != null && totalCopies != null;
    }

    /** Whether a copy can be borrowed right now. */
    public boolean available() {
        return availableCopies != null && availableCopies > 0;
    }

    /** One line an assistant can read or repeat, with no record of any person in it. */
    public String describe() {
        StringBuilder line = new StringBuilder("\"").append(title).append("\"");

        if (author != null && !author.isBlank()) {
            line.append(" by ").append(author);
        }
        if (category != null && !category.isBlank()) {
            line.append(" (").append(category).append(")");
        }

        if (!hasCopyCounts()) {
            return line.toString();
        }

        return line.append(" - ")
                .append(available() ? availableCopies + " of " + totalCopies + " copies available now"
                        : "all " + totalCopies + " copies are out")
                .toString();
    }
}
