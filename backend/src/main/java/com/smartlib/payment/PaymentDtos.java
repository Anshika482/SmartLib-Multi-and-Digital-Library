package com.smartlib.payment;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.smartlib.catalogue.CatalogueDtos.BookSummary;
import com.smartlib.circulation.CirculationDtos.LibraryRef;
import com.smartlib.circulation.CirculationDtos.MemberRef;
import com.smartlib.fine.FineType;

public final class PaymentDtos {
    private PaymentDtos() {
    }

    public record Prefill(String name, String email, String contact) {
    }

    /** Everything the checkout needs. Only the public key id is exposed (null for the demo gateway). provider: RAZORPAY | DEMO. */
    public record CheckoutOrder(long paymentId, String orderId, String keyId, long amountPaise, String currency, String name,
                                String description, Prefill prefill, long fineId, String provider) {
    }

    /** The demo gateway's checkout step: the method the member picked and whether to simulate success or a decline. */
    public record DemoCheckoutRequest(@NotBlank @Size(max = 64) String orderId,
                                      @NotBlank @jakarta.validation.constraints.Pattern(regexp = "UPI|CARD|NETBANKING") String method,
                                      boolean succeed) {
    }

    /** AUTHORIZED carries a server-signed result for /verify, like Razorpay's checkout handler; FAILED carries a message. */
    public record DemoCheckoutResult(String status, String orderId, String paymentId, String signature, String message) {
    }

    public record VerifyRequest(@NotBlank @Size(max = 64) String razorpayOrderId,
                                @NotBlank @Size(max = 64) String razorpayPaymentId,
                                @NotBlank @Size(max = 256) String razorpaySignature) {
    }

    public record PaymentView(long id, PaymentStatus status, long amountPaise, String currency, String providerOrderId,
                              String providerPaymentId, String method, String provider, Instant createdAt, Instant verifiedAt, String failureReason,
                              long fineId, FineType fineType, LibraryRef library, BookSummary book, MemberRef member) {
    }
}
