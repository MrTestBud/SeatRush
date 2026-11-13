package com.seatrush.show;

import com.seatrush.config.AuthUser;
import com.seatrush.show.ShowDtos.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ShowController {
    private final ShowService service;
    private final SeatStream seatStream;

    @PostMapping("/events/{eventId}/shows")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ORGANIZER','ADMIN')")
    public ShowView create(@AuthenticationPrincipal AuthUser me, @PathVariable Long eventId,
                           @Valid @RequestBody ShowRequest r) {
        return service.create(me, eventId, r);
    }

    @GetMapping("/events/{eventId}/shows")
    public List<ShowView> forEvent(@PathVariable Long eventId) { return service.forEvent(eventId); }

    @GetMapping("/shows/{showId}")
    public ShowView get(@PathVariable Long showId) { return service.get(showId); }

    @PutMapping("/shows/{showId}")
    @PreAuthorize("hasAnyRole('ORGANIZER','ADMIN')")
    public ShowView update(@AuthenticationPrincipal AuthUser me, @PathVariable Long showId,
                           @Valid @RequestBody ShowUpdate r) {
        return service.update(me, showId, r);
    }

    @DeleteMapping("/shows/{showId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ORGANIZER','ADMIN')")
    public void delete(@AuthenticationPrincipal AuthUser me, @PathVariable Long showId) { service.delete(me, showId); }

    @GetMapping("/shows/{showId}/seats")
    public List<SeatView> seatMap(@PathVariable Long showId) { return service.seatMap(showId); }

    /**
     * Server-Sent Events: one "seats" event every time a seat of this show changes, so the seat
     * map updates the moment someone else holds, books or releases a seat. The client then
     * re-reads the seat map (the event carries no data that could go stale or arrive out of order).
     */
    @GetMapping(value = "/shows/{showId}/seats/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter seatStream(@PathVariable Long showId) {
        service.get(showId);   // 404 for an unknown show
        return seatStream.subscribe(showId);
    }
}
