package com.seatrush.venue;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public class VenueDtos {
    /**
     * Layout: rows A, B, C... of seatsPerRow seats. The first vipRows rows are VIP, the next
     * premiumRows are PREMIUM (default 2), the rest REGULAR. aisles lists the seat numbers after
     * which a walkway is drawn.
     */
    public record VenueRequest(
            @NotBlank @Size(max = 150) String name,
            @NotBlank @Size(max = 100) String city,
            @NotBlank @Size(max = 255) String address,
            @NotNull @Min(1) @Max(26) Integer rows,
            @NotNull @Min(1) @Max(50) Integer seatsPerRow,
            @Min(0) @Max(26) Integer vipRows,
            @Min(0) @Max(26) Integer premiumRows,
            @Size(max = 10) List<@NotNull @Min(1) @Max(49) Integer> aisles) {}

    public record VenueView(Long id, String name, String city, String address, String aisles) {
        public static VenueView from(Venue v) {
            return new VenueView(v.getId(), v.getName(), v.getCity(), v.getAddress(), v.getAisles());
        }
    }
}
