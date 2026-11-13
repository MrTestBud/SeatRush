package com.seatrush.booking;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Releases seats whose hold ran out. Each booking is expired in its own short transaction, so one
 * bad row cannot block the rest and row locks are held for milliseconds.
 * (Seat availability does not wait for this job: a lapsed hold is already bookable by someone else.)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExpirySweeper {
    private static final int BATCH = 200;

    private final BookingService bookings;
    private final io.micrometer.core.instrument.MeterRegistry metrics;

    @Scheduled(fixedDelayString = "${app.booking.sweep-interval-ms:5000}")
    public int sweep() {
        int expired = 0;
        for (Long id : bookings.findExpiredIds(BATCH)) {
            try {
                if (bookings.expire(id)) expired++;
            } catch (RuntimeException e) {
                log.warn("Could not expire booking {}: {}", id, e.getMessage());
            }
        }
        if (expired > 0) metrics.counter("seatrush.bookings.expired").increment(expired);
        if (expired > 0) log.info("Expired {} unpaid booking(s) and released their seats", expired);
        return expired;
    }
}
