package com.seatrush.payment;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    /** Row lock serialises duplicate webhook deliveries for the same order. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.providerOrderId = :orderId")
    Optional<Payment> lockByOrderId(@Param("orderId") String orderId);

    Optional<Payment> findByProviderOrderId(String providerOrderId);

    List<Payment> findByBookingIdOrderByIdDesc(Long bookingId);
}
