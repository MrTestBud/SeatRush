package com.seatrush.booking;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "bookings")
@Getter @NoArgsConstructor
public class Booking {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id")
    private Long userId;
    @Column(name = "show_id")
    private Long showId;
    @Enumerated(EnumType.STRING)
    private BookingStatus status;
    /** What the user pays: seat prices minus discount. */
    @Column(name = "total_amount")
    private BigDecimal totalAmount;
    @Column(name = "discount_amount")
    private BigDecimal discountAmount = BigDecimal.ZERO;
    @Column(name = "coupon_id")
    private Long couponId;
    @Column(name = "coupon_code")
    private String couponCode;
    @Column(name = "idempotency_key")
    private String idempotencyKey;
    @Column(name = "expires_at")
    private Instant expiresAt;
    @Column(name = "checked_in_at")
    private Instant checkedInAt;
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    public static Booking held(Long userId, Long showId, BigDecimal total, String idempotencyKey, Instant expiresAt) {
        Booking b = new Booking();
        b.userId = userId;
        b.showId = showId;
        b.status = BookingStatus.HELD;
        b.totalAmount = total;
        b.idempotencyKey = idempotencyKey;
        b.expiresAt = expiresAt;
        return b;
    }

    /** The only way to change status: illegal jumps fail loudly instead of corrupting state. */
    public void transitionTo(BookingStatus target) {
        if (status == target) return;
        if (!status.canTransitionTo(target))
            throw new IllegalStateException("Illegal booking transition " + status + " -> " + target);
        status = target;
    }

    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public boolean isExpiredAt(Instant now) { return expiresAt != null && !expiresAt.isAfter(now); }

    /** Sum of the seat prices, before any discount. */
    public BigDecimal subtotal() { return totalAmount.add(discountAmount); }

    public void applyCoupon(Long couponId, String code, BigDecimal discount) {
        BigDecimal subtotal = subtotal();
        this.couponId = couponId;
        this.couponCode = code;
        this.discountAmount = discount;
        this.totalAmount = subtotal.subtract(discount);
    }

    public void removeCoupon() {
        this.totalAmount = subtotal();
        this.discountAmount = BigDecimal.ZERO;
        this.couponId = null;
        this.couponCode = null;
    }

    public void checkIn(Instant at) { this.checkedInAt = at; }
}
