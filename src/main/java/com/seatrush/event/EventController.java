package com.seatrush.event;

import com.seatrush.common.PageResponse;
import com.seatrush.config.AuthUser;
import com.seatrush.event.EventDtos.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class EventController {
    private final EventService service;

    @GetMapping
    public PageResponse<EventView> list(@RequestParam(required = false) String q,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "20") int size) {
        return service.list(q, page, size);
    }

    @GetMapping("/mine")
    @PreAuthorize("hasAnyRole('ORGANIZER','ADMIN')")
    public java.util.List<EventView> mine(@AuthenticationPrincipal AuthUser me) { return service.mine(me); }

    @GetMapping("/{id}")
    public EventView get(@PathVariable Long id) { return service.get(id); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ORGANIZER','ADMIN')")
    public EventView create(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody EventRequest r) {
        return service.create(me, r);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ORGANIZER','ADMIN')")
    public EventView update(@AuthenticationPrincipal AuthUser me, @PathVariable Long id,
                            @Valid @RequestBody EventRequest r) {
        return service.update(me, id, r);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ORGANIZER','ADMIN')")
    public void delete(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) { service.delete(me, id); }
}
