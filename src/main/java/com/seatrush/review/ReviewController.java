package com.seatrush.review;

import com.seatrush.common.ConflictException;
import com.seatrush.common.NotFoundException;
import com.seatrush.config.AuthUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/**
 * Ratings and reviews. Only someone who actually went (a confirmed booking for a show of this
 * event that has already started) can review, once per event; posting again edits their review.
 */
@RestController
@RequestMapping("/api/events/{eventId}/reviews")
@RequiredArgsConstructor
public class ReviewController {
    private final JdbcTemplate jdbc;

    public record ReviewRequest(@NotNull @Min(1) @Max(5) Integer rating, @Size(max = 2000) String comment) {}

    public record ReviewView(Long id, String author, int rating, String comment, Instant createdAt, boolean mine) {}

    /** canReview / myReview describe the caller and are false / null for anonymous visitors. */
    public record ReviewsView(double average, int count, boolean canReview, ReviewView myReview, List<ReviewView> items) {}

    @GetMapping
    @Transactional(readOnly = true)
    public ReviewsView list(@AuthenticationPrincipal AuthUser me, @PathVariable Long eventId) {
        requireEvent(eventId);
        long caller = me == null ? -1 : me.id();
        List<ReviewView> items = jdbc.query("""
                SELECT r.id, split_part(u.name, ' ', 1) AS author, r.rating, r.comment, r.created_at, r.user_id
                FROM reviews r JOIN users u ON u.id = r.user_id
                WHERE r.event_id = ?
                ORDER BY r.created_at DESC LIMIT 100
                """, (rs, i) -> new ReviewView(rs.getLong("id"), rs.getString("author"), rs.getInt("rating"),
                rs.getString("comment"), rs.getTimestamp("created_at").toInstant(), rs.getLong("user_id") == caller), eventId);
        var summary = jdbc.queryForMap("SELECT COALESCE(avg(rating), 0) AS average, count(*) AS n FROM reviews WHERE event_id = ?", eventId);
        return new ReviewsView(
                Math.round(((Number) summary.get("average")).doubleValue() * 10) / 10.0,
                ((Number) summary.get("n")).intValue(),
                me != null && attended(me.id(), eventId),
                items.stream().filter(ReviewView::mine).findFirst().orElse(null),
                items);
    }

    @PutMapping
    @Transactional
    public ReviewsView save(@AuthenticationPrincipal AuthUser me, @PathVariable Long eventId,
                            @Valid @RequestBody ReviewRequest r) {
        requireEvent(eventId);
        if (!attended(me.id(), eventId))
            throw new ConflictException("You can review an event after attending one of its shows.");
        String comment = r.comment() == null || r.comment().isBlank() ? null : r.comment().trim();
        jdbc.update("""
                INSERT INTO reviews (event_id, user_id, rating, comment) VALUES (?, ?, ?, ?)
                ON CONFLICT (event_id, user_id) DO UPDATE SET rating = EXCLUDED.rating, comment = EXCLUDED.comment, updated_at = now()
                """, eventId, me.id(), r.rating(), comment);
        return list(me, eventId);
    }

    private boolean attended(Long userId, Long eventId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM bookings b JOIN shows s ON s.id = b.show_id
                               WHERE b.user_id = ? AND s.event_id = ? AND b.status = 'CONFIRMED' AND s.starts_at < now())
                """, Boolean.class, userId, eventId));
    }

    private void requireEvent(Long eventId) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM events WHERE id = ?)", Boolean.class, eventId)))
            throw new NotFoundException("Event " + eventId + " not found");
    }
}
