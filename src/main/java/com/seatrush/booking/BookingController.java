package com.seatrush.booking;

import com.seatrush.booking.BookingDtos.BookingView;
import com.seatrush.booking.BookingDtos.HoldRequest;
import com.seatrush.common.PageResponse;
import com.seatrush.config.AuthUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class BookingController {
    private final HoldCoordinator holds;
    private final BookingService bookings;

    /** Holds the seats for a few minutes and returns the booking to pay for. */
    @PostMapping("/shows/{showId}/holds")
    @ResponseStatus(HttpStatus.CREATED)
    public BookingView hold(@AuthenticationPrincipal AuthUser me, @PathVariable Long showId,
                            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                            @Valid @RequestBody HoldRequest r) {
        return holds.hold(me.id(), showId, r.showSeatIds(), idempotencyKey);
    }

    @GetMapping("/bookings")
    public PageResponse<BookingView> mine(@AuthenticationPrincipal AuthUser me,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        return bookings.listMine(me.id(), page, size);
    }

    @GetMapping("/bookings/{id}")
    public BookingView get(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return bookings.get(me.id(), id);
    }

    @PostMapping("/bookings/{id}/cancel")
    public BookingView cancel(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        bookings.cancel(me.id(), id);
        return bookings.get(me.id(), id);
    }
}
