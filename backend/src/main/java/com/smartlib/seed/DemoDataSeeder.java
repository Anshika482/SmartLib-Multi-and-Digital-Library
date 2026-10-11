package com.smartlib.seed;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.smartlib.auth.PasswordPolicy;
import com.smartlib.catalogue.Author;
import com.smartlib.catalogue.AuthorRepository;
import com.smartlib.catalogue.Book;
import com.smartlib.catalogue.BookCopy;
import com.smartlib.catalogue.BookCopyRepository;
import com.smartlib.catalogue.BookRepository;
import com.smartlib.catalogue.Category;
import com.smartlib.catalogue.CategoryRepository;
import com.smartlib.catalogue.CopyStatus;
import com.smartlib.catalogue.CoverResolver;
import com.smartlib.catalogue.HoldingStatus;
import com.smartlib.catalogue.IsbnUtils;
import com.smartlib.catalogue.LibraryHolding;
import com.smartlib.catalogue.LibraryHoldingRepository;
import com.smartlib.catalogue.SearchTerms;
import com.smartlib.circulation.BorrowRequest;
import com.smartlib.circulation.BorrowRequestRepository;
import com.smartlib.circulation.Borrowing;
import com.smartlib.circulation.BorrowingRepository;
import com.smartlib.circulation.BorrowingStatus;
import com.smartlib.circulation.RequestStatus;
import com.smartlib.circulation.ReturnCondition;
import com.smartlib.common.config.AppProperties;
import com.smartlib.common.storage.StorageService;
import com.smartlib.common.time.AppClock;
import com.smartlib.common.util.Money;
import com.smartlib.common.util.Texts;
import com.smartlib.fine.Fine;
import com.smartlib.fine.FineCalculator;
import com.smartlib.fine.FineRepository;
import com.smartlib.fine.FineStatus;
import com.smartlib.fine.FineType;
import com.smartlib.library.Library;
import com.smartlib.library.LibraryPolicy;
import com.smartlib.library.LibraryPolicyRepository;
import com.smartlib.library.LibraryRepository;
import com.smartlib.library.LibraryStatus;
import com.smartlib.notification.NotificationService;
import com.smartlib.resource.DigitalResource;
import com.smartlib.resource.DigitalResourceRepository;
import com.smartlib.resource.ResourceType;
import com.smartlib.resource.ResourceVisibility;
import com.smartlib.user.ApprovalStatus;
import com.smartlib.user.LibraryMembership;
import com.smartlib.user.LibraryMembershipRepository;
import com.smartlib.user.MembershipStatus;
import com.smartlib.user.StaffAssignment;
import com.smartlib.user.StaffAssignmentRepository;
import com.smartlib.user.User;
import com.smartlib.user.UserRepository;
import com.smartlib.user.UserRole;
import com.smartlib.user.UserStatus;

/**
 * DEMO DATA ONLY. Populates an empty database with clearly-labelled demo libraries (*.smartlib.demo accounts), real
 * books with real ISBNs, copies and a realistic circulation state so every screen can be demonstrated. Runs only when
 * SEED_DEMO_DATA=true and the database has no libraries. The application itself never depends on this data.
 */
@Component
@Order(2)
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final AppProperties properties;
    private final LibraryRepository libraries;
    private final LibraryPolicyRepository policies;
    private final UserRepository users;
    private final StaffAssignmentRepository staff;
    private final LibraryMembershipRepository memberships;
    private final BookRepository books;
    private final AuthorRepository authors;
    private final CategoryRepository categories;
    private final LibraryHoldingRepository holdings;
    private final BookCopyRepository copies;
    private final BorrowRequestRepository requests;
    private final BorrowingRepository borrowings;
    private final FineRepository fines;
    private final DigitalResourceRepository resources;
    private final NotificationService notifications;
    private final StorageService storage;
    private final PasswordEncoder encoder;
    private final PasswordPolicy passwordPolicy;
    private final CoverResolver covers;
    private final AppClock clock;
    private final TransactionTemplate tx;

    private final Map<String, Library> libs = new LinkedHashMap<>();
    private final Map<String, Book> byIsbn = new LinkedHashMap<>();
    private final Map<String, User> people = new LinkedHashMap<>();

    public DemoDataSeeder(AppProperties properties, LibraryRepository libraries, LibraryPolicyRepository policies, UserRepository users,
                          StaffAssignmentRepository staff, LibraryMembershipRepository memberships, BookRepository books,
                          AuthorRepository authors, CategoryRepository categories, LibraryHoldingRepository holdings,
                          BookCopyRepository copies, BorrowRequestRepository requests, BorrowingRepository borrowings,
                          FineRepository fines, DigitalResourceRepository resources, NotificationService notifications,
                          StorageService storage, PasswordEncoder encoder, PasswordPolicy passwordPolicy, CoverResolver covers,
                          AppClock clock, TransactionTemplate tx) {
        this.properties = properties;
        this.libraries = libraries;
        this.policies = policies;
        this.users = users;
        this.staff = staff;
        this.memberships = memberships;
        this.books = books;
        this.authors = authors;
        this.categories = categories;
        this.holdings = holdings;
        this.copies = copies;
        this.requests = requests;
        this.borrowings = borrowings;
        this.fines = fines;
        this.resources = resources;
        this.notifications = notifications;
        this.storage = storage;
        this.encoder = encoder;
        this.passwordPolicy = passwordPolicy;
        this.covers = covers;
        this.clock = clock;
        this.tx = tx;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (properties.seed() == null || !properties.seed().demo()) {
            return;
        }
        if (libraries.count() > 0) {
            log.info("Demo seed skipped: the database already contains libraries.");
            return;
        }
        String password = properties.seed().demoPassword();
        boolean withUsers = password != null && passwordPolicy.problem(password, null) == null;
        if (!withUsers) {
            log.warn("DEMO_USER_PASSWORD is missing or too weak; seeding catalogue only (no demo accounts).");
        }
        tx.executeWithoutResult(s -> seed(withUsers ? password : null));
        byIsbn.values().forEach(b -> covers.resolveLater(b.getId()));
        log.info("Demo data seeded: {} libraries, {} books, {} demo accounts (password = DEMO_USER_PASSWORD from .env).",
                libs.size(), byIsbn.size(), people.size());
    }

    void seed(String password) {
        libs.clear();
        byIsbn.clear();
        people.clear();
        seedLibraries();
        seedBooks();
        seedHoldings();
        if (password != null) {
            seedUsers(password);
            seedCirculation();
            seedResources();
        }
    }

    // ---------------------------------------------------------------- libraries

    private void seedLibraries() {
        library("CEN", "Central Library", "Main Campus", "Pune", "Maharashtra", "Demo address: 12 University Road, Shivajinagar",
                "Mon–Sat 8:00 AM – 9:00 PM", "+91 20 4000 0101",
                "The flagship library of the SmartLib network with a large technology and reference collection.", 14, 500, 0);
        library("WEL", "West End Library", "City Center", "Mumbai", "Maharashtra", "Demo address: 45 Marine Drive",
                "Mon–Sun 9:00 AM – 8:00 PM", "+91 22 4000 0202",
                "A city-centre library known for literature, classics and a quiet reading lounge.", 21, 300, 1);
        library("RIV", "Riverside Library", "Riverside Campus", "Bengaluru", "Karnataka", "Demo address: 8 Lakeview Road, Koramangala",
                "Mon–Sat 8:00 AM – 9:00 PM", "+91 80 4000 0303",
                "A campus library by the river with strong science, engineering and research collections.", 14, 1000, 0);
        library("INV", "Innovation Library", "Tech Park", "Hyderabad", "Telangana", "Demo address: Block C, Hitec City",
                "Mon–Fri 9:00 AM – 10:00 PM", "+91 40 4000 0404",
                "A modern library inside a technology park focused on programming, startups and business.", 7, 500, 0);
        library("EWD", "Eastwood Library", "East Campus", "New Delhi", "Delhi", "Demo address: 21 Ring Road, Lajpat Nagar",
                "Tue–Sun 10:00 AM – 7:00 PM", "+91 11 4000 0505",
                "A community library with history, culture and wellbeing collections and weekend reading circles.", 14, 200, 2);
    }

    private void library(String code, String name, String tagline, String city, String state, String address, String hours,
                         String phone, String about, int loanDays, long finePerDay, int grace) {
        Library l = new Library();
        l.setCode(code);
        l.setSlug(Texts.slugify(name));
        l.setName(name);
        l.setTagline(tagline);
        l.setDescription(about + " (Demo library created by the SmartLib seed for development and demonstrations.)");
        l.setAddressLine(address);
        l.setCity(city);
        l.setState(state);
        l.setPublicEmail(code.toLowerCase() + "@smartlib.demo");
        l.setPublicPhone(phone);
        l.setOpeningHours(hours);
        l.setTimezone("Asia/Kolkata");
        l.setStatus(LibraryStatus.ACTIVE);
        libraries.save(l);
        LibraryPolicy p = LibraryPolicy.defaultsFor(l.getId());
        p.setLoanPeriodDays(loanDays);
        p.setFinePerDayPaise(finePerDay);
        p.setGraceDays(grace);
        p.setMaxFinePerLoanPaise(finePerDay * 60);
        policies.save(p);
        libs.put(code, l);
    }

    // ---------------------------------------------------------------- books

    private void seedBooks() {
        Map<String, Category> cats = new LinkedHashMap<>();
        categories.findAll().forEach(c -> cats.put(c.getSlug(), c));
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new ClassPathResource("seed/books.psv").getInputStream(),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] f = line.split("\\|", -1);
                IsbnUtils.Isbn isbn = IsbnUtils.parse(f[0])
                        .orElseThrow(() -> new IllegalStateException("Invalid ISBN in demo seed: " + f[0]));
                Category category = cats.get(f[7]);
                Book b = new Book();
                b.setIsbn13(isbn.isbn13());
                b.setIsbn10(isbn.isbn10());
                b.setTitle(f[1]);
                b.setSubtitle(Texts.trimToNull(f[2]));
                b.setPublisher(f[4]);
                b.setPublishedYear(Integer.valueOf(f[5]));
                b.setPageCount(Integer.valueOf(f[6]));
                b.setCategoryId(category == null ? null : category.getId());
                b.setLanguage(f[8]);
                b.setDescription(f[9]);
                List<Author> list = new ArrayList<>();
                for (String name : f[3].split(";")) {
                    list.add(author(name.trim()));
                }
                b.getAuthors().addAll(list);
                b.setSearchText(SearchTerms.buildSearchText(b.getTitle(), b.getSubtitle(), list.stream().map(Author::getName).toList(),
                        b.getPublisher(), b.getIsbn13(), b.getIsbn10(), category == null ? null : category.getName()));
                books.save(b);
                byIsbn.put(isbn.isbn13(), b);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Author author(String name) {
        String normalized = Texts.normalizePersonName(name);
        return authors.findByNameNormalized(normalized).orElseGet(() -> {
            Author a = new Author();
            a.setName(name);
            a.setNameNormalized(normalized);
            return authors.save(a);
        });
    }

    private void seedHoldings() {
        List<Library> list = new ArrayList<>(libs.values());
        int i = 0;
        for (Book b : byIsbn.values()) {
            if ("9780132350884".equals(b.getIsbn13())) {
                // Clean Code: deliberately held by three libraries with different availability (demo for library comparison)
                ensureHolding(libs.get("CEN"), b, 2);
                ensureHolding(libs.get("WEL"), b, 1);
                ensureHolding(libs.get("RIV"), b, 1);
            } else {
                for (int j = 0; j < list.size(); j++) {
                    if ((i + 2 * j) % 5 < 3) {
                        ensureHolding(list.get(j), b, 1 + (i + j) % 3);
                    }
                }
            }
            i++;
        }
    }

    private LibraryHolding ensureHolding(Library lib, Book book, int count) {
        LibraryHolding h = holdings.findByLibraryIdAndBookId(lib.getId(), book.getId()).orElse(null);
        if (h != null) {
            return h;
        }
        h = new LibraryHolding();
        h.setLibraryId(lib.getId());
        h.setBookId(book.getId());
        h.setStatus(HoldingStatus.ACTIVE);
        h.setShelfLocation(shelf(book));
        holdings.save(h);
        for (int n = 1; n <= count; n++) {
            BookCopy c = new BookCopy();
            c.setHoldingId(h.getId());
            c.setLibraryId(lib.getId());
            c.setAccessionNo(String.format("%s-%05d-%02d", lib.getCode(), book.getId(), n));
            c.setStatus(CopyStatus.AVAILABLE);
            c.setAcquiredAt(clock.now().minus(Duration.ofDays(120)));
            copies.save(c);
        }
        return h;
    }

    private static String shelf(Book b) {
        String t = b.getTitle().replaceAll("[^A-Za-z]", "");
        return "Aisle " + (1 + Math.abs(b.getTitle().hashCode()) % 12) + " · " + (t.isEmpty() ? "A" : t.substring(0, 1).toUpperCase());
    }

    // ---------------------------------------------------------------- users

    private void seedUsers(String password) {
        String hash = encoder.encode(password);
        user("superadmin@smartlib.demo", "Platform Administrator", UserRole.SUPER_ADMIN, null, null, hash);
        user("admin.central@smartlib.demo", "Vikram Desai", UserRole.ADMIN, "CEN", ApprovalStatus.APPROVED, hash);
        user("admin.westend@smartlib.demo", "Neha Joshi", UserRole.ADMIN, "WEL", ApprovalStatus.APPROVED, hash);
        user("admin.riverside@smartlib.demo", "Farhan Ali", UserRole.ADMIN, "RIV", ApprovalStatus.APPROVED, hash);
        user("librarian.central@smartlib.demo", "Meera Kulkarni", UserRole.LIBRARIAN, "CEN", ApprovalStatus.APPROVED, hash);
        user("librarian.westend@smartlib.demo", "Kabir Khan", UserRole.LIBRARIAN, "WEL", ApprovalStatus.APPROVED, hash);
        user("librarian.riverside@smartlib.demo", "Divya Rao", UserRole.LIBRARIAN, "RIV", ApprovalStatus.APPROVED, hash);
        user("librarian.innovation@smartlib.demo", "Arjun Nair", UserRole.LIBRARIAN, "INV", ApprovalStatus.APPROVED, hash);
        user("librarian.eastwood@smartlib.demo", "Ishita Bose", UserRole.LIBRARIAN, "EWD", ApprovalStatus.APPROVED, hash);
        user("pending.librarian@smartlib.demo", "Rahul Verma", UserRole.LIBRARIAN, "CEN", ApprovalStatus.PENDING, hash);
        user("rejected.librarian@smartlib.demo", "Sameer Gupta", UserRole.LIBRARIAN, "WEL", ApprovalStatus.REJECTED, hash);
        user("member@smartlib.demo", "Ananya Iyer", UserRole.MEMBER, null, null, hash);
        user("aarav.sharma@smartlib.demo", "Aarav Sharma", UserRole.MEMBER, null, null, hash);
        user("priya.singh@smartlib.demo", "Priya Singh", UserRole.MEMBER, null, null, hash);
        user("rohan.mehta@smartlib.demo", "Rohan Mehta", UserRole.MEMBER, null, null, hash);
        user("sneha.patel@smartlib.demo", "Sneha Patel", UserRole.MEMBER, null, null, hash);
    }

    private void user(String email, String name, UserRole role, String libCode, ApprovalStatus approval, String hash) {
        User u = new User();
        u.setEmail(email);
        u.setFullName(name);
        u.setPasswordHash(hash);
        u.setRole(role);
        u.setStatus(UserStatus.ACTIVE);
        u.setEmailVerifiedAt(clock.now().minus(Duration.ofDays(60)));
        u.setTermsAcceptedAt(clock.now().minus(Duration.ofDays(60)));
        u.setCreatedAt(clock.now().minus(Duration.ofDays(60)));
        users.save(u);
        if (libCode != null) {
            StaffAssignment a = new StaffAssignment();
            a.setUserId(u.getId());
            a.setLibraryId(libs.get(libCode).getId());
            a.setApprovalStatus(approval);
            a.setEmployeeRef(approval == ApprovalStatus.PENDING ? "EMP-2026-118" : null);
            a.setApplicationNote(approval == ApprovalStatus.PENDING ? "Weekend circulation desk volunteer, applying for a librarian role." : null);
            if (approval == ApprovalStatus.REJECTED) {
                a.setRejectionReason("We could not match this application to our staff roster.");
                a.setReviewedAt(clock.now().minus(Duration.ofDays(3)));
            }
            staff.save(a);
        }
        people.put(email, u);
    }

    // ---------------------------------------------------------------- circulation

    private void seedCirculation() {
        User aarav = people.get("aarav.sharma@smartlib.demo");
        User priya = people.get("priya.singh@smartlib.demo");
        User rohan = people.get("rohan.mehta@smartlib.demo");
        User sneha = people.get("sneha.patel@smartlib.demo");
        User ananya = people.get("member@smartlib.demo");
        Library cen = libs.get("CEN");
        Library wel = libs.get("WEL");
        Library riv = libs.get("RIV");
        Library inv = libs.get("INV");
        Library ewd = libs.get("EWD");

        loan(aarav, cen, "9780132350884", 9, 5);          // Clean Code, due in 5 days
        loan(aarav, cen, "9780735211292", 20, -6);        // Atomic Habits, 6 days overdue
        pending(aarav, riv, "9780441172719", 5);           // Dune, pending at Riverside

        returned(priya, wel, "9780062316097", 30, 9, 5);   // Sapiens, returned 4 days late -> fine
        loan(priya, wel, "9781455586691", 19, 2);          // Deep Work, due in 2 days
        loan(priya, wel, "9780132350884", 3, 18);          // Clean Code at West End (its only copy)

        approved(rohan, cen, "9780134685991", 2);          // Effective Java ready for pickup
        pending(rohan, cen, "9781491910771", 26);          // Head First Java pending

        pending(sneha, cen, "9780135957059", 3);           // The Pragmatic Programmer pending
        returned(sneha, cen, "9780062315007", 40, 26, 28); // The Alchemist, returned on time

        loan(ananya, inv, "9780857197689", 4, 3);          // The Psychology of Money
        returned(ananya, inv, "9780593135204", 21, 14, 9); // Project Hail Mary, 5 days late -> unpaid fine (Razorpay demo)
        pending(ananya, ewd, "9780062316097", 1);          // Sapiens pending at Eastwood
    }

    private User staffOf(Library lib) {
        return people.values().stream().filter(u -> u.getRole() == UserRole.LIBRARIAN)
                .filter(u -> staff.findByUserId(u.getId()).map(a -> lib.getId().equals(a.getLibraryId())
                        && a.getApprovalStatus() == ApprovalStatus.APPROVED).orElse(false))
                .findFirst().orElse(people.get("superadmin@smartlib.demo"));
    }

    private void member(User u, Library lib) {
        if (memberships.findByUserIdAndLibraryId(u.getId(), lib.getId()).isEmpty()) {
            LibraryMembership m = new LibraryMembership();
            m.setUserId(u.getId());
            m.setLibraryId(lib.getId());
            m.setStatus(MembershipStatus.ACTIVE);
            m.setJoinedAt(clock.now().minus(Duration.ofDays(45)));
            memberships.save(m);
        }
    }

    private BookCopy takeCopy(Library lib, Book book, CopyStatus to) {
        LibraryHolding h = ensureHolding(lib, book, 2);
        BookCopy c = copies.findByHoldingIdOrderByIdAsc(h.getId()).stream().filter(x -> x.getStatus() == CopyStatus.AVAILABLE).findFirst()
                .orElseGet(() -> {
                    BookCopy extra = new BookCopy();
                    extra.setHoldingId(h.getId());
                    extra.setLibraryId(lib.getId());
                    extra.setAccessionNo(String.format("%s-%05d-%02d", lib.getCode(), book.getId(), 90 + copies.countByHoldingId(h.getId())));
                    extra.setStatus(CopyStatus.AVAILABLE);
                    return copies.save(extra);
                });
        c.setStatus(to);
        return copies.save(c);
    }

    private BorrowRequest request(User m, Library lib, Book book, RequestStatus status, Instant created, User decider) {
        LibraryHolding h = ensureHolding(lib, book, 2);
        BorrowRequest r = new BorrowRequest();
        r.setMemberId(m.getId());
        r.setLibraryId(lib.getId());
        r.setHoldingId(h.getId());
        r.setBookId(book.getId());
        r.setStatus(status);
        r.setCreatedAt(created);
        if (decider != null) {
            r.setDecidedBy(decider.getId());
            r.setDecidedAt(created.plus(Duration.ofHours(3)));
        }
        return requests.save(r);
    }

    private void loan(User m, Library lib, String isbn, int issuedDaysAgo, int dueInDays) {
        Book book = byIsbn.get(isbn);
        User issuer = staffOf(lib);
        member(m, lib);
        BookCopy copy = takeCopy(lib, book, CopyStatus.BORROWED);
        Instant issued = clock.now().minus(Duration.ofDays(issuedDaysAgo));
        BorrowRequest r = request(m, lib, book, RequestStatus.FULFILLED, issued.minus(Duration.ofDays(1)), issuer);
        r.setAssignedCopyId(copy.getId());
        requests.save(r);
        Borrowing b = new Borrowing();
        b.setRequestId(r.getId());
        b.setMemberId(m.getId());
        b.setLibraryId(lib.getId());
        b.setHoldingId(r.getHoldingId());
        b.setBookId(book.getId());
        b.setCopyId(copy.getId());
        b.setIssuedBy(issuer.getId());
        b.setIssuedAt(issued);
        b.setDueDate(today().plusDays(dueInDays));
        b.setStatus(BorrowingStatus.ACTIVE);
        b.setCreatedAt(issued);
        borrowings.save(b);
        notifications.notify(m.getId(), lib.getId(), "BOOK_ISSUED", "Borrowed: " + book.getTitle(),
                "Please return it to " + lib.getName() + " by " + b.getDueDate() + ".", "/app/member/borrowings");
        if (dueInDays < 0) {
            notifications.notify(m.getId(), lib.getId(), "OVERDUE", "Overdue: " + book.getTitle(),
                    "This book was due on " + b.getDueDate() + ". Please return it to " + lib.getName() + " as soon as possible; a fine is accruing.",
                    "/app/member/borrowings");
        } else if (dueInDays <= 2) {
            notifications.notify(m.getId(), lib.getId(), "DUE_SOON", "Due in " + dueInDays + " days: " + book.getTitle(),
                    "Please return it to " + lib.getName() + " by " + b.getDueDate() + ".", "/app/member/borrowings");
        }
    }

    private void returned(User m, Library lib, String isbn, int issuedDaysAgo, int dueDaysAgo, int returnedDaysAgo) {
        Book book = byIsbn.get(isbn);
        User issuer = staffOf(lib);
        member(m, lib);
        BookCopy copy = takeCopy(lib, book, CopyStatus.AVAILABLE);
        Instant issued = clock.now().minus(Duration.ofDays(issuedDaysAgo));
        BorrowRequest r = request(m, lib, book, RequestStatus.FULFILLED, issued.minus(Duration.ofDays(1)), issuer);
        r.setAssignedCopyId(copy.getId());
        requests.save(r);
        Borrowing b = new Borrowing();
        b.setRequestId(r.getId());
        b.setMemberId(m.getId());
        b.setLibraryId(lib.getId());
        b.setHoldingId(r.getHoldingId());
        b.setBookId(book.getId());
        b.setCopyId(copy.getId());
        b.setIssuedBy(issuer.getId());
        b.setIssuedAt(issued);
        b.setDueDate(today().minusDays(dueDaysAgo));
        b.setReturnedAt(clock.now().minus(Duration.ofDays(returnedDaysAgo)));
        b.setReturnedTo(issuer.getId());
        b.setReturnCondition(ReturnCondition.GOOD);
        b.setStatus(BorrowingStatus.RETURNED);
        b.setCreatedAt(issued);
        borrowings.save(b);
        LibraryPolicy p = policies.findById(lib.getId()).orElseThrow();
        LocalDate returnDate = today().minusDays(returnedDaysAgo);
        long amount = FineCalculator.amount(b.getDueDate(), returnDate, p.getGraceDays(), p.getFinePerDayPaise(), p.getMaxFinePerLoanPaise());
        if (amount > 0) {
            long days = FineCalculator.overdueDays(b.getDueDate(), returnDate);
            Fine f = new Fine();
            f.setMemberId(m.getId());
            f.setLibraryId(lib.getId());
            f.setBorrowingId(b.getId());
            f.setType(FineType.OVERDUE);
            f.setAmountPaise(amount);
            f.setOverdueDays((int) days);
            f.setStatus(FineStatus.UNPAID);
            f.setReason("Returned " + days + " days after the due date (" + b.getDueDate() + ")");
            f.setCreatedBy(issuer.getId());
            f.setCreatedAt(b.getReturnedAt());
            fines.save(f);
            notifications.notify(m.getId(), lib.getId(), "FINE_CREATED", "New fine: " + Money.inr(amount), f.getReason() + " · " + lib.getName(),
                    "/app/member/fines");
        }
    }

    private void pending(User m, Library lib, String isbn, int hoursAgo) {
        Book book = byIsbn.get(isbn);
        member(m, lib);
        ensureHolding(lib, book, 2);
        request(m, lib, book, RequestStatus.PENDING, clock.now().minus(Duration.ofHours(hoursAgo)), null);
        for (Long staffId : notifications.staffOf(lib.getId())) {
            notifications.notify(staffId, lib.getId(), "REQUEST_RECEIVED", "New borrow request", m.getFullName() + " requested \"" + book.getTitle() + "\".",
                    users.findById(staffId).map(u -> u.getRole() == UserRole.ADMIN ? "/app/admin/requests" : "/app/librarian/requests").orElse(null));
        }
    }

    private void approved(User m, Library lib, String isbn, int pickupInDays) {
        Book book = byIsbn.get(isbn);
        User librarian = staffOf(lib);
        member(m, lib);
        BookCopy copy = takeCopy(lib, book, CopyStatus.RESERVED);
        BorrowRequest r = request(m, lib, book, RequestStatus.APPROVED, clock.now().minus(Duration.ofHours(30)), librarian);
        r.setAssignedCopyId(copy.getId());
        r.setPickupDeadline(today().plusDays(pickupInDays));
        requests.save(r);
        notifications.notify(m.getId(), lib.getId(), "REQUEST_APPROVED", "Ready for pickup: " + book.getTitle(),
                "Your request was approved. Collect it from " + lib.getName() + " by " + r.getPickupDeadline() + ".", "/app/member/requests");
    }

    // ---------------------------------------------------------------- digital resources

    private void seedResources() {
        link("CEN", "9781491910771", "The Java Tutorials (Oracle)", "Official tutorials covering Java language basics, collections and concurrency.",
                "https://docs.oracle.com/javase/tutorial/", ResourceVisibility.PUBLIC);
        link("WEL", "9780141439518", "Pride and Prejudice — free eBook (Project Gutenberg)", "The public-domain text of Jane Austen's novel.",
                "https://www.gutenberg.org/ebooks/1342", ResourceVisibility.PUBLIC);
        link("RIV", "9780262033848", "MIT OpenCourseWare: Introduction to Algorithms", "Free lecture notes, videos and problem sets from MIT 6.006.",
                "https://ocw.mit.edu/courses/6-006-introduction-to-algorithms-spring-2020/", ResourceVisibility.PUBLIC);
        link("INV", "9781718502703", "The Python Tutorial (python.org)", "The official tutorial for the Python programming language.",
                "https://docs.python.org/3/tutorial/", ResourceVisibility.PUBLIC);
        link("INV", "9781593279509", "MDN JavaScript Guide", "Mozilla's guide to the JavaScript language, from grammar to modules.",
                "https://developer.mozilla.org/en-US/docs/Web/JavaScript/Guide", ResourceVisibility.REGISTERED);
        link("EWD", "9781617297571", "Spring Guides", "Short official guides for building applications with Spring.",
                "https://spring.io/guides", ResourceVisibility.PUBLIC);
        pdf("CEN", "Central Library — Member Guide",
                "How borrowing, pickups, returns and fines work at Central Library (members only).", ResourceVisibility.LIBRARY_MEMBERS,
                List.of("Welcome to Central Library (SmartLib demo library).", "",
                        "1. Find a book on SmartLib and open its page to see availability per library.",
                        "2. Choose Central Library and press 'Request'. A librarian reviews your request.",
                        "3. When approved, a copy is reserved for you. Collect it within 3 days.",
                        "4. Loans last 14 days. You will get reminders 2 days before the due date.",
                        "5. Late returns cost Rs. 5 per day, capped per loan. Pay online with Razorpay.",
                        "6. Lost or damaged books may be charged at replacement cost.", "",
                        "Opening hours: Mon-Sat 8:00 AM - 9:00 PM.", "Questions? Ask the SmartLib AI assistant at /assistant."));
        pdf("WEL", "West End Library — Library Rules",
                "Borrowing rules and fine policy for West End Library (signed-in users).", ResourceVisibility.REGISTERED,
                List.of("West End Library (SmartLib demo library) - Library Rules", "",
                        "- Loan period: 21 days.", "- Overdue fine: Rs. 3 per day after 1 grace day.",
                        "- Up to 5 books on loan at a time and 3 pending requests.",
                        "- Approved requests must be collected within 3 days.",
                        "- Please keep the reading lounge quiet and return books to the desk."));
    }

    private User uploader(String libCode) {
        return staffOf(libs.get(libCode));
    }

    private void link(String libCode, String isbn, String title, String description, String url, ResourceVisibility visibility) {
        DigitalResource r = new DigitalResource();
        r.setLibraryId(libs.get(libCode).getId());
        Book book = byIsbn.get(isbn);
        if (book != null) {
            ensureHolding(libs.get(libCode), book, 1);
            r.setBookId(book.getId());
        }
        r.setTitle(title);
        r.setDescription(description);
        r.setType(ResourceType.LINK);
        r.setVisibility(visibility);
        r.setExternalUrl(url);
        r.setUploadedBy(uploader(libCode).getId());
        resources.save(r);
    }

    private void pdf(String libCode, String title, String description, ResourceVisibility visibility, List<String> lines) {
        byte[] bytes = MiniPdf.create(title.replace("—", "-"), lines);
        String key = storage.store("resources", new ByteArrayInputStream(bytes), "pdf");
        DigitalResource r = new DigitalResource();
        r.setLibraryId(libs.get(libCode).getId());
        r.setTitle(title);
        r.setDescription(description);
        r.setType(ResourceType.PDF);
        r.setVisibility(visibility);
        r.setStorageKey(key);
        r.setOriginalFilename(Texts.slugify(title) + ".pdf");
        r.setMimeType("application/pdf");
        r.setSizeBytes((long) bytes.length);
        r.setUploadedBy(uploader(libCode).getId());
        resources.save(r);
    }

    private LocalDate today() {
        return clock.today(AppClock.DEFAULT_ZONE);
    }
}
