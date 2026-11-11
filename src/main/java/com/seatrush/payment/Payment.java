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
}
