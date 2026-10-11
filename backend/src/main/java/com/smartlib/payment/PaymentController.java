package com.smartlib.payment;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.smartlib.common.web.PageResponse;
import com.smartlib.fine.FineDtos;
import com.smartlib.fine.FineService;
import com.smartlib.payment.PaymentDtos.PaymentView;
import com.smartlib.security.CurrentUser;
import com.smartlib.security.LibraryScope;

@RestController
public class PaymentController {

    private final PaymentService payments;
    private final FineService fines;

    public PaymentController(PaymentService payments, FineService fines) {
        this.payments = payments;
        this.fines = fines;
    }

    @GetMapping("/api/me/fines")
    public java.util.List<FineDtos.FineView> myFines(@RequestParam(required = false) String status) {
        return fines.memberFines(CurrentUser.requireMember().userId(), status);
    }

    @GetMapping("/api/me/fines/summary")
    public FineDtos.FineSummary myFineSummary() {
        return fines.memberSummary(CurrentUser.requireMember().userId());
    }

    @PostMapping("/api/me/fines/{fineId}/payment-order")
    public PaymentDtos.CheckoutOrder createOrder(@PathVariable long fineId) {
        return payments.createOrder(CurrentUser.requireMember(), fineId);
    }

    @GetMapping("/api/me/payments")
    public PageResponse<PaymentView> myPayments(@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return payments.memberPayments(CurrentUser.requireMember().userId(), PageResponse.clampPage(page), PageResponse.clampSize(size, 20, 100));
    }

    @PostMapping("/api/payments/razorpay/verify")
    public PaymentView verify(@Valid @RequestBody PaymentDtos.VerifyRequest req) {
        return payments.verifyCheckout(CurrentUser.requireMember(), req);
    }

    /** Built-in demo gateway (only while PAYMENTS_DEMO_MODE=true and no Razorpay keys are set). */
    @PostMapping("/api/payments/demo/checkout")
    public PaymentDtos.DemoCheckoutResult demoCheckout(@Valid @RequestBody PaymentDtos.DemoCheckoutRequest req) {
        return payments.demoCheckout(CurrentUser.requireMember(), req);
    }

    @PostMapping("/api/webhooks/razorpay")
    public ResponseEntity<Void> webhook(@RequestBody String rawBody,
                                        @RequestHeader(name = "X-Razorpay-Signature", required = false) String signature) {
        payments.handleWebhook(rawBody, signature);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/api/manage/libraries/{libraryId}/payments")
    public PageResponse<PaymentView> libraryPayments(LibraryScope scope, @RequestParam(required = false) String status,
                                                     @RequestParam(required = false) Integer page,
                                                     @RequestParam(required = false) Integer size) {
        return payments.libraryPayments(scope.libraryId(), status, PageResponse.clampPage(page), PageResponse.clampSize(size, 20, 100));
    }
}
