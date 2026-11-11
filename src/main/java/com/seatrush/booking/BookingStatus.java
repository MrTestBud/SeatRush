package com.seatrush.booking;

import java.util.EnumSet;
import java.util.Set;

/**
 * Booking state machine.
 * <pre>
 * HELD ──► PAYMENT_PENDING ──► CONFIRMED
 *   │            │
 *   ├────────────┴──► EXPIRED ──► CONFIRMED   (late payment, only if the seats are still free)
 *   └────────────┴──► CANCELLED
 * </pre>
 * Every status change goes through {@link Booking#transitionTo}, so an illegal jump is a bug that fails loudly.
 */
public enum BookingStatus {
    HELD, PAYMENT_PENDING, CONFIRMED, EXPIRED, CANCELLED;

    private Set<BookingStatus> next() {
        return switch (this) {
            case HELD -> EnumSet.of(PAYMENT_PENDING, CONFIRMED, EXPIRED, CANCELLED);
            case PAYMENT_PENDING -> EnumSet.of(CONFIRMED, EXPIRED, CANCELLED);
            case EXPIRED -> EnumSet.of(CONFIRMED);
            case CONFIRMED, CANCELLED -> EnumSet.noneOf(BookingStatus.class);
        };
    }

    public boolean canTransitionTo(BookingStatus target) { return next().contains(target); }

    /** Still holding seats and waiting on the user. */
    public boolean isActive() { return this == HELD || this == PAYMENT_PENDING; }
}
