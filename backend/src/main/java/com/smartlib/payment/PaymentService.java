package com.smartlib.payment;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.smartlib.audit.AuditActions;
import com.smartlib.audit.AuditService;
import com.smartlib.catalogue.CatalogueDtos.BookSummary;
import com.smartlib.catalogue.CatalogueQueryService;
import com.smartlib.circulation.CirculationDtos.LibraryRef;
import com.smartlib.circulation.CirculationDtos.MemberRef;
import com.smartlib.common.error.ApiException;
import com.smartlib.common.error.ErrorCodes;
import com.smartlib.common.events.DomainEvents;
import com.smartlib.common.time.AppClock;
import com.smartlib.common.web.PageResponse;
import com.smartlib.fine.Fine;
import com.smartlib.fine.FineRepository;
import com.smartlib.fine.FineStatus;
import com.smartlib.fine.FineType;
import com.smartlib.library.LibraryRepository;
import com.smartlib.payment.PaymentDtos.PaymentView;
import com.smartlib.security.RateLimiter;
import com.smartlib.security.SmartLibPrincipal;
import com.smartlib.user.User;
import com.smartlib.user.UserRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Razorpay fine payments. A fine is marked PAID only after the server verifies Razorpay's HMAC signature (checkout
 * handler) or a signed webhook. The amount always comes from the database, never from the browser.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final Duration ORDER_REUSE = Duration.ofMinutes(25);

    private final PaymentRepository payments;
    private final FineRepository fines;
    private final UserRepository users;
    private final LibraryRepository libraries;
    private final RazorpayClient razorpay;
    private final NamedParameterJdbcTemplate jdbc;
    private final CatalogueQueryService catalogue;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate tx;
    private final ObjectMapper mapper;
    private final RateLimiter rateLimiter;
    private final AppClock clock;
    private final DemoPaymentGateway demo;

    public PaymentService(PaymentRepository payments, FineRepository fines, UserRepository users, LibraryRepository libraries,
                          RazorpayClient razorpay, NamedParameterJdbcTemplate jdbc, CatalogueQueryService catalogue,
                          AuditService audit, ApplicationEventPublisher events, TransactionTemplate tx, ObjectMapper mapper,
                          RateLimiter rateLimiter, AppClock clock, DemoPaymentGateway demo) {
        this.demo = demo;
        this.payments = payments;
        this.fines = fines;
        this.users = users;
        this.libraries = libraries;
        this.razorpay = razorpay;
        this.jdbc = jdbc;
        this.catalogue = catalogue;
        this.audit = audit;
        this.events = events;
        this.tx = tx;
        this.mapper = mapper;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
    }

    @Transactional
    public PaymentDtos.CheckoutOrder createOrder(SmartLibPrincipal member, long fineId) {
        rateLimiter.check("pay-order:" + member.userId(), 20, Duration.ofMinutes(10));
        boolean live = razorpay.configured();
        if (!live && !demo.enabled()) {
            throw new ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.PAYMENTS_UNAVAILABLE,
                    "Online payments are not configured yet. Please pay at the library desk.");
        }
        String provider = live ? "RAZORPAY" : DemoPaymentGateway.PROVIDER;
        Fine fine = fines.lockByIdAndMemberId(fineId, member.userId())
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND, "Fine not found."));
        if (fine.getStatus() != FineStatus.UNPAID) {
            throw ApiException.conflict(ErrorCodes.FINE_NOT_PAYABLE, "This fine is already " + fine.getStatus().name().toLowerCase() + ".");
        }
        User user = users.findById(member.userId()).orElseThrow();
        String libraryName = libraries.findById(fine.getLibraryId()).map(l -> l.getName()).orElse("SmartLib");
        Payment payment = payments.findFirstByFineIdAndStatusAndCreatedAtAfterOrderByIdDesc(fineId, PaymentStatus.CREATED,
                clock.now().minus(ORDER_REUSE))
                .filter(p -> p.getAmountPaise() == fine.getAmountPaise() && provider.equals(p.getProvider())).orElse(null);
        if (payment == null) {
            String orderId;
            if (live) {
                RazorpayClient.Order order = razorpay.createOrder(fine.getAmountPaise(), "INR", "fine_" + fine.getId() + "_" + System.currentTimeMillis(),
                        Map.of("fineId", String.valueOf(fine.getId()), "memberId", String.valueOf(member.userId()),
                                "libraryId", String.valueOf(fine.getLibraryId())));
                if (order.amount() != fine.getAmountPaise()) {
                    throw new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY, ErrorCodes.PAYMENT_PROVIDER_ERROR,
                            "The payment provider returned an unexpected amount.");
                }
                orderId = order.id();
            } else {
                orderId = demo.newOrderId();
            }
            payment = new Payment();
            payment.setProvider(provider);
            payment.setFineId(fine.getId());
            payment.setMemberId(member.userId());
            payment.setLibraryId(fine.getLibraryId());
            payment.setAmountPaise(fine.getAmountPaise());
            payment.setCurrency("INR");
            payment.setProviderOrderId(orderId);
            payment.setStatus(PaymentStatus.CREATED);
            payments.save(payment);
            audit.record(AuditActions.PAYMENT_ORDER_CREATED, "PAYMENT", payment.getId(), fine.getLibraryId(),
                    AuditService.details("fineId", fine.getId(), "amountPaise", fine.getAmountPaise(), "provider", provider));
        }
        return new PaymentDtos.CheckoutOrder(payment.getId(), payment.getProviderOrderId(), live ? razorpay.keyId() : null, payment.getAmountPaise(),
                payment.getCurrency(), "SmartLib", describe(fine, libraryName),
                new PaymentDtos.Prefill(user.getFullName(), user.getEmail(), user.getPhone()), fine.getId(), provider);
    }

    /**
     * The demo gateway's checkout: plays the provider's part. A success returns a server-signed result that the browser
     * must still send to {@link #verifyCheckout}; a decline marks the attempt FAILED and leaves the fine unpaid.
     */
    public PaymentDtos.DemoCheckoutResult demoCheckout(SmartLibPrincipal member, PaymentDtos.DemoCheckoutRequest req) {
        if (!demo.enabled() || razorpay.configured()) {
            throw ApiException.notFound(ErrorCodes.NOT_FOUND, "Demo payments are not enabled.");
        }
        rateLimiter.check("pay-demo:" + member.userId(), 30, Duration.ofMinutes(10));
        Payment payment = payments.findByProviderOrderId(req.orderId())
                .filter(p -> p.getMemberId().equals(member.userId()) && DemoPaymentGateway.PROVIDER.equals(p.getProvider()))
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND, "Payment not found."));
        if (payment.getStatus() != PaymentStatus.CREATED) {
            throw ApiException.conflict(ErrorCodes.FINE_NOT_PAYABLE, "This payment attempt is no longer open. Please start a new payment.");
        }
        if (!req.succeed()) {
            markFailed(req.orderId(), "Payment declined by the bank (demo).");
            return new PaymentDtos.DemoCheckoutResult("FAILED", req.orderId(), null, null, "Payment declined by the bank (demo). You have not been charged.");
        }
        String method = req.method().toLowerCase(java.util.Locale.ROOT);
        tx.executeWithoutResult(s -> payments.findById(payment.getId()).ifPresent(p -> {
            p.setMethod(method);
            payments.save(p);
        }));
        String paymentId = demo.newPaymentId();
        return new PaymentDtos.DemoCheckoutResult("AUTHORIZED", req.orderId(), paymentId, demo.sign(req.orderId(), paymentId), null);
    }

    /** Called with the fields returned to checkout.js. Never trusts the browser's notion of success. */
    public PaymentView verifyCheckout(SmartLibPrincipal member, PaymentDtos.VerifyRequest req) {
        rateLimiter.check("pay-verify:" + member.userId(), 30, Duration.ofMinutes(10));
        Payment payment = payments.findByProviderOrderId(req.razorpayOrderId())
                .filter(p -> p.getMemberId().equals(member.userId()))
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND, "Payment not found."));
        if (payment.getStatus() == PaymentStatus.PAID) {
            return view(payment.getId());
        }
        if (DemoPaymentGateway.PROVIDER.equals(payment.getProvider())) {
            // Same rule as Razorpay: only a signature the server itself issued proves the (demo) payment.
            if (!demo.enabled() || !demo.verify(req.razorpayOrderId(), req.razorpayPaymentId(), req.razorpaySignature())) {
                audit.recordIndependent(AuditService.Actor.of(member), AuditActions.PAYMENT_VERIFICATION_FAILED, "PAYMENT", payment.getId(),
                        payment.getLibraryId(), "FAILURE", AuditService.details("orderId", req.razorpayOrderId(), "provider", "DEMO"));
                throw ApiException.badRequest(ErrorCodes.PAYMENT_VERIFICATION_FAILED, "We could not verify this payment.");
            }
            if (payment.getStatus() != PaymentStatus.CREATED) {
                throw ApiException.badRequest(ErrorCodes.PAYMENT_VERIFICATION_FAILED, "This payment attempt is no longer open. Please start a new payment.");
            }
            markPaid(req.razorpayOrderId(), req.razorpayPaymentId(), payment.getMethod(), AuditService.Actor.of(member));
            return view(payment.getId());
        }
        if (!razorpay.verifyCheckoutSignature(req.razorpayOrderId(), req.razorpayPaymentId(), req.razorpaySignature())) {
            audit.recordIndependent(AuditService.Actor.of(member), AuditActions.PAYMENT_VERIFICATION_FAILED, "PAYMENT", payment.getId(),
                    payment.getLibraryId(), "FAILURE", AuditService.details("orderId", req.razorpayOrderId()));
            throw ApiException.badRequest(ErrorCodes.PAYMENT_VERIFICATION_FAILED,
                    "We could not verify this payment. If money was deducted, it will be reconciled automatically.");
        }
        String method = null;
        try {
            RazorpayClient.PaymentInfo info = razorpay.fetchPayment(req.razorpayPaymentId());
            if (!req.razorpayOrderId().equals(info.orderId()) || info.amount() != payment.getAmountPaise()) {
                audit.recordIndependent(AuditService.Actor.of(member), AuditActions.PAYMENT_VERIFICATION_FAILED, "PAYMENT", payment.getId(),
                        payment.getLibraryId(), "FAILURE", AuditService.details("reason", "order_or_amount_mismatch"));
                throw ApiException.badRequest(ErrorCodes.PAYMENT_VERIFICATION_FAILED, "Payment details did not match this fine.");
            }
            if ("authorized".equals(info.status())) {
                info = razorpay.capture(info.id(), payment.getAmountPaise(), payment.getCurrency());
            }
            if ("failed".equals(info.status())) {
                throw ApiException.badRequest(ErrorCodes.PAYMENT_VERIFICATION_FAILED, "The payment failed. You have not been charged.");
            }
            method = info.method();
        } catch (ApiException e) {
            if (ErrorCodes.PAYMENT_VERIFICATION_FAILED.equals(e.getCode())) {
                throw e;
            }
            // Provider unreachable after a valid signature: the signature alone proves Razorpay authorised this payment.
            log.warn("Razorpay fetch failed after valid signature for order {}: {}", req.razorpayOrderId(), e.getMessage());
        }
        markPaid(req.razorpayOrderId(), req.razorpayPaymentId(), method, AuditService.Actor.of(member));
        return view(payment.getId());
    }

    /** Signed server-to-server notification from Razorpay (covers a closed browser tab). */
    public void handleWebhook(String rawBody, String signature) {
        if (!razorpay.verifyWebhookSignature(rawBody, signature)) {
            throw ApiException.badRequest(ErrorCodes.PAYMENT_VERIFICATION_FAILED, "Invalid webhook signature.");
        }
        JsonNode root = mapper.readTree(rawBody);
        String event = root.path("event").asString("");
        JsonNode payment = root.path("payload").path("payment").path("entity");
        String orderId = payment.path("order_id").asString(null);
        String paymentId = payment.path("id").asString(null);
        if (orderId == null) {
            orderId = root.path("payload").path("order").path("entity").path("id").asString(null);
        }
        if (orderId == null) {
            return;
        }
        switch (event) {
            case "payment.captured", "order.paid" -> markPaid(orderId, paymentId, payment.path("method").asString(null), AuditService.Actor.system());
            case "payment.failed" -> markFailed(orderId, payment.path("error_description").asString("Payment failed"));
            default -> {
                // ignored event
            }
        }
    }

    /** Idempotent: a payment/fine already PAID is left untouched. */
    public void markPaid(String orderId, String providerPaymentId, String method, AuditService.Actor actor) {
        Long paymentId = tx.execute(s -> {
            Payment p = payments.lockByOrderId(orderId).orElse(null);
            if (p == null || p.getStatus() == PaymentStatus.PAID) {
                return null;
            }
            Fine fine = fines.lockById(p.getFineId()).orElseThrow();
            p.setStatus(PaymentStatus.PAID);
            p.setProviderPaymentId(providerPaymentId);
            p.setMethod(method);
            p.setVerifiedAt(clock.now());
            p.setFailureReason(null);
            payments.save(p);
            if (fine.getStatus() == FineStatus.UNPAID) {
                fine.setStatus(FineStatus.PAID);
                fine.setPaidAt(clock.now());
                fines.save(fine);
            }
            audit.recordAs(actor, AuditActions.PAYMENT_VERIFIED, "PAYMENT", p.getId(), p.getLibraryId(),
                    AuditService.details("fineId", fine.getId(), "amountPaise", p.getAmountPaise(), "providerPaymentId", providerPaymentId));
            events.publishEvent(new DomainEvents.PaymentSucceeded(p.getId()));
            return p.getId();
        });
        if (paymentId != null) {
            log.info("Payment {} verified and fine marked paid", paymentId);
        }
    }

    private void markFailed(String orderId, String reason) {
        tx.executeWithoutResult(s -> payments.lockByOrderId(orderId).filter(p -> p.getStatus() == PaymentStatus.CREATED).ifPresent(p -> {
            p.setStatus(PaymentStatus.FAILED);
            p.setFailureReason(reason == null ? null : reason.substring(0, Math.min(reason.length(), 500)));
            payments.save(p);
            audit.recordAs(AuditService.Actor.system(), AuditActions.PAYMENT_FAILED, "PAYMENT", p.getId(), p.getLibraryId(), null);
        }));
    }

    @Scheduled(fixedDelay = 600_000, initialDelay = 90_000)
    public void expireStaleOrders() {
        for (Long id : payments.findStale(clock.now().minus(Duration.ofMinutes(30)))) {
            tx.executeWithoutResult(s -> payments.findById(id).filter(p -> p.getStatus() == PaymentStatus.CREATED).ifPresent(p -> {
                p.setStatus(PaymentStatus.EXPIRED);
                payments.save(p);
            }));
        }
    }

    // ---------- read models ----------

    private static final String SELECT = """
            SELECT pa.*, f.type AS fine_type, f.borrowing_id, l.name AS lib_name, l.slug AS lib_slug, bo.book_id,
                   u.full_name, u.email, u.phone
            FROM payments pa
            JOIN fines f ON f.id = pa.fine_id
            JOIN libraries l ON l.id = pa.library_id
            JOIN users u ON u.id = pa.member_id
            LEFT JOIN borrowings bo ON bo.id = f.borrowing_id""";

    public PageResponse<PaymentView> memberPayments(long memberId, int page, int size) {
        return page(" WHERE pa.member_id = :id", new MapSqlParameterSource("id", memberId), page, size, false);
    }

    public PageResponse<PaymentView> libraryPayments(long libraryId, String status, int page, int size) {
        MapSqlParameterSource p = new MapSqlParameterSource("id", libraryId);
        String where = " WHERE pa.library_id = :id";
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            where += " AND pa.status = :status";
            p.addValue("status", PaymentStatus.valueOf(status.toUpperCase()).name());
        }
        return page(where, p, page, size, true);
    }

    public PaymentView view(long paymentId) {
        return page(" WHERE pa.id = :id", new MapSqlParameterSource("id", paymentId), 0, 1, false).content().get(0);
    }

    private PageResponse<PaymentView> page(String where, MapSqlParameterSource p, int page, int size, boolean staffView) {
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM payments pa" + where, p, Long.class);
        p.addValue("limit", size).addValue("offset", (long) page * size);
        List<Object[]> rows = jdbc.query(SELECT + where + " ORDER BY pa.created_at DESC, pa.id DESC LIMIT :limit OFFSET :offset", p,
                (rs, i) -> {
                    Timestamp verified = rs.getTimestamp("verified_at");
                    PaymentView v = new PaymentView(rs.getLong("id"), PaymentStatus.valueOf(rs.getString("status")), rs.getLong("amount_paise"),
                            rs.getString("currency"), rs.getString("provider_order_id"), rs.getString("provider_payment_id"),
                            rs.getString("method"), rs.getString("provider"), rs.getTimestamp("created_at").toInstant(), verified == null ? null : verified.toInstant(),
                            rs.getString("failure_reason"), rs.getLong("fine_id"), FineType.valueOf(rs.getString("fine_type")),
                            new LibraryRef(rs.getLong("library_id"), rs.getString("lib_name"), rs.getString("lib_slug")), null,
                            staffView ? new MemberRef(rs.getLong("member_id"), rs.getString("full_name"), rs.getString("email"), rs.getString("phone")) : null);
                    return new Object[]{v, rs.getObject("book_id", Long.class)};
                });
        Map<Long, BookSummary> books = catalogue.summaries(rows.stream().map(r -> (Long) r[1]).filter(java.util.Objects::nonNull).toList());
        List<PaymentView> out = new ArrayList<>();
        for (Object[] r : rows) {
            PaymentView v = (PaymentView) r[0];
            out.add(new PaymentView(v.id(), v.status(), v.amountPaise(), v.currency(), v.providerOrderId(), v.providerPaymentId(), v.method(),
                    v.provider(), v.createdAt(), v.verifiedAt(), v.failureReason(), v.fineId(), v.fineType(), v.library(),
                    r[1] == null ? null : books.get((Long) r[1]), v.member()));
        }
        return PageResponse.of(out, page, size, total == null ? 0 : total);
    }

    private static String describe(Fine fine, String libraryName) {
        return switch (fine.getType()) {
            case OVERDUE -> "Overdue fine · " + libraryName;
            case LOST -> "Lost book charge · " + libraryName;
            case DAMAGED -> "Damage charge · " + libraryName;
            case MANUAL -> "Library fine · " + libraryName;
        };
    }

    public Instant now() {
        return clock.now();
    }
}
