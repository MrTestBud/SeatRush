package com.seatrush.booking;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Entity
@Table(name = "booking_items")
@Getter @NoArgsConstructor
public class BookingItem {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "booking_id")
    private Long bookingId;
    @Column(name = "show_seat_id")
    private Long showSeatId;
    /** The price this seat was taken at. The seat's list price may change later; this never does. */
    private BigDecimal price;

    public BookingItem(Long bookingId, Long showSeatId, BigDecimal price) {
        this.bookingId = bookingId;
        this.showSeatId = showSeatId;
        this.price = price;
    }
}
