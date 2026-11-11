package com.seatrush.event;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public class EventDtos {
    public record EventRequest(
            @NotBlank @Size(max = 200) String title,
            @Size(max = 5000) String description,
            @NotBlank @Size(max = 50) String category,
            @Size(max = 50) String language,
            @NotNull @Min(1) Integer durationMinutes) {}

    public record EventView(Long id, Long organizerId, String title, String description,
                            String category, String language, Integer durationMinutes, Instant createdAt) {
        public static EventView from(Event e) {
            return new EventView(e.getId(), e.getOrganizerId(), e.getTitle(), e.getDescription(),
                    e.getCategory(), e.getLanguage(), e.getDurationMinutes(), e.getCreatedAt());
        }
    }
}
