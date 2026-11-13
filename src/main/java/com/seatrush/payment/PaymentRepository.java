package com.seatrush.payment;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    /** Row lock serialises duplicate webhook deliveries for the same order. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.providerOrderId = :orderId")
    Optional<Payment> lockByOrderId(@Param("orderId") String orderId);

    Optional<Payment> findByProviderOrderId(String providerOrderId);

    List<Payment> findByBookingIdOrderByIdDesc(Long bookingId);

    List<Payment> findByBookingIdInAndRefundAmountIsNotNull(Collection<Long> bookingIds);

    /**
     * Claims one payment that is waiting for a refund. SKIP LOCKED: two app instances never pick
     * the same payment, so a refund cannot be sent twice. A refund that just failed is left alone
     * for a minute before the next attempt.
     */
    @Query(value = """
            SELECT * FROM payments WHERE status = 'REFUND_PENDING'
              AND (refund_attempts = 0 OR updated_at < now() - interval '60 seconds')
            ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    Optional<Payment> claimRefundPending();
}
