package com.seatrush.event;

import com.seatrush.common.BadRequestException;
import com.seatrush.common.NotFoundException;
import com.seatrush.config.AuthUser;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Event posters, stored in PostgreSQL (bytea). Fine at this scale and it keeps the deployment
 * a single database with nothing extra to back up; object storage is the move if posters get big.
 * The content type is taken from the file's magic bytes, never from what the client claims.
 */
@Service
@RequiredArgsConstructor
public class PosterService {
    public static final int MAX_BYTES = 2 * 1024 * 1024;

    public record Poster(String contentType, byte[] data) {}

    private final EventService events;
    private final JdbcTemplate jdbc;

    @Transactional
    public EventDtos.EventView upload(AuthUser user, Long eventId, byte[] data) {
        Event e = events.owned(user, eventId);
        if (data.length == 0) throw new BadRequestException("The file is empty");
        if (data.length > MAX_BYTES) throw new BadRequestException("Posters can be at most 2 MB");
        String type = sniff(data);
        if (type == null) throw new BadRequestException("Upload a JPEG, PNG or WebP image");

        jdbc.update("""
                INSERT INTO event_posters (event_id, content_type, data) VALUES (?, ?, ?)
                ON CONFLICT (event_id) DO UPDATE SET content_type = EXCLUDED.content_type, data = EXCLUDED.data, updated_at = now()
                """, eventId, type, data);
        e.setPosterVersion(e.getPosterVersion() == null ? 1 : e.getPosterVersion() + 1);
        events.invalidate(eventId);
        return EventDtos.EventView.from(e);
    }

    @Transactional
    public void remove(AuthUser user, Long eventId) {
        Event e = events.owned(user, eventId);
        jdbc.update("DELETE FROM event_posters WHERE event_id = ?", eventId);
        e.setPosterVersion(null);
        events.invalidate(eventId);
    }

    @Transactional(readOnly = true)
    public Poster get(Long eventId) {
        return jdbc.query("SELECT content_type, data FROM event_posters WHERE event_id = ?",
                        (rs, i) -> new Poster(rs.getString(1), rs.getBytes(2)), eventId)
                .stream().findFirst().orElseThrow(() -> new NotFoundException("This event has no poster"));
    }

    private static String sniff(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "image/jpeg";
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "image/webp";
        return null;
    }
}
