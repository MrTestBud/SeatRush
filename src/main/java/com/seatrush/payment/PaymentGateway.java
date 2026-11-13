package com.seatrush.payment;

import java.math.BigDecimal;

/** What the booking flow needs from a payment provider. Swapping providers is one bean, not a rewrite. */
public interface PaymentGateway {
    /** Stored on the payment row and returned to the frontend so it knows which checkout to open. */
    String name();

    /** Opens an order with the provider and returns the provider's order id. */
    String createOrder(Long bookingId, BigDecimal amount);

    /** Sends money back for a captured payment and returns the provider's refund id. Throws if the provider refuses. */
    String refund(String providerPaymentId, BigDecimal amount);

    /** Publishable key for the browser checkout; null for the mock provider. */
    String publicKey();
}
