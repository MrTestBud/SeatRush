package com.seatrush.venue;

import com.seatrush.venue.VenueDtos.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class VenueService {
    private final VenueRepository venues;
    private final SeatRepository seats;

    /** Creates a venue and generates its seat grid: VIP rows first, then PREMIUM, then REGULAR. */
    @Transactional
    public VenueView create(VenueRequest r) {
        int vipRows = r.vipRows() == null ? 0 : r.vipRows();
        int premiumRows = r.premiumRows() == null ? 2 : r.premiumRows();
        String aisles = r.aisles() == null ? "" : r.aisles().stream()
                .filter(n -> n < r.seatsPerRow()).distinct().sorted()      // an aisle after the last seat is meaningless
                .map(String::valueOf).collect(Collectors.joining(","));

        Venue v = venues.save(new Venue(r.name().trim(), r.city().trim(), r.address().trim(), aisles));
        List<Seat> grid = new ArrayList<>();
        for (int i = 0; i < r.rows(); i++) {
            String row = String.valueOf((char) ('A' + i));
            SeatTier tier = i < vipRows ? SeatTier.VIP : i < vipRows + premiumRows ? SeatTier.PREMIUM : SeatTier.REGULAR;
            for (int n = 1; n <= r.seatsPerRow(); n++) {
                grid.add(new Seat(null, v.getId(), row, n, tier));
            }
        }
        seats.saveAll(grid);
        return VenueView.from(v);
    }

    @Transactional(readOnly = true)
    public List<VenueView> list() { return venues.findAll().stream().map(VenueView::from).toList(); }
}
