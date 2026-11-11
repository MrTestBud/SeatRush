package com.seatrush.venue;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class VenueDtos {
    public record VenueRequest(
            @NotBlank String name,
            @NotBlank String city,
            @NotBlank String address,
            @NotNull @Min(1) @Max(26) Integer rows,
            @NotNull @Min(1) @Max(50) Integer seatsPerRow) {}

    public record VenueView(Long id, String name, String city, String address) {
        public static VenueView from(Venue v) { return new VenueView(v.getId(), v.getName(), v.getCity(), v.getAddress()); }
    }
}
