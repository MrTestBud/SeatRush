package com.seatrush.event;

import com.seatrush.common.PageResponse;
import com.seatrush.config.AuthUser;
import com.seatrush.event.EventDtos.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class EventController {
    private final EventService service;
    private final PosterService posters;

    @GetMapping
    public PageResponse<EventView> list(@RequestParam(required = false) String q,
                                        @RequestParam(required = false) String category,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "20") int size) {
        return service.list(q, category, page, size);
    }

    @GetMapping("/categories")
    public List<String> categories() { return service.categories(); }

    @GetMapping("/mine")
    @PreAuthorize("hasAnyRole('ORGANIZER','ADMIN')")
    public List<EventView> mine(@AuthenticationPrincipal AuthUser me) { return service.mine(me); }

    @GetMapping("/{id}")
    public EventView get(@PathVariable Long id) { return service.get(id); }

    @GetMapping("/{id}/stats")
    @PreAuthorize("hasAnyRole('ORGANIZER','ADMIN')")
    public EventStats stats(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) { return service.stats(me, id); }

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

    // ---------------------------------------------------------------- poster

    @PutMapping(value = "/{id}/poster", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('ORGANIZER','ADMIN')")
    public EventView uploadPoster(@AuthenticationPrincipal AuthUser me, @PathVariable Long id,
                                  @RequestParam("file") MultipartFile file) throws IOException {
        return posters.upload(me, id, file.getBytes());
    }

    @DeleteMapping("/{id}/poster")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ORGANIZER','ADMIN')")
    public void deletePoster(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) { posters.remove(me, id); }

    /** The URL carries a version (?v=N) that changes on every upload, so the image itself can be cached for a year. */
    @GetMapping("/{id}/poster")
    public ResponseEntity<byte[]> poster(@PathVariable Long id) {
        PosterService.Poster p = posters.get(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(p.contentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                .body(p.data());
    }
}
