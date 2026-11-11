package com.seatrush.venue;

import com.seatrush.venue.VenueDtos.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class VenueService {
    private final VenueRepository venues;
    private final SeatRepository seats;

    /** Creates a venue and generates its seat grid (rows A, B, C... ; first two rows are PREMIUM). */
    @Transactional
    public VenueView create(VenueRequest r) {
        Venue v = venues.save(new Venue(null, r.name().trim(), r.city().trim(), r.address().trim()));
        List<Seat> grid = new ArrayList<>();
        for (int i = 0; i < r.rows(); i++) {
            String row = String.valueOf((char) ('A' + i));
            SeatTier tier = i < 2 ? SeatTier.PREMIUM : SeatTier.REGULAR;
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
