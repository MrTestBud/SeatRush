package com.seatrush.booking;

import com.seatrush.booking.BookingDtos.BookingView;
import com.seatrush.common.BadRequestException;
import com.seatrush.common.ConflictException;
import com.seatrush.show.Show;
import com.seatrush.show.ShowRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;

/**
 * Entry point for holding seats: idempotency check, waiting room, Redis gate, then the database
 * transaction. Kept outside {@link BookingService} so the gate is taken before (and released
 * after) the transaction rather than inside it.
 */
@Service
public class HoldCoordinator {
    private final BookingService bookings;
    private final HoldGate gate;
    private final WaitingRoom waitingRoom;
    private final ShowRepository shows;
    private final MeterRegistry metrics;
    private final LockStrategy defaultStrategy;

    public HoldCoordinator(BookingService bookings, HoldGate gate, WaitingRoom waitingRoom, ShowRepository shows,
                           MeterRegistry metrics,
                           @Value("${app.booking.lock-strategy:PESSIMISTIC}") LockStrategy defaultStrategy) {
        this.bookings = bookings;
        this.gate = gate;
        this.waitingRoom = waitingRoom;
        this.shows = shows;
        this.metrics = metrics;
        this.defaultStrategy = defaultStrategy;
    }

    public BookingView hold(Long userId, Long showId, Collection<Long> showSeatIds, String idempotencyKey) {
        return hold(userId, showId, showSeatIds, idempotencyKey, defaultStrategy);
    }

    public BookingView hold(Long userId, Long showId, Collection<Long> showSeatIds, String idempotencyKey,
                            LockStrategy strategy) {
        Timer.Sample sample = Timer.start(metrics);
        String outcome = "error";
        try {
            BookingView view = doHold(userId, showId, showSeatIds, idempotencyKey, strategy);
            outcome = "held";
            return view;
        } catch (SeatUnavailableException e) {
            outcome = "conflict";
            throw e;
        } catch (ConflictException | BadRequestException e) {
            outcome = "rejected";
            throw e;
        } finally {
            // seatrush_hold_seconds{strategy,outcome}: latency and the conflict rate, per locking strategy
            sample.stop(Timer.builder("seatrush.hold").description("Seat hold attempts")
                    .tag("strategy", strategy.name()).tag("outcome", outcome).register(metrics));
        }
    }

    private BookingView doHold(Long userId, Long showId, Collection<Long> showSeatIds, String idempotencyKey,
                               LockStrategy strategy) {
        String key = scopedKey(userId, idempotencyKey);
        if (key != null) {
            // A retried request (double click, flaky network) gets the booking it already made.
            BookingView existing = bookings.findByIdempotencyKey(key);
            if (existing != null) return existing;
        }

        boolean queued = shows.findById(showId).map(Show::isWaitingRoom).orElse(false);
        if (queued && !waitingRoom.isAdmitted(showId, userId))
            throw new ConflictException("This show has a waiting room. Wait for your turn before picking seats.");

        List<Long> ids = showSeatIds.stream().distinct().sorted().toList();
        if (!gate.tryAcquire(ids, userId, bookings.holdDuration())) {
            return retriedOrThrow(key, new SeatUnavailableException());
        }
        try {
            BookingView view = bookings.view(bookings.hold(userId, showId, ids, key, strategy).getId());
            if (queued) waitingRoom.leave(showId, userId);   // pass used: let the next person in
            return view;
        } catch (RuntimeException e) {
            // Nothing was held. We own the gate keys we just set, so give them back now rather than
            // leaving free seats gated for the whole TTL (e.g. 1 of 3 requested seats was booked).
            gate.release(ids);
            if (e instanceof OptimisticLockingFailureException)        // lost the @Version race
                return retriedOrThrow(key, new SeatUnavailableException());
            if (e instanceof SeatUnavailableException)                 // lost the row-lock race
                return retriedOrThrow(key, e);
            if (e instanceof DataIntegrityViolationException)          // same idempotency key raced itself
                return retriedOrThrow(key, new ConflictException("This request was already processed"));
            throw e;
        }
    }

    /** If the failure was really our own earlier request winning, return that booking instead of an error. */
    private BookingView retriedOrThrow(String key, RuntimeException failure) {
        if (key != null) {
            BookingView existing = bookings.findByIdempotencyKey(key);
            if (existing != null) return existing;
        }
        throw failure;
    }

    /** Keys are namespaced per user so one user can never read another's booking by guessing a key. */
    private static String scopedKey(Long userId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) return null;
        String k = idempotencyKey.trim();
        if (k.length() > 64) throw new BadRequestException("Idempotency-Key must be at most 64 characters");
        return userId + ":" + k;
    }
}
