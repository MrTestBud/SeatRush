package com.seatrush.booking;

import com.seatrush.booking.BookingDtos.*;
import com.seatrush.common.NotFoundException;
import com.seatrush.common.PageResponse;
import com.seatrush.config.AuthUser;
import com.seatrush.show.ShowRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class BookingController {
    private final HoldCoordinator holds;
    private final BookingService bookings;
    private final WaitingRoom waitingRoom;
    private final ShowRepository shows;

    public record QueueStatus(boolean enabled, boolean admitted, int position, Instant admittedUntil) {}

    /**
     * Waiting room: call when opening a show's seat map and keep calling every few seconds while waiting.
     * For a show without a waiting room it simply answers "admitted".
     */
    @PostMapping("/shows/{showId}/queue")
    public QueueStatus queue(@AuthenticationPrincipal AuthUser me, @PathVariable Long showId) {
        var show = shows.findById(showId).orElseThrow(() -> new NotFoundException("Show " + showId + " not found"));
        if (!show.isWaitingRoom()) return new QueueStatus(false, true, 0, null);
        WaitingRoom.Ticket t = waitingRoom.enter(showId, me.id());
        return new QueueStatus(true, t.admitted(), t.position(), t.admittedUntil());
    }

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

    /** Unpaid: releases the seats. Paid: releases the seats and refunds according to the cancellation policy. */
    @PostMapping("/bookings/{id}/cancel")
    public BookingView cancel(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        bookings.cancel(me.id(), id);
        return bookings.get(me.id(), id);
    }

    @PostMapping("/bookings/{id}/coupon")
    public BookingView applyCoupon(@AuthenticationPrincipal AuthUser me, @PathVariable Long id,
                                   @Valid @RequestBody CouponRequest r) {
        bookings.applyCoupon(me.id(), id, r.code());
        return bookings.get(me.id(), id);
    }

    @DeleteMapping("/bookings/{id}/coupon")
    public BookingView removeCoupon(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        bookings.removeCoupon(me.id(), id);
        return bookings.get(me.id(), id);
    }

    /** Door check-in: the organizer scans (or types) the ticket code. One ticket admits once. */
    @PostMapping("/checkin")
    @PreAuthorize("hasAnyRole('ORGANIZER','ADMIN')")
    public CheckInResult checkIn(@AuthenticationPrincipal AuthUser me, @Valid @RequestBody CheckInRequest r) {
        return bookings.checkIn(me, r.code());
    }
}
