package com.seatrush.payment;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "payments")
@Getter @Setter @NoArgsConstructor
public class Payment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "booking_id")
    private Long bookingId;
    private String provider;
    @Column(name = "provider_order_id")
    private String providerOrderId;
    @Column(name = "provider_payment_id")
    private String providerPaymentId;
    @Enumerated(EnumType.STRING)
    private PaymentStatus status;
    private BigDecimal amount;
    /** How much goes back to the user; may be less than amount (partial refund on a late cancellation). */
    @Column(name = "refund_amount")
    private BigDecimal refundAmount;
    @Column(name = "provider_refund_id")
    private String providerRefundId;
    @Column(name = "refund_attempts")
    private int refundAttempts;
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();

    public Payment(Long bookingId, String provider, String providerOrderId, BigDecimal amount) {
        this.bookingId = bookingId;
        this.provider = provider;
        this.providerOrderId = providerOrderId;
        this.amount = amount;
        this.status = PaymentStatus.CREATED;
    }

    public void mark(PaymentStatus status) {
        this.status = status;
        this.updatedAt = Instant.now();
    }

    public void requestRefund(BigDecimal amount) {
        this.refundAmount = amount;
        mark(PaymentStatus.REFUND_PENDING);
    }
}
