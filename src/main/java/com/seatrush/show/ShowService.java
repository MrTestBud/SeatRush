package com.seatrush.show;

import com.seatrush.common.NotFoundException;
import com.seatrush.config.AuthUser;
import com.seatrush.event.EventService;
import com.seatrush.show.ShowDtos.*;
import com.seatrush.venue.VenueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ShowService {
    private static final BigDecimal PREMIUM_MULTIPLIER = new BigDecimal("1.5");

    private final ShowRepository shows;
    private final ShowSeatRepository showSeats;
    private final EventService events;
    private final VenueRepository venues;
    private final JdbcTemplate jdbc;

    /**
     * Creates a show and its seat inventory in ONE transaction.
     * Inventory is generated with a single set-based INSERT ... SELECT (not N inserts from Java).
     */
    @Transactional
    public ShowView create(AuthUser user, Long eventId, ShowRequest r) {
        events.owned(user, eventId);
        if (!venues.existsById(r.venueId()))
            throw new NotFoundException("Venue " + r.venueId() + " not found");

        Show show = shows.save(new Show(null, eventId, r.venueId(), r.startsAt(), r.basePrice()));
        BigDecimal premium = r.basePrice().multiply(PREMIUM_MULTIPLIER).setScale(2, RoundingMode.HALF_UP);

        jdbc.update("""
                INSERT INTO show_seats (show_id, seat_id, status, price, version)
                SELECT ?, s.id, 'AVAILABLE',
                       CASE WHEN s.tier = 'PREMIUM' THEN CAST(? AS NUMERIC) ELSE CAST(? AS NUMERIC) END,
                       0
                FROM seats s WHERE s.venue_id = ?
                """, show.getId(), premium, r.basePrice(), r.venueId());

        return get(show.getId());
    }

    @Transactional(readOnly = true)
    public List<ShowView> forEvent(Long eventId) { return shows.viewsForEvent(eventId); }

    @Transactional(readOnly = true)
    public ShowView get(Long showId) {
        return shows.viewsByIds(List.of(showId)).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Show " + showId + " not found"));
    }

    /** Live seat map. A hold that has lapsed but not been swept yet is already shown as available. */
    @Transactional(readOnly = true)
    public List<SeatView> seatMap(Long showId) {
        if (!shows.existsById(showId)) throw new NotFoundException("Show " + showId + " not found");
        Instant now = Instant.now();
        return showSeats.seatMap(showId).stream().map(r -> {
            boolean lapsed = r.status() == SeatStatus.HELD && r.heldUntil() != null && !r.heldUntil().isAfter(now);
            return new SeatView(r.showSeatId(), r.row(), r.number(), r.tier(),
                    lapsed ? SeatStatus.AVAILABLE : r.status(), r.price());
        }).toList();
    }
}
