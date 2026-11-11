package com.seatrush.payment;

import java.math.BigDecimal;

public class PaymentDtos {
    /** What the browser needs to open a checkout. amountMinor is in paise; keyId is null for the mock provider. */
    public record PaymentOrderView(String provider, String orderId, BigDecimal amount, long amountMinor,
                                   String currency, String keyId) {}

    public record WebhookAck(String outcome) {}
}
