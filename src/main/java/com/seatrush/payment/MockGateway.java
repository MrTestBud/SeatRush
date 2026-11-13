package com.seatrush.payment;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Default provider: no external account needed. "Paying" is simulated by
 * POST /api/payments/mock/{orderId}/complete, which builds a webhook, signs it with the webhook
 * secret and pushes it through the exact same code path a real Razorpay webhook takes.
 */
@Component
@ConditionalOnProperty(name = "app.payment.provider", havingValue = "mock", matchIfMissing = true)
public class MockGateway implements PaymentGateway {
    @Override public String name() { return "MOCK"; }

    @Override public String createOrder(Long bookingId, BigDecimal amount) {
        return "order_mock_" + UUID.randomUUID().toString().replace("-", "");
    }

    @Override public String refund(String providerPaymentId, BigDecimal amount) {
        return "rfnd_mock_" + UUID.randomUUID().toString().replace("-", "");
    }

    @Override public String publicKey() { return null; }
}
