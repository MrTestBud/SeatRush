package com.seatrush.payment;

import com.seatrush.config.AuthUser;
import com.seatrush.payment.PaymentDtos.PaymentOrderView;
import com.seatrush.payment.PaymentDtos.WebhookAck;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class PaymentController {
    private final PaymentService payments;

    @PostMapping("/bookings/{bookingId}/pay")
    public PaymentOrderView pay(@AuthenticationPrincipal AuthUser me, @PathVariable Long bookingId) {
        return payments.startPayment(me.id(), bookingId);
    }

    /**
     * Called by the payment provider, not by a logged-in user: it is open in SecurityConfig and
     * authenticated by the HMAC signature instead. Always answers 200 for a valid signature so the
     * provider stops retrying; duplicates are absorbed by the idempotent handler.
     */
    @PostMapping("/payments/webhook")
    public WebhookAck webhook(@RequestBody String rawBody,
                              @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature) {
        return new WebhookAck(payments.handleWebhook(rawBody, signature).name());
    }

    /** Development stand-in for a checkout page (mock provider only). */
    @PostMapping("/payments/mock/{orderId}/complete")
    public WebhookAck completeMock(@AuthenticationPrincipal AuthUser me, @PathVariable String orderId,
                                   @RequestParam(defaultValue = "success") String outcome) {
        return new WebhookAck(payments.completeMock(me.id(), orderId, !"failure".equalsIgnoreCase(outcome)).name());
    }
}
