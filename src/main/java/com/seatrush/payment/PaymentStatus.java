package com.seatrush.payment;

/**
 * CREATED: order opened with the provider, waiting for the webhook.
 * CAPTURED: money taken and the booking was confirmed.
 * FAILED: provider reported failure; the user may retry with a new order.
 * REFUND_PENDING: money has to go back (the hold expired and the seats were re-sold, the booking was
 * cancelled, or the user cancelled a paid booking). The refund worker picks these up.
 * REFUNDED: the provider accepted the refund.
 * REFUND_FAILED: the provider kept refusing; needs a human.
 */
public enum PaymentStatus { CREATED, CAPTURED, FAILED, REFUND_PENDING, REFUNDED, REFUND_FAILED }
