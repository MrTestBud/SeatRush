package com.seatrush.booking;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

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

    public BookingItem(Long bookingId, Long showSeatId) {
        this.bookingId = bookingId;
        this.showSeatId = showSeatId;
    }
}
