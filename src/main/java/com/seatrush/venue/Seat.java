package com.seatrush.venue;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "seats")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class Seat {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "venue_id")
    private Long venueId;
    @Column(name = "row_label")
    private String rowLabel;
    @Column(name = "seat_number")
    private Integer seatNumber;
    @Enumerated(EnumType.STRING)
    private SeatTier tier;
}
