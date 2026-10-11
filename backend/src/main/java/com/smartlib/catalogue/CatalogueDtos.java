package com.smartlib.catalogue;

import java.util.List;

public final class CatalogueDtos {
    private CatalogueDtos() {
    }

    public record CategoryRef(long id, String name, String slug) {
    }

    public record CategoryWithCount(long id, String name, String slug, long bookCount) {
    }

    public record HoldingAvailability(long holdingId, long libraryId, String libraryName, String librarySlug, String city,
                                      int availableCopies, int totalCopies) {
    }

    /** One result card: a title plus its per-library availability (never merged across libraries). */
    public record CatalogueItem(long id, String title, String subtitle, List<String> authors, String isbn13, String coverUrl,
                                String isbnCoverUrl, CategoryRef category, Integer publishedYear, String language,
                                String publisher, List<HoldingAvailability> holdings, int totalAvailable, int libraryCount) {
    }

    public record BookSummary(long id, String title, String subtitle, List<String> authors, String isbn13, String coverUrl,
                              String isbnCoverUrl, String category) {
    }

    public record HoldingDetail(long holdingId, long libraryId, String libraryName, String librarySlug, String city,
                                String addressLine, String openingHours, String shelfLocation, int availableCopies,
                                int totalCopies, int loanPeriodDays, int pickupWindowDays, long finePerDayPaise) {
    }

    public record ResourceSummary(long id, String title, String type, String visibility, long libraryId, String libraryName) {
    }

    public record BookDetails(long id, String title, String subtitle, List<String> authors, String isbn13, String isbn10,
                              String description, String publisher, Integer publishedYear, String language, Integer pageCount,
                              CategoryRef category, String coverUrl, String isbnCoverUrl, List<HoldingDetail> holdings,
                              int totalAvailable, List<ResourceSummary> digitalResources, List<CatalogueItem> related) {
    }

    /** matchAny=false requires every search word (default); true ranks rows matching any word (AI fallback). */
    public record CatalogueQuery(String q, Long libraryId, Long categoryId, String categorySlug, String language,
                                 boolean availableOnly, String sort, int page, int size, boolean matchAny) {
        public CatalogueQuery(String q, Long libraryId, Long categoryId, String categorySlug, String language,
                              boolean availableOnly, String sort, int page, int size) {
            this(q, libraryId, categoryId, categorySlug, language, availableOnly, sort, page, size, false);
        }
    }
}
