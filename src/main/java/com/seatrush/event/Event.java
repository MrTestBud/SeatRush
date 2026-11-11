package com.seatrush.event;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "events")
@Getter @Setter @NoArgsConstructor
public class Event {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "organizer_id")
    private Long organizerId;
    private String title;
    private String description;
    private String category;
    private String language;
    @Column(name = "duration_minutes")
    private Integer durationMinutes;
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;
}
