package com.seatrush.payment;

import com.seatrush.booking.BookingRepository;
import com.seatrush.notification.OutboxMessage;
import com.seatrush.notification.OutboxRepository;
import com.seatrush.user.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues the refunds the booking flow has flagged (REFUND_PENDING): late payments that could not
 * be honoured and paid bookings the user cancelled.
 * <p>
 * Each refund is one transaction that holds the payment row lock while it calls the provider.
 * That is deliberate here: it is a background job, one row at a time, and the lock is what
 * guarantees a refund is never sent twice by two instances. A refused refund is retried on later
 * runs and parked as REFUND_FAILED after {@value #MAX_ATTEMPTS} attempts.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefundProcessor {
    static final int MAX_ATTEMPTS = 5;

    private final PaymentRepository payments;
    private final PaymentGateway gateway;
    private final BookingRepository bookings;
    private final UserRepository users;
    private final OutboxRepository outbox;
    private final MeterRegistry metrics;

    /** @return false when nothing is waiting. */
    @Transactional
    public boolean refundOne() {
        Payment p = payments.claimRefundPending().orElse(null);
        if (p == null) return false;

        if (p.getProviderPaymentId() == null || p.getRefundAmount() == null || p.getRefundAmount().signum() <= 0) {
            p.mark(PaymentStatus.REFUNDED);     // nothing was captured, or nothing is owed: nothing to send back
            return true;
        }
        try {
            p.setProviderRefundId(gateway.refund(p.getProviderPaymentId(), p.getRefundAmount()));
            p.mark(PaymentStatus.REFUNDED);
            metrics.counter("seatrush.refunds", "outcome", "refunded").increment();
            bookings.findById(p.getBookingId()).flatMap(b -> users.findById(b.getUserId())).ifPresent(u ->
                    outbox.save(new OutboxMessage(u.getEmail(), "Your SeatRush refund has been issued",
                            "Rs. " + p.getRefundAmount() + " for booking #" + p.getBookingId()
                                    + " is on its way back to your original payment method.")));
        } catch (RuntimeException e) {
            p.setRefundAttempts(p.getRefundAttempts() + 1);
            p.setUpdatedAt(java.time.Instant.now());   // starts the one-minute back-off before the next attempt
            if (p.getRefundAttempts() >= MAX_ATTEMPTS) {
                p.mark(PaymentStatus.REFUND_FAILED);
                metrics.counter("seatrush.refunds", "outcome", "failed").increment();
                log.error("Refund for payment {} (booking {}) failed {} times and needs manual attention: {}",
                        p.getId(), p.getBookingId(), MAX_ATTEMPTS, e.getMessage());
            } else {
                log.warn("Refund for payment {} failed (attempt {}), will retry: {}", p.getId(), p.getRefundAttempts(), e.getMessage());
            }
        }
        return true;
    }
}
