package com.seatrush.coupon;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CouponRepository extends JpaRepository<Coupon, Long> {

    Optional<Coupon> findByEventIdAndCode(Long eventId, String code);

    List<Coupon> findByEventIdOrderByIdDesc(Long eventId);

    /**
     * Takes one use of the coupon, atomically. Returns 0 if it is already fully redeemed, so two
     * people racing for the last use cannot both get it (same idea as the seat race, in one UPDATE).
     */
    @Modifying
    @Query(value = "UPDATE coupons SET used_count = used_count + 1 WHERE id = :id AND used_count < max_uses", nativeQuery = true)
    int redeem(@Param("id") Long id);

    /** Gives a use back when the booking it was applied to is abandoned. */
    @Modifying
    @Query(value = "UPDATE coupons SET used_count = used_count - 1 WHERE id = :id AND used_count > 0", nativeQuery = true)
    int release(@Param("id") Long id);
}
