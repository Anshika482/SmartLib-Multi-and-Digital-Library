package com.smartlib.notification;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.smartlib.common.events.DomainEvents;
import com.smartlib.common.util.Money;
import com.smartlib.mail.MailComposer;
import com.smartlib.mail.MailService;

/**
 * Turns domain events into in-app notifications and (non-secret) emails, after the business transaction commits.
 * Each handler runs in its own transaction so a notification failure can never undo a loan, fine or payment.
 */
@Component
public class NotificationListener {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.ENGLISH);

    private final NotificationService notifications;
    private final MailService mail;
    private final MailComposer composer;
    private final JdbcTemplate jdbc;

    public NotificationListener(NotificationService notifications, MailService mail, MailComposer composer, JdbcTemplate jdbc) {
        this.notifications = notifications;
        this.mail = mail;
        this.composer = composer;
        this.jdbc = jdbc;
    }

    record Person(long id, String email, String name, String role) {
        String first() {
            return name == null ? "there" : name.trim().split("\\s+")[0];
        }
    }

    record RequestInfo(long id, long memberId, long libraryId, String libraryName, String title, java.time.LocalDate pickupDeadline,
                       String reason) {
    }

    record LoanInfo(long id, long memberId, long libraryId, String libraryName, String title, java.time.LocalDate dueDate) {
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onVerified(DomainEvents.UserVerified e) {
        Person p = person(e.userId());
        notifications.notify(p.id(), null, "WELCOME", "Welcome to SmartLib, " + p.first() + "!",
                "Your email is verified. Explore books across every library, request titles and chat with the AI assistant.", "/catalogue");
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onLibrarianApplied(DomainEvents.LibrarianApplied e) {
        Person applicant = person(e.userId());
        String library = libraryName(e.libraryId());
        for (Long adminId : notifications.adminsOf(e.libraryId())) {
            Person admin = person(adminId);
            String url = "SUPER_ADMIN".equals(admin.role()) ? "/app/super-admin/libraries/" + e.libraryId() : "/app/admin/librarians";
            notifications.notify(adminId, e.libraryId(), "LIBRARIAN_APPLICATION", "New librarian application",
                    applicant.name() + " applied to work as a librarian at " + library + ".", url);
            mail.enqueue(composer.build(composer.to(admin.email()).subject("New librarian application · " + library)
                    .title("New librarian application").greeting("Hi " + admin.first() + ",")
                    .paragraph(applicant.name() + " (" + applicant.email() + ") has applied to join " + library + " as a librarian.")
                    .paragraph("The applicant must verify their email before you can approve the application.")
                    .cta("Review applications", composer.link(url))));
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onAdminApplied(DomainEvents.AdminApplied e) {
        Person applicant = person(e.userId());
        boolean superAdmin = "SUPER_ADMIN".equals(e.role());
        String what = superAdmin ? "Super Admin" : "admin";
        String target = superAdmin ? "the SmartLib platform" : libraryName(e.libraryId());
        for (Long reviewerId : notifications.superAdmins()) {
            Person reviewer = person(reviewerId);
            notifications.notify(reviewerId, e.libraryId(), superAdmin ? "SUPER_ADMIN_APPLICATION" : "ADMIN_APPLICATION",
                    "New " + what + " application", applicant.name() + " applied for " + what + " access to " + target + ".",
                    "/app/super-admin/applications");
            mail.enqueue(composer.build(composer.to(reviewer.email()).subject("New " + what + " application · " + applicant.name())
                    .title("New " + what + " application").greeting("Hi " + reviewer.first() + ",")
                    .paragraph(applicant.name() + " (" + applicant.email() + ") has applied for " + what + " access to " + target + ".")
                    .paragraph("The applicant must verify their email before you can approve the application. Only approve people you can identify.")
                    .cta("Review applications", composer.link("/app/super-admin/applications"))));
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onAdminReviewed(DomainEvents.AdminReviewed e) {
        Person p = person(e.userId());
        boolean superAdmin = "SUPER_ADMIN".equals(e.role());
        String what = superAdmin ? "Super Admin" : "admin";
        String target = superAdmin ? "the SmartLib platform" : libraryName(e.libraryId());
        if (e.approved()) {
            String home = superAdmin ? "/app/super-admin" : "/app/admin";
            notifications.notify(p.id(), e.libraryId(), superAdmin ? "SUPER_ADMIN_APPROVED" : "ADMIN_APPROVED",
                    "Your " + what + " application was approved", "You can now manage " + target + " in SmartLib.", home);
            mail.enqueue(composer.build(composer.to(p.email()).subject("You're approved as " + (superAdmin ? "a Super Admin" : "an admin of " + target))
                    .title("Application approved").greeting("Hi " + p.first() + ",")
                    .paragraph("Your application for " + what + " access to " + target + " has been approved. You can now log in to your workspace.")
                    .cta("Log in to SmartLib", composer.link("/login"))));
        } else {
            notifications.notify(p.id(), e.libraryId(), superAdmin ? "SUPER_ADMIN_REJECTED" : "ADMIN_REJECTED",
                    "Your " + what + " application was not approved", e.reason(), null);
            mail.enqueue(composer.build(composer.to(p.email()).subject("Update on your " + what + " application")
                    .title("Application not approved").greeting("Hi " + p.first() + ",")
                    .paragraph("Your application for " + what + " access to " + target + " was not approved.")
                    .fact("Reason", e.reason())
                    .paragraph("If you believe this is a mistake, please contact a SmartLib Super Admin.")));
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onLibrarianReviewed(DomainEvents.LibrarianReviewed e) {
        Person p = person(e.userId());
        String library = libraryName(e.libraryId());
        if (e.approved()) {
            notifications.notify(p.id(), e.libraryId(), "LIBRARIAN_APPROVED", "Your librarian application was approved",
                    "You can now manage " + library + " in SmartLib.", "/app/librarian");
            mail.enqueue(composer.build(composer.to(p.email()).subject("You're approved as a librarian at " + library)
                    .title("Application approved").greeting("Hi " + p.first() + ",")
                    .paragraph("Your application to work as a librarian at " + library + " has been approved. You can now log in to your librarian workspace.")
                    .cta("Open librarian dashboard", composer.link("/login"))));
        } else {
            notifications.notify(p.id(), e.libraryId(), "LIBRARIAN_REJECTED", "Your librarian application was not approved",
                    e.reason(), null);
            mail.enqueue(composer.build(composer.to(p.email()).subject("Update on your librarian application at " + library)
                    .title("Application not approved").greeting("Hi " + p.first() + ",")
                    .paragraph("Your application to work as a librarian at " + library + " was not approved.")
                    .fact("Reason", e.reason())
                    .paragraph("If you believe this is a mistake, please contact the library administrator.")));
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onRequestCreated(DomainEvents.RequestCreated e) {
        RequestInfo r = request(e.requestId());
        Person member = person(r.memberId());
        notifications.notify(member.id(), r.libraryId(), "REQUEST_SUBMITTED", "Request sent to " + r.libraryName(),
                "Your request for \"" + r.title() + "\" is waiting for the librarian's review.", "/app/member/requests");
        for (Long staffId : notifications.staffOf(r.libraryId())) {
            Person staff = person(staffId);
            String url = "ADMIN".equals(staff.role()) ? "/app/admin/requests" : "/app/librarian/requests";
            notifications.notify(staffId, r.libraryId(), "REQUEST_RECEIVED", "New borrow request",
                    member.name() + " requested \"" + r.title() + "\".", url);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onRequestApproved(DomainEvents.RequestApproved e) {
        RequestInfo r = request(e.requestId());
        Person m = person(r.memberId());
        String deadline = r.pickupDeadline() == null ? null : DATE.format(r.pickupDeadline());
        notifications.notify(m.id(), r.libraryId(), "REQUEST_APPROVED", "Ready for pickup: " + r.title(),
                "Your request was approved. Collect it from " + r.libraryName() + (deadline == null ? "." : " by " + deadline + "."),
                "/app/member/requests");
        mail.enqueue(composer.build(composer.to(m.email()).subject("Approved: \"" + r.title() + "\" is ready for pickup")
                .title("Your book is ready for pickup").greeting("Hi " + m.first() + ",")
                .paragraph("Good news! " + r.libraryName() + " approved your request and has set a copy aside for you.")
                .fact("Book", r.title()).fact("Library", r.libraryName()).fact("Collect by", deadline)
                .cta("View my requests", composer.link("/app/member/requests"))));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onRequestRejected(DomainEvents.RequestRejected e) {
        RequestInfo r = request(e.requestId());
        Person m = person(r.memberId());
        notifications.notify(m.id(), r.libraryId(), "REQUEST_REJECTED", "Request not approved: " + r.title(), r.reason(), "/app/member/requests");
        mail.enqueue(composer.build(composer.to(m.email()).subject("Your request for \"" + r.title() + "\" was not approved")
                .title("Request not approved").greeting("Hi " + m.first() + ",")
                .paragraph(r.libraryName() + " could not approve your request.").fact("Book", r.title()).fact("Reason", r.reason())
                .cta("Find another copy", composer.link("/catalogue"))));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onRequestExpired(DomainEvents.RequestExpired e) {
        RequestInfo r = request(e.requestId());
        notifications.notify(r.memberId(), r.libraryId(), "REQUEST_EXPIRED", "Pickup window ended: " + r.title(),
                "The copy reserved for you at " + r.libraryName() + " was released because it wasn't collected in time.", "/app/member/requests");
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onIssued(DomainEvents.BookIssued e) {
        LoanInfo l = loan(e.borrowingId());
        Person m = person(l.memberId());
        String due = DATE.format(l.dueDate());
        notifications.notify(m.id(), l.libraryId(), "BOOK_ISSUED", "Borrowed: " + l.title(), "Please return it to " + l.libraryName() + " by " + due + ".",
                "/app/member/borrowings");
        mail.enqueue(composer.build(composer.to(m.email()).subject("You borrowed \"" + l.title() + "\" — due " + due)
                .title("Enjoy your book!").greeting("Hi " + m.first() + ",")
                .fact("Book", l.title()).fact("Library", l.libraryName()).fact("Due date", due)
                .paragraph("We'll remind you before the due date. Late returns may incur a fine according to the library's policy.")
                .cta("View my borrowed books", composer.link("/app/member/borrowings"))));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onReturned(DomainEvents.BookReturned e) {
        LoanInfo l = loan(e.borrowingId());
        notifications.notify(l.memberId(), l.libraryId(), "BOOK_RETURNED", "Return recorded: " + l.title(),
                "Thanks! " + l.libraryName() + " has recorded your return.", "/app/member/borrowings");
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onDueReminder(DomainEvents.DueReminder e) {
        LoanInfo l = loan(e.borrowingId());
        Person m = person(l.memberId());
        String due = DATE.format(l.dueDate());
        String title;
        String body;
        String type = e.kind();
        switch (e.kind()) {
            case "DUE_SOON" -> {
                title = "Due in 2 days: " + l.title();
                body = "Please return it to " + l.libraryName() + " by " + due + ".";
            }
            case "DUE_TODAY" -> {
                title = "Due today: " + l.title();
                body = "Return it to " + l.libraryName() + " today to avoid a late fine.";
            }
            default -> {
                title = "Overdue: " + l.title();
                body = "This book was due on " + due + " (" + e.daysOverdue() + (e.daysOverdue() == 1 ? " day" : " days")
                        + " ago). Please return it to " + l.libraryName() + " as soon as possible; a fine is accruing.";
            }
        }
        notifications.notify(m.id(), l.libraryId(), type, title, body, "/app/member/borrowings");
        mail.enqueue(composer.build(composer.to(m.email()).subject(title).title(title).greeting("Hi " + m.first() + ",").paragraph(body)
                .fact("Library", l.libraryName()).fact("Due date", due).cta("View my borrowed books", composer.link("/app/member/borrowings"))));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onFineCreated(DomainEvents.FineCreated e) {
        record F(long memberId, long libraryId, String libraryName, long amount, String type, String reason) {
        }
        F f = jdbc.queryForObject("SELECT f.member_id, f.library_id, l.name, f.amount_paise, f.type, f.reason FROM fines f JOIN libraries l ON l.id = f.library_id WHERE f.id = ?",
                (rs, i) -> new F(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getLong(4), rs.getString(5), rs.getString(6)), e.fineId());
        Person m = person(f.memberId());
        notifications.notify(m.id(), f.libraryId(), "FINE_CREATED", "New fine: " + Money.inr(f.amount()), f.reason() + " · " + f.libraryName(),
                "/app/member/fines");
        mail.enqueue(composer.build(composer.to(m.email()).subject("A fine of " + Money.inr(f.amount()) + " was added to your account")
                .title("Fine added").greeting("Hi " + m.first() + ",")
                .fact("Amount", Money.inr(f.amount())).fact("Library", f.libraryName()).fact("Reason", f.reason())
                .paragraph("You can pay securely online with Razorpay from your SmartLib dashboard.")
                .cta("View and pay fines", composer.link("/app/member/fines"))));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onFineWaived(DomainEvents.FineWaived e) {
        jdbc.query("SELECT f.member_id, f.library_id, f.amount_paise FROM fines f WHERE f.id = ?", rs -> {
            notifications.notify(rs.getLong(1), rs.getLong(2), "FINE_WAIVED", "Fine waived: " + Money.inr(rs.getLong(3)),
                    "The library has waived this fine. Nothing is owed.", "/app/member/fines");
        }, e.fineId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onPayment(DomainEvents.PaymentSucceeded e) {
        record P(long memberId, long libraryId, String libraryName, long amount, String paymentId, String orderId) {
        }
        P p = jdbc.queryForObject("SELECT pa.member_id, pa.library_id, l.name, pa.amount_paise, pa.provider_payment_id, pa.provider_order_id"
                        + " FROM payments pa JOIN libraries l ON l.id = pa.library_id WHERE pa.id = ?",
                (rs, i) -> new P(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getLong(4), rs.getString(5), rs.getString(6)), e.paymentId());
        Person m = person(p.memberId());
        notifications.notify(m.id(), p.libraryId(), "PAYMENT_SUCCESS", "Payment received: " + Money.inr(p.amount()),
                "Your fine at " + p.libraryName() + " is now paid.", "/app/member/payments");
        mail.enqueue(composer.build(composer.to(m.email()).subject("Payment receipt · " + Money.inr(p.amount()))
                .title("Payment successful").greeting("Hi " + m.first() + ",")
                .paragraph("We've received your payment and marked the fine as paid.")
                .fact("Amount", Money.inr(p.amount())).fact("Library", p.libraryName()).fact("Payment ID", p.paymentId())
                .fact("Order ID", p.orderId()).cta("View payment history", composer.link("/app/member/payments"))));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onResource(DomainEvents.ResourcePublished e) {
        record R(long libraryId, String libraryName, String title, String type, String visibility) {
        }
        R r = jdbc.queryForObject("SELECT r.library_id, l.name, r.title, r.type, r.visibility FROM digital_resources r JOIN libraries l ON l.id = r.library_id WHERE r.id = ?",
                (rs, i) -> new R(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)), e.resourceId());
        notifications.notifyAll(notifications.membersOf(r.libraryId()), r.libraryId(), "RESOURCE_AVAILABLE",
                "New " + r.type() + " at " + r.libraryName(), "\"" + r.title() + "\" is now available in Digital Resources.",
                "/app/member/resources");
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onAnnouncement(DomainEvents.Announcement e) {
        String library = libraryName(e.libraryId());
        List<Long> members = notifications.membersOf(e.libraryId());
        notifications.notifyAll(members, e.libraryId(), "ANNOUNCEMENT", library + ": " + e.title(), e.body(), null);
        if (e.sendEmail()) {
            for (Long id : members) {
                Person m = person(id);
                mail.enqueue(composer.build(composer.to(m.email()).subject(library + ": " + e.title()).title(e.title())
                        .greeting("Hi " + m.first() + ",").paragraph(e.body()).footnote("Sent by " + library + " via SmartLib.")));
            }
        }
    }

    // ---------- lookups ----------

    private Person person(long id) {
        return jdbc.queryForObject("SELECT id, email, full_name, role FROM users WHERE id = ?",
                (rs, i) -> new Person(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)), id);
    }

    private String libraryName(long id) {
        return jdbc.queryForObject("SELECT name FROM libraries WHERE id = ?", String.class, id);
    }

    private RequestInfo request(long id) {
        return jdbc.queryForObject("""
                SELECT r.id, r.member_id, r.library_id, l.name, b.title, r.pickup_deadline, r.decision_reason
                FROM borrow_requests r JOIN libraries l ON l.id = r.library_id JOIN books b ON b.id = r.book_id WHERE r.id = ?""",
                (rs, i) -> new RequestInfo(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4), rs.getString(5),
                        rs.getDate(6) == null ? null : rs.getDate(6).toLocalDate(), rs.getString(7)), id);
    }

    private LoanInfo loan(long id) {
        return jdbc.queryForObject("""
                SELECT bo.id, bo.member_id, bo.library_id, l.name, b.title, bo.due_date
                FROM borrowings bo JOIN libraries l ON l.id = bo.library_id JOIN books b ON b.id = bo.book_id WHERE bo.id = ?""",
                (rs, i) -> new LoanInfo(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4), rs.getString(5),
                        rs.getDate(6).toLocalDate()), id);
    }
}
