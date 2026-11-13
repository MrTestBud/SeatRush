package com.seatrush.show;

import com.seatrush.common.NotFoundException;
import com.seatrush.config.AuthUser;
import com.seatrush.event.EventService;
import com.seatrush.show.ShowDtos.*;
import com.seatrush.venue.VenueRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
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
    private static final BigDecimal VIP_MULTIPLIER = new BigDecimal("2");

    private final ShowRepository shows;
    private final ShowSeatRepository showSeats;
    private final EventService events;
    private final VenueRepository venues;
    private final PricingService pricing;
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher publisher;

    /**
     * Creates a show and its seat inventory in ONE transaction.
     * Inventory is generated with a single set-based INSERT ... SELECT (not N inserts from Java).
     */
    @Transactional
    public ShowView create(AuthUser user, Long eventId, ShowRequest r) {
        events.owned(user, eventId);
        if (!venues.existsById(r.venueId()))
            throw new NotFoundException("Venue " + r.venueId() + " not found");

        Show show = new Show(eventId, r.venueId(), r.startsAt(), r.basePrice());
        show.setWaitingRoom(Boolean.TRUE.equals(r.waitingRoom()));
        show.setDynamicPricing(Boolean.TRUE.equals(r.dynamicPricing()));
        shows.save(show);

        jdbc.update("""
                INSERT INTO show_seats (show_id, seat_id, status, price, version)
                SELECT ?, s.id, 'AVAILABLE',
                       CASE s.tier WHEN 'VIP' THEN CAST(? AS NUMERIC) WHEN 'PREMIUM' THEN CAST(? AS NUMERIC) ELSE CAST(? AS NUMERIC) END,
                       0
                FROM seats s WHERE s.venue_id = ?
                """, show.getId(), vip(r.basePrice(), r.vipPrice()), premium(r.basePrice(), r.premiumPrice()),
                r.basePrice(), r.venueId());

        return get(show.getId());
    }

    /** Reschedule / reprice. Seats that are already held or booked keep the price they were taken at. */
    @Transactional
    public ShowView update(AuthUser user, Long showId, ShowUpdate r) {
        Show show = owned(user, showId);
        show.setStartsAt(r.startsAt());
        show.setBasePrice(r.basePrice());
        if (r.waitingRoom() != null) show.setWaitingRoom(r.waitingRoom());
        if (r.dynamicPricing() != null) show.setDynamicPricing(r.dynamicPricing());
        shows.flush();

        jdbc.update("""
                UPDATE show_seats ss
                SET price = CASE s.tier WHEN 'VIP' THEN CAST(? AS NUMERIC) WHEN 'PREMIUM' THEN CAST(? AS NUMERIC) ELSE CAST(? AS NUMERIC) END,
                    version = ss.version + 1
                FROM seats s
                WHERE s.id = ss.seat_id AND ss.show_id = ? AND ss.status = 'AVAILABLE'
                """, vip(r.basePrice(), r.vipPrice()), premium(r.basePrice(), r.premiumPrice()), r.basePrice(), showId);
        publisher.publishEvent(new SeatsChanged(showId));
        return get(showId);
    }

    /** Only possible while nobody has booked: the bookings foreign key refuses otherwise (reported as a 409). */
    @Transactional
    public void delete(AuthUser user, Long showId) {
        shows.delete(owned(user, showId));
        shows.flush();
    }

    @Transactional(readOnly = true)
    public List<ShowView> forEvent(Long eventId) { return shows.viewsForEvent(eventId); }

    @Transactional(readOnly = true)
    public ShowView get(Long showId) {
        ShowView view = shows.viewsByIds(List.of(showId)).stream().findFirst()
                .orElseThrow(() -> new NotFoundException("Show " + showId + " not found"));
        return view.withSurge(pricing.surgePercent(showId, view.dynamicPricing()));
    }

    /**
     * Live seat map. A hold that has lapsed but not been swept yet is already shown as available,
     * and available seats show the price a buyer would pay right now (demand pricing included).
     */
    @Transactional(readOnly = true)
    public List<SeatView> seatMap(Long showId) {
        Show show = shows.findById(showId).orElseThrow(() -> new NotFoundException("Show " + showId + " not found"));
        int surge = pricing.surgePercent(show);
        Instant now = Instant.now();
        return showSeats.seatMap(showId).stream().map(r -> {
            boolean lapsed = r.status() == SeatStatus.HELD && r.heldUntil() != null && !r.heldUntil().isAfter(now);
            SeatStatus status = lapsed ? SeatStatus.AVAILABLE : r.status();
            BigDecimal price = status == SeatStatus.AVAILABLE ? PricingService.apply(r.price(), surge) : r.price();
            return new SeatView(r.showSeatId(), r.row(), r.number(), r.tier(), status, price);
        }).toList();
    }

    private Show owned(AuthUser user, Long showId) {
        Show show = shows.findById(showId).orElseThrow(() -> new NotFoundException("Show " + showId + " not found"));
        events.owned(user, show.getEventId());
        return show;
    }

    private static BigDecimal premium(BigDecimal base, BigDecimal explicit) {
        return explicit != null ? explicit : base.multiply(PREMIUM_MULTIPLIER).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal vip(BigDecimal base, BigDecimal explicit) {
        return explicit != null ? explicit : base.multiply(VIP_MULTIPLIER).setScale(2, RoundingMode.HALF_UP);
    }
}
