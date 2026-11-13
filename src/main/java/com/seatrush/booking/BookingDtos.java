package com.seatrush.booking;

import com.seatrush.venue.SeatTier;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public class BookingDtos {
    public record HoldRequest(@NotEmpty @Size(max = 20) List<Long> showSeatIds) {}

    public record CouponRequest(@NotBlank @Size(max = 40) String code) {}

    public record CheckInRequest(@NotBlank @Size(max = 100) String code) {}

    /** One seat of a booking (bookingId lets a single query serve a whole page of bookings). */
    public record SeatLine(Long bookingId, Long showSeatId, String row, Integer number, SeatTier tier, BigDecimal price) {}

    public record BookedSeat(Long showSeatId, String row, Integer number, SeatTier tier, BigDecimal price) {
        static BookedSeat from(SeatLine l) {
            return new BookedSeat(l.showSeatId(), l.row(), l.number(), l.tier(), l.price());
        }
    }

    /** What cancelling a confirmed booking would give back right now. */
    public record CancellationQuote(boolean allowed, int refundPercent, BigDecimal refundAmount, String note) {}

    /** status: REFUND_PENDING | REFUNDED | REFUND_FAILED */
    public record RefundInfo(BigDecimal amount, String status) {}

    /**
     * ticketCode (the QR content) is only present on a confirmed booking.
     * cancellation is only present on a confirmed booking; refund only once a refund exists.
     */
    public record BookingView(Long id, Long showId, Long eventId, String eventTitle, String venueName, String venueCity,
                              Instant startsAt, BookingStatus status, BigDecimal subtotal, BigDecimal discountAmount,
                              String couponCode, BigDecimal totalAmount, Instant expiresAt, Instant createdAt,
                              String ticketCode, Instant checkedInAt, CancellationQuote cancellation, RefundInfo refund,
                              List<BookedSeat> seats) {}

    public record CheckInResult(Long bookingId, String eventTitle, Instant startsAt, String guestName,
                                List<String> seats, Instant checkedInAt) {}
}
