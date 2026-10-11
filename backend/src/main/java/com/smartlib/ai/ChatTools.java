package com.smartlib.ai;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.smartlib.catalogue.CatalogueDtos.BookDetails;
import com.smartlib.catalogue.CatalogueDtos.CatalogueItem;
import com.smartlib.catalogue.CatalogueDtos.CatalogueQuery;
import com.smartlib.catalogue.CatalogueDtos.HoldingAvailability;
import com.smartlib.catalogue.CatalogueQueryService;
import com.smartlib.catalogue.SearchTerms;
import com.smartlib.circulation.CirculationDtos.LoanView;
import com.smartlib.circulation.CirculationDtos.RequestView;
import com.smartlib.circulation.CirculationQueries;
import com.smartlib.circulation.MembershipService;
import com.smartlib.common.util.Money;
import com.smartlib.common.web.PageResponse;
import com.smartlib.fine.FineDtos.FineView;
import com.smartlib.fine.FineService;
import com.smartlib.library.LibraryQueryService;
import com.smartlib.notification.NotificationService;
import com.smartlib.security.SmartLibPrincipal;

/**
 * The only bridge between Gemini and SmartLib data. Every tool runs with the authenticated caller's identity; no
 * tool accepts a user id or private library id from the model, so prompt injection cannot widen access.
 */
@Component
public class ChatTools {

    private final CatalogueQueryService catalogue;
    private final LibraryQueryService libraries;
    private final CirculationQueries circulation;
    private final FineService fines;
    private final MembershipService memberships;
    private final NotificationService notifications;
    private final Cache<String, List<LibraryQueryService.LibraryCard>> libraryCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(5)).build();

    public ChatTools(CatalogueQueryService catalogue, LibraryQueryService libraries, CirculationQueries circulation, FineService fines,
                     MembershipService memberships, NotificationService notifications) {
        this.catalogue = catalogue;
        this.libraries = libraries;
        this.circulation = circulation;
        this.fines = fines;
        this.memberships = memberships;
        this.notifications = notifications;
    }

    public record Outcome(Map<String, Object> response, List<CatalogueItem> cards) {
    }

    /** Live catalogue matches for the user's own words, looked up before Gemini is called (see {@link #prefetch}). */
    public record Prefetch(Map<String, Object> response, List<CatalogueItem> books) {
        static final Prefetch NONE = new Prefetch(null, List.of());

        public boolean found() {
            return !books.isEmpty();
        }
    }

    /** Question and connecting words that never appear in catalogue text, so a strict search would fail on them. */
    private static final Set<String> QUESTION_WORDS = Set.of("which", "what", "who", "whom", "whose", "where", "when", "why", "how",
            "is", "are", "was", "were", "do", "does", "did", "can", "could", "would", "should", "will", "there", "the", "a", "an", "me",
            "i", "my", "we", "us", "tell", "about", "get", "borrow", "issue", "want", "need", "looking", "look", "for", "of", "in", "at",
            "on", "to", "by", "from", "with", "and", "or", "it", "its", "this", "that", "still", "now", "today", "currently", "right",
            "written", "author", "wrote", "know", "give", "list", "all", "lend", "keep", "stock", "stocked");

    /**
     * Looks up the user's message in the catalogue before the model runs, so most book questions are answered in a single
     * model call instead of search → availability → answer. Strict matching only (every remaining word must match), so
     * general questions rarely pick up unrelated books; when nothing matches, the model can still call search_catalogue.
     */
    public Prefetch prefetch(String message) {
        Set<String> libraryWords = new java.util.HashSet<>();
        for (LibraryQueryService.LibraryCard l : allLibraries()) {
            for (String part : (l.name() + " " + (l.city() == null ? "" : l.city())).toLowerCase(Locale.ROOT).split("\\s+")) {
                libraryWords.add(part);
            }
        }
        List<String> kept = new ArrayList<>();
        for (String t : SearchTerms.stripFiller(message).split("\\s+")) {
            String w = t.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}+#.-]", "").replaceAll("[.]+$", "");
            if (!w.isEmpty() && !QUESTION_WORDS.contains(w) && !libraryWords.contains(w)) {
                kept.add(t.replaceAll("[?!,;:]+$", ""));
            }
        }
        String query = String.join(" ", kept).trim();
        if (query.replaceAll("[^\\p{L}\\p{N}]", "").length() < 3 || kept.size() > 6) {
            return Prefetch.NONE;
        }
        PageResponse<CatalogueItem> page = catalogue.search(new CatalogueQuery(query, null, null, null, null, false, null, 0, 5, false));
        if (page.content().isEmpty()) {
            return Prefetch.NONE;
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("query", query);
        response.put("total_matches", page.totalElements());
        response.put("results", page.content().stream().map(ChatTools::compact).toList());
        return new Prefetch(response, page.content());
    }

    // ---------- declarations ----------

    public List<Map<String, Object>> declarations() {
        return List.of(
                fn("search_catalogue",
                        "Search the real SmartLib catalogue (all libraries, or one library) by title, author, ISBN, topic or keyword. Returns "
                                + "matching books with per-library availability. Use it whenever the user asks whether SmartLib has a book, which "
                                + "libraries have it, what is available, or wants recommendations from SmartLib. Use short keyword queries "
                                + "(e.g. 'java', 'clean code', 'robert martin'). Results already include live per-library availability, "
                                + "so there is no need to call get_book_availability for books in the results.",
                        props("query", prop("STRING", "Keywords: title, author, ISBN or topic"),
                                "library_name", prop("STRING", "Optional: restrict to one library by name"),
                                "available_only", prop("BOOLEAN", "Only books with at least one available copy"),
                                "limit", prop("INTEGER", "Max results, 1-8 (default 6)")),
                        List.of("query")),
                fn("get_book_availability",
                        "Get fresh per-library availability for one specific book that is NOT already in recent results (search results "
                                + "and the live catalogue matches already contain availability). Prefer book_id; otherwise give the title.",
                        props("book_id", prop("INTEGER", "SmartLib book id from earlier results"),
                                "title", prop("STRING", "Book title when no id is known")),
                        List.of()),
                fn("list_libraries", "List SmartLib libraries with city, address, opening hours and collection size.", props(), List.of()),
                fn("get_library_policy", "Borrowing rules of one library: loan period, max loans, pickup window, fine per day, grace days.",
                        props("library_name", prop("STRING", "Library name")), List.of("library_name")),
                fn("get_my_loans", "The signed-in member's borrowed books with due dates, overdue days and estimated fines.",
                        props("filter", prop("STRING", "active (default), overdue, history or all")), List.of()),
                fn("get_my_requests", "The signed-in member's borrow requests and their status (pending, approved/ready for pickup, ...).",
                        props("filter", prop("STRING", "active (default) or all")), List.of()),
                fn("get_my_fines", "The signed-in member's fines, with amounts and status.",
                        props("status", prop("STRING", "unpaid (default) or all")), List.of()),
                fn("get_my_account", "The signed-in user's account summary: role, library memberships and unread notifications.", props(), List.of()));
    }

    public String statusLabel(String name) {
        return switch (name) {
            case "search_catalogue" -> "Searching the SmartLib catalogue…";
            case "get_book_availability" -> "Checking availability across libraries…";
            case "list_libraries" -> "Looking up SmartLib libraries…";
            case "get_library_policy" -> "Checking the library's borrowing rules…";
            case "get_my_loans" -> "Looking up your borrowed books…";
            case "get_my_requests" -> "Checking your requests…";
            case "get_my_fines" -> "Checking your fines…";
            case "get_my_account" -> "Checking your account…";
            default -> "Working on it…";
        };
    }

    // ---------- execution ----------

    public Outcome execute(String name, Map<String, Object> args, SmartLibPrincipal principal) {
        Map<String, Object> a = args == null ? Map.of() : args;
        return switch (name) {
            case "search_catalogue" -> search(a);
            case "get_book_availability" -> availability(a);
            case "list_libraries" -> listLibraries();
            case "get_library_policy" -> policy(a);
            case "get_my_loans" -> personal(principal, () -> myLoans(principal, str(a, "filter")));
            case "get_my_requests" -> personal(principal, () -> myRequests(principal, str(a, "filter")));
            case "get_my_fines" -> personal(principal, () -> myFines(principal, str(a, "status")));
            case "get_my_account" -> account(principal);
            default -> new Outcome(Map.of("error", "UNKNOWN_TOOL"), List.of());
        };
    }

    private Outcome search(Map<String, Object> a) {
        String query = str(a, "query");
        if (query == null || query.isBlank()) {
            return new Outcome(Map.of("error", "EMPTY_QUERY"), List.of());
        }
        int limit = Math.max(1, Math.min(8, intVal(a, "limit", 6)));
        Long libraryId = null;
        String libraryName = str(a, "library_name");
        if (libraryName != null && !libraryName.isBlank()) {
            Optional<LibraryQueryService.LibraryCard> lib = findLibrary(libraryName);
            if (lib.isEmpty()) {
                return new Outcome(Map.of("error", "LIBRARY_NOT_FOUND", "known_libraries", libraryNames()), List.of());
            }
            libraryId = lib.get().id();
        }
        boolean availableOnly = Boolean.TRUE.equals(a.get("available_only"));
        String cleaned = SearchTerms.stripFiller(query);
        PageResponse<CatalogueItem> page = catalogue.search(new CatalogueQuery(cleaned, libraryId, null, null, null, availableOnly, null, 0, limit, false));
        boolean loose = false;
        if (page.content().isEmpty()) {
            page = catalogue.search(new CatalogueQuery(cleaned, libraryId, null, null, null, availableOnly, null, 0, limit, true));
            loose = !page.content().isEmpty();
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("query", cleaned);
        if (libraryId != null) {
            response.put("library_filter", libraryName);
        }
        response.put("total_matches", page.totalElements());
        response.put("results", page.content().stream().map(ChatTools::compact).toList());
        if (page.content().isEmpty()) {
            response.put("note", "No matching books in the current SmartLib catalogue.");
        } else if (loose) {
            response.put("note", "No book matched every word; these are the closest catalogue matches.");
        }
        return new Outcome(response, page.content());
    }

    private Outcome availability(Map<String, Object> a) {
        Long id = longVal(a, "book_id");
        if (id == null) {
            String title = str(a, "title");
            if (title == null || title.isBlank()) {
                return new Outcome(Map.of("error", "BOOK_ID_OR_TITLE_REQUIRED"), List.of());
            }
            List<CatalogueItem> found = catalogue.search(new CatalogueQuery(SearchTerms.stripFiller(title), null, null, null, null, false, null, 0, 1)).content();
            if (found.isEmpty()) {
                return new Outcome(Map.of("error", "NOT_FOUND", "message", "No book titled like that in the SmartLib catalogue."), List.of());
            }
            id = found.get(0).id();
        }
        Optional<BookDetails> details = catalogue.details(id);
        if (details.isEmpty()) {
            return new Outcome(Map.of("error", "NOT_FOUND", "message", "That book is not in the current SmartLib catalogue."), List.of());
        }
        BookDetails d = details.get();
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("book_id", d.id());
        r.put("title", d.title());
        r.put("authors", String.join(", ", d.authors()));
        r.put("total_available", d.totalAvailable());
        r.put("libraries", d.holdings().stream().map(h -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("library", h.libraryName());
            m.put("city", h.city());
            m.put("available_copies", h.availableCopies());
            m.put("total_copies", h.totalCopies());
            m.put("loan_period_days", h.loanPeriodDays());
            return m;
        }).toList());
        CatalogueItem card = new CatalogueItem(d.id(), d.title(), d.subtitle(), d.authors(), d.isbn13(), d.coverUrl(), d.isbnCoverUrl(), d.category(),
                d.publishedYear(), d.language(), d.publisher(),
                d.holdings().stream().map(h -> new HoldingAvailability(h.holdingId(), h.libraryId(), h.libraryName(), h.librarySlug(), h.city(),
                        h.availableCopies(), h.totalCopies())).toList(), d.totalAvailable(), d.holdings().size());
        return new Outcome(r, List.of(card));
    }

    private Outcome listLibraries() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (LibraryQueryService.LibraryCard l : allLibraries()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("library", l.name());
            m.put("city", l.city());
            m.put("address", l.addressLine());
            m.put("opening_hours", l.openingHours());
            m.put("titles", l.titles());
            m.put("available_copies", l.availableCopies());
            m.put("page", "/libraries/" + l.slug());
            list.add(m);
        }
        return new Outcome(Map.of("libraries", list), List.of());
    }

    private Outcome policy(Map<String, Object> a) {
        Optional<LibraryQueryService.LibraryCard> lib = findLibrary(str(a, "library_name"));
        if (lib.isEmpty()) {
            return new Outcome(Map.of("error", "LIBRARY_NOT_FOUND", "known_libraries", libraryNames()), List.of());
        }
        return libraries.bySlug(lib.get().slug()).map(d -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("library", d.library().name());
            if (d.policy() != null) {
                m.put("loan_period_days", d.policy().loanPeriodDays());
                m.put("max_active_loans", d.policy().maxActiveLoans());
                m.put("pickup_window_days", d.policy().pickupWindowDays());
                m.put("fine_per_day", Money.inr(d.policy().finePerDayPaise()));
                m.put("grace_days", d.policy().graceDays());
            }
            m.put("opening_hours", d.library().openingHours());
            return new Outcome(m, List.<CatalogueItem>of());
        }).orElse(new Outcome(Map.of("error", "LIBRARY_NOT_FOUND"), List.of()));
    }

    private Outcome personal(SmartLibPrincipal p, java.util.function.Supplier<Outcome> action) {
        if (p == null) {
            return new Outcome(Map.of("error", "LOGIN_REQUIRED", "message", "The user is not signed in. Ask them to log in at /login to see personal information."),
                    List.of());
        }
        if (!p.isMember()) {
            return new Outcome(Map.of("error", "NOT_A_MEMBER_ACCOUNT", "message",
                    "This is a staff/administrator account; borrowing data exists only for member accounts."), List.of());
        }
        return action.get();
    }

    private Outcome myLoans(SmartLibPrincipal p, String filter) {
        String f = filter == null ? "ACTIVE" : filter.toUpperCase(Locale.ROOT);
        if (!CirculationQueries.LOAN_FILTERS.contains(f)) {
            f = "ACTIVE";
        }
        List<LoanView> loans = circulation.memberLoans(p.userId(), f, 0, 20).content();
        List<Map<String, Object>> list = new ArrayList<>();
        for (LoanView l : loans) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("title", l.book() == null ? null : l.book().title());
            m.put("library", l.library().name());
            m.put("status", l.status().name());
            m.put("due_date", l.dueDate().toString());
            m.put("overdue", l.overdue());
            m.put("overdue_days", l.overdueDays());
            m.put("due_soon", l.dueSoon());
            if (l.estimatedFinePaise() > 0) {
                m.put("estimated_fine_so_far", Money.inr(l.estimatedFinePaise()));
            }
            if (l.returnedAt() != null) {
                m.put("returned_at", l.returnedAt().toString());
            }
            list.add(m);
        }
        return new Outcome(Map.of("filter", f.toLowerCase(Locale.ROOT), "count", list.size(), "loans", list, "page", "/app/member/borrowings"), List.of());
    }

    private Outcome myRequests(SmartLibPrincipal p, String filter) {
        String f = "all".equalsIgnoreCase(filter) ? "ALL" : "ACTIVE";
        List<RequestView> requests = circulation.memberRequests(p.userId(), f, null, 0, 20).content();
        List<Map<String, Object>> list = new ArrayList<>();
        for (RequestView r : requests) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("title", r.book() == null ? null : r.book().title());
            m.put("library", r.library().name());
            m.put("status", r.status().name());
            m.put("requested_at", r.createdAt().toString());
            if (r.pickupDeadline() != null) {
                m.put("collect_by", r.pickupDeadline().toString());
            }
            if (r.decisionReason() != null) {
                m.put("reason", r.decisionReason());
            }
            list.add(m);
        }
        return new Outcome(Map.of("count", list.size(), "requests", list, "page", "/app/member/requests"), List.of());
    }

    private Outcome myFines(SmartLibPrincipal p, String status) {
        String s = "all".equalsIgnoreCase(status) ? "ALL" : "UNPAID";
        List<FineView> list = fines.memberFines(p.userId(), s);
        long unpaid = list.stream().filter(f -> f.status().name().equals("UNPAID")).mapToLong(FineView::amountPaise).sum();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (FineView f : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("amount", Money.inr(f.amountPaise()));
            m.put("type", f.type().name());
            m.put("status", f.status().name());
            m.put("library", f.library().name());
            m.put("reason", f.reason());
            if (f.book() != null) {
                m.put("book", f.book().title());
            }
            rows.add(m);
        }
        return new Outcome(Map.of("total_unpaid", Money.inr(unpaid), "fines", rows, "pay_at", "/app/member/fines"), List.of());
    }

    private Outcome account(SmartLibPrincipal p) {
        if (p == null) {
            return new Outcome(Map.of("signed_in", false, "message", "Not signed in."), List.of());
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("signed_in", true);
        m.put("first_name", p.fullName() == null ? null : p.fullName().split("\\s+")[0]);
        m.put("role", p.role().name());
        m.put("email_verified", p.emailVerified());
        if (p.isStaff()) {
            m.put("library", p.libraryName());
        }
        if (p.isMember()) {
            m.put("memberships", memberships.mine(p.userId()).stream().map(x -> x.libraryName() + " (" + x.status() + ")").toList());
        }
        m.put("unread_notifications", notifications.unreadCount(p.userId()));
        return new Outcome(m, List.of());
    }

    // ---------- helpers ----------

    static Map<String, Object> compact(CatalogueItem i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("book_id", i.id());
        m.put("title", i.title());
        if (i.subtitle() != null) {
            m.put("subtitle", i.subtitle());
        }
        m.put("authors", String.join(", ", i.authors()));
        if (i.publishedYear() != null) {
            m.put("year", i.publishedYear());
        }
        if (i.category() != null) {
            m.put("category", i.category().name());
        }
        m.put("total_available", i.totalAvailable());
        m.put("libraries", i.holdings().stream().map(h -> Map.of("library", h.libraryName(), "available_copies", h.availableCopies(),
                "total_copies", h.totalCopies())).toList());
        return m;
    }

    private List<LibraryQueryService.LibraryCard> allLibraries() {
        return libraryCache.get("all", k -> libraries.list(null, null));
    }

    private List<String> libraryNames() {
        return allLibraries().stream().map(LibraryQueryService.LibraryCard::name).toList();
    }

    Optional<LibraryQueryService.LibraryCard> findLibrary(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String n = name.toLowerCase(Locale.ROOT).replace("library", "").trim();
        List<LibraryQueryService.LibraryCard> all = allLibraries();
        for (LibraryQueryService.LibraryCard l : all) {
            if (l.name().equalsIgnoreCase(name.trim()) || l.slug().equalsIgnoreCase(name.trim())) {
                return Optional.of(l);
            }
        }
        for (LibraryQueryService.LibraryCard l : all) {
            String ln = l.name().toLowerCase(Locale.ROOT);
            if (!n.isEmpty() && (ln.contains(n) || (l.city() != null && l.city().equalsIgnoreCase(n)))) {
                return Optional.of(l);
            }
        }
        return Optional.empty();
    }

    private static Map<String, Object> fn(String name, String description, Map<String, Object> properties, List<String> required) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "OBJECT");
        params.put("properties", properties);
        if (!required.isEmpty()) {
            params.put("required", required);
        }
        Map<String, Object> fn = new LinkedHashMap<>();
        fn.put("name", name);
        fn.put("description", description);
        fn.put("parameters", params);
        return fn;
    }

    private static Map<String, Object> props(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static Map<String, Object> prop(String type, String description) {
        return Map.of("type", type, "description", description);
    }

    private static String str(Map<String, Object> a, String key) {
        Object v = a.get(key);
        return v == null ? null : v.toString();
    }

    private static int intVal(Map<String, Object> a, String key, int def) {
        Object v = a.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return v == null ? def : Integer.parseInt(v.toString());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static Long longVal(Map<String, Object> a, String key) {
        Object v = a.get(key);
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return v == null ? null : Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
