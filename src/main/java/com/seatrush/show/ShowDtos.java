package com.seatrush.show;

import com.seatrush.venue.SeatTier;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;

public class ShowDtos {
    /**
     * basePrice is the REGULAR price. premiumPrice / vipPrice are optional; when left out they
     * default to 1.5x and 2x the base price.
     */
    public record ShowRequest(
            @NotNull Long venueId,
            @NotNull @Future Instant startsAt,
            @NotNull @DecimalMin("0.0") BigDecimal basePrice,
            @DecimalMin("0.0") BigDecimal premiumPrice,
            @DecimalMin("0.0") BigDecimal vipPrice,
            Boolean waitingRoom,
            Boolean dynamicPricing) {}

    /** Everything about a show except its venue can be edited. New prices apply to seats that are still available. */
    public record ShowUpdate(
            @NotNull @Future Instant startsAt,
            @NotNull @DecimalMin("0.0") BigDecimal basePrice,
            @DecimalMin("0.0") BigDecimal premiumPrice,
            @DecimalMin("0.0") BigDecimal vipPrice,
            Boolean waitingRoom,
            Boolean dynamicPricing) {}

    /**
     * venueAisles: seat numbers after which the map draws an aisle ("3,7").
     * surgePercent: how much demand pricing currently adds to seats that are still available (0 = none).
     */
    public record ShowView(Long id, Long eventId, String eventTitle, Long venueId, String venueName, String venueCity,
                           String venueAisles, Instant startsAt, BigDecimal basePrice,
                           boolean waitingRoom, boolean dynamicPricing, int surgePercent) {
        /** Used by the JPQL projection in ShowRepository. */
        public ShowView(Long id, Long eventId, String eventTitle, Long venueId, String venueName, String venueCity,
                        String venueAisles, Instant startsAt, BigDecimal basePrice,
                        boolean waitingRoom, boolean dynamicPricing) {
            this(id, eventId, eventTitle, venueId, venueName, venueCity, venueAisles, startsAt, basePrice,
                    waitingRoom, dynamicPricing, 0);
        }

        public ShowView withSurge(int percent) {
            return new ShowView(id, eventId, eventTitle, venueId, venueName, venueCity, venueAisles, startsAt, basePrice,
                    waitingRoom, dynamicPricing, percent);
        }
    }

    /** Internal projection; heldUntil is used to show lapsed holds as available, and is not exposed. */
    public record SeatRow(Long showSeatId, String row, Integer number, SeatTier tier,
                          SeatStatus status, BigDecimal price, Instant heldUntil) {}

    public record SeatView(Long showSeatId, String row, Integer number, SeatTier tier,
                           SeatStatus status, BigDecimal price) {}
}
