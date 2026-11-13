package com.seatrush.venue;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "venues")
@Getter @Setter @NoArgsConstructor
public class Venue {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;
    private String city;
    private String address;
    /** Seat numbers after which the seat map draws an aisle, comma separated (e.g. "3,7"). Empty = no aisles. */
    private String aisles = "";

    public Venue(String name, String city, String address, String aisles) {
        this.name = name;
        this.city = city;
        this.address = address;
        this.aisles = aisles;
    }
}
