package com.smartlib.catalogue;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** ISBN-10/13 normalization, checksum validation and conversion. */
public final class IsbnUtils {
    private IsbnUtils() {
    }

    public record Isbn(String isbn13, String isbn10) {
    }

    private static final Pattern ISBN_LIKE = Pattern.compile("^(?:ISBN(?:-1[03])?:?\\s*)?[0-9Xx][0-9Xx\\s-]{8,20}$");

    /** Strips everything except digits and X (hyphens, spaces, "ISBN" prefix). */
    public static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.toUpperCase(Locale.ROOT).replaceFirst("^ISBN(?:-1[03])?:?", "");
        return s.replaceAll("[^0-9X]", "");
    }

    /** True if the raw input is shaped like an ISBN (used to route search input to an exact ISBN lookup). */
    public static boolean looksLikeIsbn(String raw) {
        if (raw == null || !ISBN_LIKE.matcher(raw.trim()).matches()) {
            return false;
        }
        int len = clean(raw).length();
        return len == 10 || len == 13;
    }

    public static Optional<Isbn> parse(String raw) {
        String c = clean(raw);
        if (c.length() == 10 && isValid10(c)) {
            return Optional.of(new Isbn(to13(c), c));
        }
        if (c.length() == 13 && c.indexOf('X') < 0 && (c.startsWith("978") || c.startsWith("979")) && isValid13(c)) {
            return Optional.of(new Isbn(c, c.startsWith("978") ? to10(c) : null));
        }
        return Optional.empty();
    }

    public static boolean isValid10(String c) {
        if (c.length() != 10) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < 10; i++) {
            char ch = c.charAt(i);
            int d;
            if (ch == 'X') {
                if (i != 9) {
                    return false;
                }
                d = 10;
            } else if (Character.isDigit(ch)) {
                d = ch - '0';
            } else {
                return false;
            }
            sum += (10 - i) * d;
        }
        return sum % 11 == 0;
    }

    public static boolean isValid13(String c) {
        if (c.length() != 13 || !c.chars().allMatch(Character::isDigit)) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < 13; i++) {
            sum += (c.charAt(i) - '0') * (i % 2 == 0 ? 1 : 3);
        }
        return sum % 10 == 0;
    }

    public static String to13(String isbn10) {
        String core = "978" + isbn10.substring(0, 9);
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            sum += (core.charAt(i) - '0') * (i % 2 == 0 ? 1 : 3);
        }
        return core + ((10 - sum % 10) % 10);
    }

    public static String to10(String isbn13) {
        String core = isbn13.substring(3, 12);
        int sum = 0;
        for (int i = 0; i < 9; i++) {
            sum += (10 - i) * (core.charAt(i) - '0');
        }
        int check = (11 - sum % 11) % 11;
        return core + (check == 10 ? "X" : String.valueOf(check));
    }
}
