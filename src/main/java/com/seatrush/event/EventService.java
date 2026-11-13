package com.seatrush.event;

import com.fasterxml.jackson.core.type.TypeReference;
import com.seatrush.common.NotFoundException;
import com.seatrush.common.PageResponse;
import com.seatrush.config.AuthUser;
import com.seatrush.event.EventDtos.*;
import com.seatrush.infra.JsonCache;
import com.seatrush.user.Role;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

/**
 * Browsing is read-heavy and changes rarely, so event pages are cached in Redis.
 * Invalidation: a single event is evicted by key; list/search pages embed a generation counter
 * in their key, so one INCR on any write invalidates every cached page at once.
 * (The seat map is never cached: it must be live.)
 */
@Service
@RequiredArgsConstructor
public class EventService {
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final String GENERATION = "events:gen";

    private final EventRepository events;
    private final JsonCache cache;
    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public PageResponse<EventView> list(String q, String category, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 50);
        int safePage = Math.max(page, 0);
        String query = (q == null || q.isBlank()) ? "" : q.trim();
        String cat = (category == null || category.isBlank()) ? "" : category.trim().toUpperCase();
        String key = "events:list:" + cache.generation(GENERATION) + ":" + safePage + ":" + safeSize + ":" + cat + ":" + query.toLowerCase();
        return cache.get(key, new TypeReference<>() {}, TTL, () -> {
            var newestFirst = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "id"));
            var result = !query.isEmpty() ? events.search(query, cat, PageRequest.of(safePage, safeSize))
                    : cat.isEmpty() ? events.findAll(newestFirst)
                    : events.findByCategoryIgnoreCase(cat, newestFirst);
            return PageResponse.of(result.map(EventView::from));
        });
    }

    /** Categories that actually have events, for the filter chips. */
    @Transactional(readOnly = true)
    public List<String> categories() {
        return cache.get("events:categories:" + cache.generation(GENERATION), new TypeReference<>() {}, TTL, events::categories);
    }

    @Transactional(readOnly = true)
    public EventView get(Long id) {
        return cache.get("event:" + id, new TypeReference<>() {}, TTL, () -> EventView.from(find(id)));
    }

    /** The organizer's own events, straight from the database (they must see their edits immediately). */
    @Transactional(readOnly = true)
    public List<EventView> mine(AuthUser user) {
        return events.findByOrganizerIdOrderByIdDesc(user.id()).stream().map(EventView::from).toList();
    }

    @Transactional
    public EventView create(AuthUser user, EventRequest r) {
        Event e = new Event();
        e.setOrganizerId(user.id());
        apply(e, r);
        EventView view = EventView.from(events.save(e));
        invalidate(view.id());
        return view;
    }

    @Transactional
    public EventView update(AuthUser user, Long id, EventRequest r) {
        Event e = owned(user, id);
        apply(e, r);
        invalidate(id);
        return EventView.from(e);
    }

    @Transactional
    public void delete(AuthUser user, Long id) {
        events.delete(owned(user, id));
        events.flush();   // surface FK violations (event has bookings) here, as a 409
        invalidate(id);
    }

    /** Organizer dashboard: seats sold, occupancy, check-ins and revenue per show, in one query. */
    @Transactional(readOnly = true)
    public EventStats stats(AuthUser user, Long id) {
        owned(user, id);
        List<ShowStats> shows = jdbc.query("""
                SELECT s.id, s.starts_at, v.name,
                       (SELECT count(*) FROM show_seats ss WHERE ss.show_id = s.id) AS total,
                       (SELECT count(*) FROM show_seats ss WHERE ss.show_id = s.id AND ss.status = 'BOOKED') AS booked,
                       (SELECT count(*) FROM show_seats ss WHERE ss.show_id = s.id AND ss.status = 'HELD' AND ss.held_until > now()) AS held,
                       (SELECT count(*) FROM bookings b WHERE b.show_id = s.id AND b.status = 'CONFIRMED') AS confirmed,
                       (SELECT count(*) FROM bookings b WHERE b.show_id = s.id AND b.status = 'CONFIRMED' AND b.checked_in_at IS NOT NULL) AS checked_in,
                       COALESCE((SELECT sum(b.total_amount) FROM bookings b WHERE b.show_id = s.id AND b.status = 'CONFIRMED'), 0) AS revenue
                FROM shows s JOIN venues v ON v.id = s.venue_id
                WHERE s.event_id = ?
                ORDER BY s.starts_at
                """, (rs, i) -> new ShowStats(rs.getLong("id"), rs.getTimestamp("starts_at").toInstant(), rs.getString("name"),
                rs.getInt("total"), rs.getInt("booked"), rs.getInt("held"),
                rs.getInt("total") - rs.getInt("booked") - rs.getInt("held"),
                rs.getInt("confirmed"), rs.getInt("checked_in"), rs.getBigDecimal("revenue")), id);
        return new EventStats(id,
                shows.stream().mapToInt(ShowStats::totalSeats).sum(),
                shows.stream().mapToInt(ShowStats::booked).sum(),
                shows.stream().mapToInt(ShowStats::confirmedBookings).sum(),
                shows.stream().mapToInt(ShowStats::checkedIn).sum(),
                shows.stream().map(ShowStats::revenue).reduce(BigDecimal.ZERO, BigDecimal::add),
                shows);
    }

    /** Loads an event and verifies the caller owns it (admins bypass). */
    public Event owned(AuthUser user, Long id) {
        Event e = find(id);
        if (user.role() != Role.ADMIN && !e.getOrganizerId().equals(user.id()))
            throw new AccessDeniedException("You do not own this event");
        return e;
    }

    public void invalidate(Long id) {
        cache.evict("event:" + id);
        cache.bumpGeneration(GENERATION);
    }

    private Event find(Long id) {
        return events.findById(id).orElseThrow(() -> new NotFoundException("Event " + id + " not found"));
    }

    private void apply(Event e, EventRequest r) {
        e.setTitle(r.title().trim());
        e.setDescription(r.description());
        e.setCategory(r.category().trim().toUpperCase());
        e.setLanguage(r.language());
        e.setDurationMinutes(r.durationMinutes());
    }
}
