package com.smartlib.catalogue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Builds a safe MySQL boolean-mode FULLTEXT expression from free text. */
public final class SearchTerms {
    private SearchTerms() {
    }

    /** InnoDB's default stopword list; required (+) stopwords would make a query match nothing. */
    static final Set<String> STOPWORDS = Set.of("a", "about", "an", "are", "as", "at", "be", "by", "com", "de", "en", "for", "from",
            "how", "i", "in", "is", "it", "la", "of", "on", "or", "that", "the", "this", "to", "was", "what", "when", "where", "who",
            "will", "with", "und", "www");
    static final int MIN_TOKEN = 3;

    public static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        for (String t : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (!t.isBlank() && t.length() >= MIN_TOKEN && !STOPWORDS.contains(t) && out.size() < 8) {
                out.add(t);
            }
        }
        return out;
    }

    /** e.g. "Clean Code" -> "+clean* +code*"; null when no indexable token remains (caller falls back to LIKE). */
    public static String booleanQuery(String text) {
        List<String> tokens = tokens(text);
        if (tokens.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String t : tokens) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append('+').append(t).append('*');
        }
        return sb.toString();
    }

    /** Words shorter than the FULLTEXT minimum (e.g. "C3", "Go"); applied as extra LIKE filters. */
    public static List<String> shortTokens(String text) {
        List<String> out = new ArrayList<>();
        for (String t : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (!t.isBlank() && t.length() < MIN_TOKEN && !STOPWORDS.contains(t) && out.size() < 3) {
                out.add(t);
            }
        }
        return out;
    }

    /** e.g. "java programming" -> "java* programming*" (rows matching ANY word, ranked by relevance). */
    public static String looseQuery(String text) {
        List<String> tokens = tokens(text);
        if (tokens.isEmpty()) {
            return null;
        }
        return String.join(" ", tokens.stream().map(t -> t + "*").toList());
    }

    private static final Set<String> FILLER = Set.of("book", "books", "novel", "novels", "smartlib", "library", "libraries", "available",
            "availability", "recommend", "recommendation", "recommendations", "find", "any", "some", "good", "best", "please", "show",
            "have", "has", "you", "your", "copy", "copies", "title", "titles", "read", "reading", "beginner", "beginners", "top", "rated");

    /** Removes conversational filler so model-written queries ("good java books available") match catalogue text. */
    public static String stripFiller(String text) {
        if (text == null) {
            return "";
        }
        List<String> kept = new ArrayList<>();
        for (String t : text.trim().split("\\s+")) {
            String w = t.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}+#.-]", "");
            if (!w.isEmpty() && !FILLER.contains(w)) {
                kept.add(t);
            }
        }
        String out = String.join(" ", kept).trim();
        return out.isEmpty() ? text.trim() : out;
    }

    public static String buildSearchText(String title, String subtitle, List<String> authors, String publisher, String isbn13,
                                         String isbn10, String category) {
        StringBuilder sb = new StringBuilder();
        append(sb, title);
        append(sb, subtitle);
        if (authors != null) {
            authors.forEach(a -> append(sb, a));
        }
        append(sb, publisher);
        append(sb, isbn13);
        append(sb, isbn10);
        append(sb, category);
        return sb.toString().trim();
    }

    private static void append(StringBuilder sb, String v) {
        if (v != null && !v.isBlank()) {
            sb.append(v.trim()).append(' ');
        }
    }
}
