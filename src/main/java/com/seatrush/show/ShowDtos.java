package com.seatrush.show;

import com.seatrush.venue.SeatTier;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;

public class ShowDtos {
    public record ShowRequest(
            @NotNull Long venueId,
            @NotNull @Future Instant startsAt,
            @NotNull @DecimalMin("0.0") BigDecimal basePrice) {}

    public record ShowView(Long id, Long eventId, String eventTitle, Long venueId, String venueName, String venueCity,
                           Instant startsAt, BigDecimal basePrice) {}

    /** Internal projection; heldUntil is used to show lapsed holds as available, and is not exposed. */
    public record SeatRow(Long showSeatId, String row, Integer number, SeatTier tier,
                          SeatStatus status, BigDecimal price, Instant heldUntil) {}

    public record SeatView(Long showSeatId, String row, Integer number, SeatTier tier,
                           SeatStatus status, BigDecimal price) {}
}
