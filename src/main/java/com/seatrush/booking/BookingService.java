package com.seatrush.booking;

import com.seatrush.booking.BookingDtos.BookedSeat;
import com.seatrush.booking.BookingDtos.BookingView;
import com.seatrush.booking.BookingDtos.SeatLine;
import com.seatrush.common.BadRequestException;
import com.seatrush.common.ConflictException;
import com.seatrush.common.NotFoundException;
import com.seatrush.common.PageResponse;
import com.seatrush.notification.OutboxMessage;
import com.seatrush.notification.OutboxRepository;
import com.seatrush.payment.Payment;
import com.seatrush.payment.PaymentRepository;
import com.seatrush.payment.PaymentStatus;
import com.seatrush.show.Show;
import com.seatrush.show.ShowDtos.ShowView;
import com.seatrush.show.ShowRepository;
import com.seatrush.show.ShowSeat;
import com.seatrush.show.ShowSeatRepository;
import com.seatrush.show.SeatStatus;
import com.seatrush.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * All booking state changes. Locking rules that keep this deadlock-free and race-free:
 * <ul>
 *   <li>hold() locks only seat rows, always in id order.</li>
 *   <li>Everything else locks the booking row first, then its seat rows (in id order).</li>
 *   <li>Payment results lock the payment row, then the booking, then the seats.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookingService {
    private static final EnumSet<BookingStatus> ACTIVE = EnumSet.of(BookingStatus.HELD, BookingStatus.PAYMENT_PENDING);

    public enum PaymentOutcome { CONFIRMED, FAILED, REFUND_PENDING, DUPLICATE, UNKNOWN_ORDER }

    private final BookingRepository bookings;
    private final BookingItemRepository items;
    private final ShowSeatRepository showSeats;
    private final ShowRepository shows;
    private final PaymentRepository payments;
    private final UserRepository users;
    private final OutboxRepository outbox;
    private final HoldGate gate;

    @Value("${app.booking.hold-minutes:5}")
    private long holdMinutes;
    @Value("${app.booking.payment-minutes:10}")
    private long paymentMinutes;
    @Value("${app.booking.max-seats:8}")
    private int maxSeats;

    public Duration holdDuration() { return Duration.ofMinutes(holdMinutes); }

    // ------------------------------------------------------------------ hold

    /**
     * Holds seats for a user: the heart of the "two users, one seat" problem.
     * Either every requested seat is held by this booking or none is (one transaction).
     */
    @Transactional
    public Booking hold(Long userId, Long showId, Collection<Long> showSeatIds, String idempotencyKey,
                        LockStrategy strategy) {
        List<Long> ids = showSeatIds.stream().distinct().sorted().toList();
        if (ids.isEmpty()) throw new BadRequestException("Pick at least one seat");
        if (ids.size() > maxSeats) throw new BadRequestException("You can book at most " + maxSeats + " seats at once");

        Instant now = Instant.now();
        Show show = shows.findById(showId).orElseThrow(() -> new NotFoundException("Show " + showId + " not found"));
        if (!show.getStartsAt().isAfter(now)) throw new ConflictException("This show has already started");

        List<ShowSeat> seats = strategy == LockStrategy.PESSIMISTIC
                ? showSeats.lockForHold(showId, ids)     // losers wait here, then fail the check below
                : showSeats.findForHold(showId, ids);    // losers fail at flush() with a version conflict
        if (seats.size() != ids.size()) throw new NotFoundException("One or more seats do not belong to this show");
        if (!seats.stream().allMatch(s -> s.isFreeAt(now))) throw new SeatUnavailableException();

        Instant expiresAt = now.plus(holdDuration());
        BigDecimal total = seats.stream().map(ShowSeat::getPrice).reduce(BigDecimal.ZERO, BigDecimal::add);
        Booking booking = bookings.save(Booking.held(userId, showId, total, idempotencyKey, expiresAt));

        seats.forEach(s -> s.hold(userId, booking.getId(), expiresAt));
        items.saveAll(seats.stream().map(s -> new BookingItem(booking.getId(), s.getId())).toList());
        showSeats.flush();   // run the UPDATEs now so an optimistic version conflict surfaces inside this method
        return booking;
    }

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public BookingView get(Long userId, Long bookingId) {
        return view(owned(bookings.findById(bookingId), userId, bookingId));
    }

    @Transactional(readOnly = true)
    public BookingView view(Long bookingId) {
        return view(bookings.findById(bookingId)
                .orElseThrow(() -> new NotFoundException("Booking " + bookingId + " not found")));
    }

    @Transactional(readOnly = true)
    public BookingView findByIdempotencyKey(String key) {
        return bookings.findByIdempotencyKey(key).map(this::view).orElse(null);
    }

    @Transactional(readOnly = true)
    public PageResponse<BookingView> listMine(Long userId, int page, int size) {
        var result = bookings.findByUserIdOrderByIdDesc(userId, PageRequest.of(page, Math.min(Math.max(size, 1), 50)));
        List<BookingView> views = views(result.getContent());
        return new PageResponse<>(views, result.getNumber(), result.getSize(), result.getTotalElements());
    }

    private BookingView view(Booking b) { return views(List.of(b)).get(0); }

    /** Two queries for a whole page of bookings (shows + seats), not 2N. */
    private List<BookingView> views(List<Booking> list) {
        if (list.isEmpty()) return List.of();
        Map<Long, ShowView> showById = shows.viewsByIds(list.stream().map(Booking::getShowId).distinct().toList())
                .stream().collect(Collectors.toMap(ShowView::id, Function.identity()));
        Map<Long, List<BookedSeat>> seatsByBooking = items.seatLines(list.stream().map(Booking::getId).toList())
                .stream().collect(Collectors.groupingBy(SeatLine::bookingId,
                        Collectors.mapping(BookedSeat::from, Collectors.toList())));
        return list.stream().map(b -> {
            ShowView s = showById.get(b.getShowId());
            return new BookingView(b.getId(), b.getShowId(), s.eventId(), s.eventTitle(), s.venueName(), s.venueCity(),
                    s.startsAt(), b.getStatus(), b.getTotalAmount(), b.getExpiresAt(), b.getCreatedAt(),
                    seatsByBooking.getOrDefault(b.getId(), List.of()));
        }).toList();
    }

    // ------------------------------------------------------------------ cancel / expire

    @Transactional
    public void cancel(Long userId, Long bookingId) {
        Booking b = owned(bookings.lockById(bookingId), userId, bookingId);
        if (b.getStatus() == BookingStatus.CANCELLED) return;   // repeating a cancel is harmless
        if (!b.getStatus().isActive())
            throw new ConflictException("A booking that is " + b.getStatus().name().toLowerCase() + " cannot be cancelled");
        releaseSeats(b);
        b.transitionTo(BookingStatus.CANCELLED);
        b.setExpiresAt(null);
    }

    @Transactional(readOnly = true)
    public List<Long> findExpiredIds(int limit) {
        return bookings.findExpiredIds(ACTIVE, Instant.now(), PageRequest.of(0, limit));
    }

    /** Expires one booking if it is still unpaid past its deadline. Safe to call concurrently and repeatedly. */
    @Transactional
    public boolean expire(Long bookingId) {
        Booking b = bookings.lockById(bookingId).orElse(null);
        // Re-check under the lock: a payment may have confirmed it since the sweeper listed it.
        if (b == null || !b.getStatus().isActive() || !b.isExpiredAt(Instant.now())) return false;
        releaseSeats(b);
        b.transitionTo(BookingStatus.EXPIRED);
        return true;
    }

    // ------------------------------------------------------------------ payment

    /** Read-only pre-check before we call the payment provider (no DB lock is held during that HTTP call). */
    @Transactional(readOnly = true)
    public Booking payable(Long userId, Long bookingId) {
        Booking b = owned(bookings.findById(bookingId), userId, bookingId);
        requirePayable(b);
        return b;
    }

    @Transactional(readOnly = true)
    public Payment openPayment(Long bookingId) {
        return payments.findByBookingIdOrderByIdDesc(bookingId).stream()
                .filter(p -> p.getStatus() == PaymentStatus.CREATED).findFirst().orElse(null);
    }

    /** HELD -> PAYMENT_PENDING, and the hold is extended so the user has time to finish paying. */
    @Transactional
    public Payment attachPayment(Long userId, Long bookingId, String provider, String orderId) {
        Booking b = owned(bookings.lockById(bookingId), userId, bookingId);
        requirePayable(b);
        Instant until = Instant.now().plus(Duration.ofMinutes(paymentMinutes));
        if (until.isAfter(b.getExpiresAt())) {
            b.setExpiresAt(until);
            showSeats.lockByBookingId(b.getId()).forEach(s -> s.setHeldUntil(until));
        }
        b.transitionTo(BookingStatus.PAYMENT_PENDING);
        return payments.save(new Payment(b.getId(), provider, orderId, b.getTotalAmount()));
    }

    private void requirePayable(Booking b) {
        if (!b.getStatus().isActive())
            throw new ConflictException("This booking is " + b.getStatus().name().toLowerCase() + " and cannot be paid for");
        if (b.isExpiredAt(Instant.now()))
            throw new ConflictException("Your hold on these seats has expired. Pick your seats again.");
    }

    /**
     * Applies a verified payment result. Idempotent: the payment row lock serialises duplicate
     * webhook deliveries, and a payment that is already settled is a no-op.
     * <p>
     * Also handles "the money arrived after the hold expired": if the seats are still free we honour
     * the payment and confirm; if someone else has them, the payment is flagged for refund.
     */
    @Transactional
    public PaymentOutcome applyPaymentResult(String orderId, String providerPaymentId, boolean success) {
        Payment p = payments.lockByOrderId(orderId).orElse(null);
        if (p == null) return PaymentOutcome.UNKNOWN_ORDER;
        if (p.getStatus() == PaymentStatus.CAPTURED || p.getStatus() == PaymentStatus.REFUND_PENDING)
            return PaymentOutcome.DUPLICATE;
        if (!success) {
            p.mark(PaymentStatus.FAILED);
            return PaymentOutcome.FAILED;
        }

        p.setProviderPaymentId(providerPaymentId);
        Booking b = bookings.lockById(p.getBookingId()).orElseThrow();
        boolean seated = switch (b.getStatus()) {
            case HELD, PAYMENT_PENDING, EXPIRED -> claimSeats(b);
            case CONFIRMED, CANCELLED -> false;   // paid twice, or paid after cancelling
        };

        if (seated) {
            b.transitionTo(BookingStatus.CONFIRMED);
            b.setExpiresAt(null);
            p.mark(PaymentStatus.CAPTURED);
            email(b, "Your SeatRush booking #" + b.getId() + " is confirmed",
                    "Payment of Rs. " + b.getTotalAmount() + " received. Your seats are booked. Enjoy the show!");
            return PaymentOutcome.CONFIRMED;
        }

        if (b.getStatus().isActive()) {
            releaseSeats(b);
            b.transitionTo(BookingStatus.EXPIRED);
        }
        p.mark(PaymentStatus.REFUND_PENDING);
        log.warn("Payment {} for booking {} could not be honoured (booking {}); flagged for refund",
                providerPaymentId, b.getId(), b.getStatus());
        email(b, "Refund for SeatRush booking #" + b.getId(),
                "We received your payment of Rs. " + p.getAmount() + " after your seats were released, "
                        + "so we could not complete the booking. The full amount will be refunded.");
        return PaymentOutcome.REFUND_PENDING;
    }

    // ------------------------------------------------------------------ seat helpers (caller holds the booking lock)

    /** Marks every seat of the booking BOOKED if each one is still ours or free again; otherwise changes nothing. */
    private boolean claimSeats(Booking b) {
        Instant now = Instant.now();
        List<Long> seatIds = items.findByBookingId(b.getId()).stream().map(BookingItem::getShowSeatId).toList();
        List<ShowSeat> seats = showSeats.lockByIds(seatIds);
        boolean claimable = seats.size() == seatIds.size() && seats.stream().allMatch(s ->
                (s.getStatus() == SeatStatus.HELD && b.getId().equals(s.getBookingId())) || s.isFreeAt(now));
        if (claimable) seats.forEach(s -> s.book(b.getUserId(), b.getId()));
        return claimable;
    }

    private void releaseSeats(Booking b) {
        List<ShowSeat> held = showSeats.lockByBookingId(b.getId()).stream()
                .filter(s -> s.getStatus() == SeatStatus.HELD).toList();
        held.forEach(ShowSeat::release);
        gate.release(held.stream().map(ShowSeat::getId).toList());
    }

    private void email(Booking b, String subject, String body) {
        // Same transaction as the status change: the email exists if and only if the change committed.
        users.findById(b.getUserId()).ifPresent(u -> outbox.save(new OutboxMessage(u.getEmail(), subject, body)));
    }

    private static Booking owned(java.util.Optional<Booking> found, Long userId, Long bookingId) {
        // Someone else's booking is reported as "not found" so ids cannot be probed.
        return found.filter(b -> b.getUserId().equals(userId))
                .orElseThrow(() -> new NotFoundException("Booking " + bookingId + " not found"));
    }
}
