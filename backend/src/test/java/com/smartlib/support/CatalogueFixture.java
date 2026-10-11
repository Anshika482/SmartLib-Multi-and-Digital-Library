package com.smartlib.support;

/** Helpers to build valid ISBNs for synthetic test books. */
public final class CatalogueFixture {
    private CatalogueFixture() {
    }

    /** Valid ISBN-13 in the 979-8 test-friendly range with the given 8-digit serial. */
    public static String isbn13(int serial) {
        String core = String.format("97980%07d", serial);
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            sum += (core.charAt(i) - '0') * (i % 2 == 0 ? 1 : 3);
        }
        return core + ((10 - sum % 10) % 10);
    }
}
