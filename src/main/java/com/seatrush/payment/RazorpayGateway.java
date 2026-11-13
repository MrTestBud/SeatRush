package com.seatrush.payment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Razorpay Orders API (https://razorpay.com/docs/api/orders/). Enabled with PAYMENT_PROVIDER=razorpay
 * plus RAZORPAY_KEY_ID / RAZORPAY_KEY_SECRET. Confirmation never trusts the browser: it only
 * happens when Razorpay calls our signed webhook.
 */
@Component
@ConditionalOnProperty(name = "app.payment.provider", havingValue = "razorpay")
public class RazorpayGateway implements PaymentGateway {
    private final RestClient http;
    private final String keyId;

    public RazorpayGateway(@Value("${app.payment.razorpay.key-id}") String keyId,
                           @Value("${app.payment.razorpay.key-secret}") String keySecret) {
        if (keyId.isBlank() || keySecret.isBlank())
            throw new IllegalStateException("PAYMENT_PROVIDER=razorpay needs RAZORPAY_KEY_ID and RAZORPAY_KEY_SECRET");
        this.keyId = keyId;
        this.http = RestClient.builder()
                .baseUrl("https://api.razorpay.com/v1")
                .defaultHeaders(h -> h.setBasicAuth(keyId, keySecret))
                .build();
    }

    @Override public String name() { return "RAZORPAY"; }

    @Override
    public String createOrder(Long bookingId, BigDecimal amount) {
        Map<?, ?> order = http.post().uri("/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "amount", amount.movePointRight(2).longValueExact(),   // Razorpay wants paise
                        "currency", "INR",
                        "receipt", "booking_" + bookingId))
                .retrieve()
                .body(Map.class);
        if (order == null || order.get("id") == null)
            throw new IllegalStateException("Razorpay did not return an order id");
        return order.get("id").toString();
    }

    /** Refunds API: POST /payments/{id}/refund with the amount in paise (https://razorpay.com/docs/api/refunds/). */
    @Override
    public String refund(String providerPaymentId, BigDecimal amount) {
        Map<?, ?> refund = http.post().uri("/payments/{id}/refund", providerPaymentId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("amount", amount.movePointRight(2).longValueExact()))
                .retrieve()
                .body(Map.class);
        if (refund == null || refund.get("id") == null)
            throw new IllegalStateException("Razorpay did not return a refund id");
        return refund.get("id").toString();
    }

    @Override public String publicKey() { return keyId; }
}
