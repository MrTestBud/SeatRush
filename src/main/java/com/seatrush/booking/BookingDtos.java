package com.seatrush.booking;

import com.seatrush.venue.SeatTier;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public class BookingDtos {
    public record HoldRequest(@NotEmpty @Size(max = 20) List<Long> showSeatIds) {}

    /** One seat of a booking (bookingId lets a single query serve a whole page of bookings). */
    public record SeatLine(Long bookingId, Long showSeatId, String row, Integer number, SeatTier tier, BigDecimal price) {}

    public record BookedSeat(Long showSeatId, String row, Integer number, SeatTier tier, BigDecimal price) {
        static BookedSeat from(SeatLine l) {
            return new BookedSeat(l.showSeatId(), l.row(), l.number(), l.tier(), l.price());
        }
    }

    public record BookingView(Long id, Long showId, Long eventId, String eventTitle, String venueName, String venueCity,
                              Instant startsAt, BookingStatus status, BigDecimal totalAmount, Instant expiresAt,
                              Instant createdAt, List<BookedSeat> seats) {}
}
