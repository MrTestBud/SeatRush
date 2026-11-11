package com.seatrush.payment;

/**
 * CREATED: order opened with the provider, waiting for the webhook.
 * CAPTURED: money taken and the booking was confirmed.
 * FAILED: provider reported failure; the user may retry with a new order.
 * REFUND_PENDING: money was taken but the seats could not be given (hold expired and the seats
 * were re-sold, or the booking was cancelled), so the money must go back.
 */
public enum PaymentStatus { CREATED, CAPTURED, FAILED, REFUND_PENDING }
