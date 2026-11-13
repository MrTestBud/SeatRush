package com.seatrush.booking;

import com.seatrush.booking.BookingDtos.CancellationQuote;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;

/**
 * Cancelling a paid booking: full refund up to 24 hours before the show, half up to 2 hours before,
 * and no cancellation after that (the seat can no longer realistically be resold). All three numbers are configurable.
 */
@Component
public class RefundPolicy {
    private final long fullRefundHours;
    private final long partialRefundHours;
    private final int partialPercent;

    public RefundPolicy(@Value("${app.booking.refund.full-hours:24}") long fullRefundHours,
                        @Value("${app.booking.refund.partial-hours:2}") long partialRefundHours,
                        @Value("${app.booking.refund.partial-percent:50}") int partialPercent) {
        this.fullRefundHours = fullRefundHours;
        this.partialRefundHours = partialRefundHours;
        this.partialPercent = partialPercent;
    }

    public CancellationQuote quote(Instant showStartsAt, BigDecimal paid, boolean checkedIn, Instant now) {
        if (checkedIn) return refused("This ticket has already been used.");
        Duration left = Duration.between(now, showStartsAt);
        if (left.compareTo(Duration.ofHours(fullRefundHours)) >= 0)
            return new CancellationQuote(true, 100, paid,
                    "Full refund: the show is more than " + fullRefundHours + " hours away.");
        if (left.compareTo(Duration.ofHours(partialRefundHours)) >= 0)
            return new CancellationQuote(true, partialPercent,
                    paid.multiply(BigDecimal.valueOf(partialPercent)).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP),
                    partialPercent + "% refund: the show is less than " + fullRefundHours + " hours away.");
        return refused("Bookings cannot be cancelled in the last " + partialRefundHours + " hours before the show.");
    }

    private static CancellationQuote refused(String why) {
        return new CancellationQuote(false, 0, BigDecimal.ZERO, why);
    }
}
