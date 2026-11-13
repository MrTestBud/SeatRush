package com.seatrush.event;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public class EventDtos {
    public record EventRequest(
            @NotBlank @Size(max = 200) String title,
            @Size(max = 5000) String description,
            @NotBlank @Size(max = 50) String category,
            @Size(max = 50) String language,
            @NotNull @Min(1) Integer durationMinutes) {}

    /** posterUrl is null when the event has no poster; the ?v= part changes on every upload so browsers can cache forever. */
    public record EventView(Long id, Long organizerId, String title, String description,
                            String category, String language, Integer durationMinutes, Instant createdAt,
                            String posterUrl) {
        public static EventView from(Event e) {
            return new EventView(e.getId(), e.getOrganizerId(), e.getTitle(), e.getDescription(),
                    e.getCategory(), e.getLanguage(), e.getDurationMinutes(), e.getCreatedAt(),
                    e.getPosterVersion() == null ? null : "/api/events/" + e.getId() + "/poster?v=" + e.getPosterVersion());
        }
    }

    /** One row of the organizer dashboard. */
    public record ShowStats(Long showId, Instant startsAt, String venueName, int totalSeats, int booked, int held,
                            int available, int confirmedBookings, int checkedIn, BigDecimal revenue) {}

    public record EventStats(Long eventId, int totalSeats, int booked, int confirmedBookings, int checkedIn,
                             BigDecimal revenue, List<ShowStats> shows) {}
}
