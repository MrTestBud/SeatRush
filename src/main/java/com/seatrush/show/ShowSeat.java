package com.seatrush.show;

import com.seatrush.venue.Seat;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Per-show seat inventory: the row two users fight over.
 * The @Version column powers the optimistic locking strategy; held_by / held_until / booking_id
 * record who owns a hold and until when.
 */
@Entity
@Table(name = "show_seats")
@Getter @Setter @NoArgsConstructor
public class ShowSeat {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "show_id")
    private Long showId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seat_id")
    private Seat seat;
    @Enumerated(EnumType.STRING)
    private SeatStatus status;
    private BigDecimal price;
    @Column(name = "held_by")
    private Long heldBy;
    @Column(name = "held_until")
    private Instant heldUntil;
    @Column(name = "booking_id")
    private Long bookingId;
    @Version
    private Long version;

    /** Free, or held by someone whose hold has lapsed but the sweeper has not got to yet. */
    public boolean isFreeAt(Instant now) {
        return status == SeatStatus.AVAILABLE
                || (status == SeatStatus.HELD && heldUntil != null && !heldUntil.isAfter(now));
    }

    public void hold(Long userId, Long bookingId, Instant until) {
        this.status = SeatStatus.HELD;
        this.heldBy = userId;
        this.bookingId = bookingId;
        this.heldUntil = until;
    }

    public void book(Long userId, Long bookingId) {
        this.status = SeatStatus.BOOKED;
        this.heldBy = userId;
        this.bookingId = bookingId;
        this.heldUntil = null;
    }

    public void release() {
        this.status = SeatStatus.AVAILABLE;
        this.heldBy = null;
        this.bookingId = null;
        this.heldUntil = null;
    }
}
