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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    @Transactional(readOnly = true)
    public PageResponse<EventView> list(String q, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 50);
        int safePage = Math.max(page, 0);
        String query = (q == null || q.isBlank()) ? "" : q.trim();
        String key = "events:list:" + cache.generation(GENERATION) + ":" + safePage + ":" + safeSize + ":" + query.toLowerCase();
        return cache.get(key, new TypeReference<>() {}, TTL, () -> {
            var result = query.isEmpty()
                    ? events.findAll(PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "id")))
                    : events.search(query, PageRequest.of(safePage, safeSize));
            return PageResponse.of(result.map(EventView::from));
        });
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

    /** Loads an event and verifies the caller owns it (admins bypass). */
    public Event owned(AuthUser user, Long id) {
        Event e = find(id);
        if (user.role() != Role.ADMIN && !e.getOrganizerId().equals(user.id()))
            throw new AccessDeniedException("You do not own this event");
        return e;
    }

    private void invalidate(Long id) {
        cache.evict("event:" + id);
        cache.bumpGeneration(GENERATION);
    }

    private Event find(Long id) {
        return events.findById(id).orElseThrow(() -> new NotFoundException("Event " + id + " not found"));
    }

    private void apply(Event e, EventRequest r) {
        e.setTitle(r.title().trim());
        e.setDescription(r.description());
        e.setCategory(r.category().trim());
        e.setLanguage(r.language());
        e.setDurationMinutes(r.durationMinutes());
    }
}
