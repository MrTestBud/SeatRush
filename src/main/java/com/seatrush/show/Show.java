package com.seatrush.show;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "shows")
@Getter @Setter @NoArgsConstructor
public class Show {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "event_id")
    private Long eventId;
    @Column(name = "venue_id")
    private Long venueId;
    @Column(name = "starts_at")
    private Instant startsAt;
    @Column(name = "base_price")
    private BigDecimal basePrice;
    /** High-demand shows: buyers queue fairly and are let in a few at a time. */
    @Column(name = "waiting_room")
    private boolean waitingRoom;
    /** Remaining seats cost more as the show fills up. */
    @Column(name = "dynamic_pricing")
    private boolean dynamicPricing;

    public Show(Long eventId, Long venueId, Instant startsAt, BigDecimal basePrice) {
        this.eventId = eventId;
        this.venueId = venueId;
        this.startsAt = startsAt;
        this.basePrice = basePrice;
    }
}
