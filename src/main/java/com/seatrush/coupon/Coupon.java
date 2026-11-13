package com.seatrush.coupon;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "coupons")
@Getter @NoArgsConstructor
public class Coupon {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "event_id")
    private Long eventId;
    private String code;
    @Column(name = "percent_off")
    private int percentOff;
    @Column(name = "max_uses")
    private int maxUses;
    @Column(name = "used_count", insertable = false, updatable = false)   // only ever changed by the atomic UPDATEs in CouponRepository
    private int usedCount;
    @Column(name = "expires_at")
    private Instant expiresAt;
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    public Coupon(Long eventId, String code, int percentOff, int maxUses, Instant expiresAt) {
        this.eventId = eventId;
        this.code = code;
        this.percentOff = percentOff;
        this.maxUses = maxUses;
        this.expiresAt = expiresAt;
    }

    public boolean isExpiredAt(Instant now) { return expiresAt != null && !expiresAt.isAfter(now); }
}
