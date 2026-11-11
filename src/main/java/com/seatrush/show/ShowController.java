package com.seatrush.show;

import com.seatrush.config.AuthUser;
import com.seatrush.show.ShowDtos.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ShowController {
    private final ShowService service;

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

    @GetMapping("/shows/{showId}/seats")
    public List<SeatView> seatMap(@PathVariable Long showId) { return service.seatMap(showId); }
}
