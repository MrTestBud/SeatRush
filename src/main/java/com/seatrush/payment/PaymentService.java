package com.seatrush.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatrush.booking.Booking;
import com.seatrush.booking.BookingService;
import com.seatrush.booking.BookingService.PaymentOutcome;
import com.seatrush.common.BadRequestException;
import com.seatrush.common.NotFoundException;
import com.seatrush.payment.PaymentDtos.PaymentOrderView;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class PaymentService {
    private final BookingService bookings;
    private final PaymentRepository payments;
    private final PaymentGateway gateway;
    private final ObjectMapper om;
    private final String webhookSecret;
    private final io.micrometer.core.instrument.MeterRegistry metrics;

    public PaymentService(BookingService bookings, PaymentRepository payments, PaymentGateway gateway, ObjectMapper om,
                          io.micrometer.core.instrument.MeterRegistry metrics,
                          @Value("${app.payment.webhook-secret}") String webhookSecret) {
        this.metrics = metrics;
        this.bookings = bookings;
        this.payments = payments;
        this.gateway = gateway;
        this.om = om;
        this.webhookSecret = webhookSecret;
    }

    /** Opens (or re-uses) a provider order for a held booking. Calling it twice does not create two orders. */
    public PaymentOrderView startPayment(Long userId, Long bookingId) {
        Booking b = bookings.payable(userId, bookingId);
        Payment p = bookings.openPayment(bookingId);
        if (p == null) {
            // The provider HTTP call happens outside any DB transaction; the booking is re-validated under lock after.
            String orderId = gateway.createOrder(b.getId(), b.getTotalAmount());
            p = bookings.attachPayment(userId, bookingId, gateway.name(), orderId, b.getTotalAmount());
        }
        return new PaymentOrderView(p.getProvider(), p.getProviderOrderId(), p.getAmount(),
                p.getAmount().movePointRight(2).longValueExact(), "INR", gateway.publicKey());
    }

    /**
     * Webhook entry point. The body is only trusted after its HMAC-SHA256 signature checks out
     * against the shared webhook secret (same scheme as Razorpay's X-Razorpay-Signature).
     */
    public PaymentOutcome handleWebhook(String rawBody, String signature) {
        if (signature == null || !MessageDigest.isEqual(
                sign(rawBody).getBytes(StandardCharsets.UTF_8), signature.trim().getBytes(StandardCharsets.UTF_8)))
            throw new AccessDeniedException("Invalid webhook signature");

        JsonNode root;
        try {
            root = om.readTree(rawBody);
        } catch (Exception e) {
            throw new BadRequestException("Webhook body is not valid JSON");
        }
        String event = root.path("event").asText("");
        JsonNode payment = root.path("payload").path("payment").path("entity");
        String orderId = payment.path("order_id").asText(null);
        String paymentId = payment.path("id").asText(null);

        boolean success;
        switch (event) {
            case "payment.captured" -> success = true;
            case "payment.failed" -> success = false;
            default -> {
                log.debug("Ignoring webhook event {}", event);
                return PaymentOutcome.UNKNOWN_ORDER;
            }
        }
        if (orderId == null || paymentId == null) throw new BadRequestException("Webhook is missing payment ids");

        PaymentOutcome outcome = bookings.applyPaymentResult(orderId, paymentId, success);
        metrics.counter("seatrush.payments", "outcome", outcome.name().toLowerCase()).increment();
        log.info("Webhook {} for order {} -> {}", event, orderId, outcome);
        return outcome;
    }

    /** Mock provider only: plays the provider's part by sending ourselves a correctly signed webhook. */
    public PaymentOutcome completeMock(Long userId, String orderId, boolean success) {
        if (!"MOCK".equals(gateway.name())) throw new NotFoundException("Mock payments are disabled");
        Payment p = payments.findByProviderOrderId(orderId)
                .orElseThrow(() -> new NotFoundException("Order " + orderId + " not found"));
        bookings.get(userId, p.getBookingId());   // throws 404 unless the caller owns the booking

        String body;
        try {
            body = om.writeValueAsString(Map.of(
                    "event", success ? "payment.captured" : "payment.failed",
                    "payload", Map.of("payment", Map.of("entity", Map.of(
                            "id", "pay_mock_" + UUID.randomUUID().toString().replace("-", ""),
                            "order_id", orderId,
                            "status", success ? "captured" : "failed")))));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return handleWebhook(body, sign(body));
    }

    public String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot compute webhook signature", e);
        }
    }
}
