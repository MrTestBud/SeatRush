package com.seatrush.booking;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    /** Every state change takes this row lock first, so the sweeper, a cancel and a webhook can never interleave. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Booking b WHERE b.id = :id")
    Optional<Booking> lockById(@Param("id") Long id);

    Optional<Booking> findByIdempotencyKey(String idempotencyKey);

    Page<Booking> findByUserIdOrderByIdDesc(Long userId, Pageable pageable);

    @Query("SELECT b.id FROM Booking b WHERE b.status IN :statuses AND b.expiresAt < :now ORDER BY b.expiresAt")
    List<Long> findExpiredIds(@Param("statuses") Collection<BookingStatus> statuses, @Param("now") Instant now,
                              Pageable pageable);
}
