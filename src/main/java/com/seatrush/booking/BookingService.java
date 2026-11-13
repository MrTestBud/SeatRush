package com.seatrush.booking;

import com.seatrush.booking.BookingDtos.BookedSeat;
import com.seatrush.booking.BookingDtos.BookingView;
import com.seatrush.booking.BookingDtos.CancellationQuote;
import com.seatrush.booking.BookingDtos.CheckInResult;
import com.seatrush.booking.BookingDtos.RefundInfo;
import com.seatrush.booking.BookingDtos.SeatLine;
import com.seatrush.common.BadRequestException;
import com.seatrush.common.ConflictException;
import com.seatrush.common.NotFoundException;
import com.seatrush.common.PageResponse;
import com.seatrush.config.AuthUser;
import com.seatrush.coupon.Coupon;
import com.seatrush.coupon.CouponRepository;
import com.seatrush.event.EventService;
import com.seatrush.notification.OutboxMessage;
import com.seatrush.notification.OutboxRepository;
import com.seatrush.payment.Payment;
import com.seatrush.payment.PaymentRepository;
import com.seatrush.payment.PaymentStatus;
import com.seatrush.show.PricingService;
import com.seatrush.show.SeatStatus;
import com.seatrush.show.SeatsChanged;
import com.seatrush.show.Show;
import com.seatrush.show.ShowDtos.ShowView;
import com.seatrush.show.ShowRepository;
import com.seatrush.show.ShowSeat;
import com.seatrush.show.ShowSeatRepository;
import com.seatrush.user.User;
import com.seatrush.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private final CouponRepository coupons;
    private final UserRepository users;
    private final OutboxRepository outbox;
    private final EventService events;
    private final PricingService pricing;
    private final RefundPolicy refundPolicy;
    private final TicketCodes tickets;
    private final HoldGate gate;
    private final ApplicationEventPublisher publisher;

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
        int surge = pricing.surgePercent(show);   // demand pricing, read before we take any lock

        List<ShowSeat> seats = strategy == LockStrategy.PESSIMISTIC
                ? showSeats.lockForHold(showId, ids)     // losers wait here, then fail the check below
                : showSeats.findForHold(showId, ids);    // losers fail at flush() with a version conflict
        if (seats.size() != ids.size()) throw new NotFoundException("One or more seats do not belong to this show");
        if (!seats.stream().allMatch(s -> s.isFreeAt(now))) throw new SeatUnavailableException();

        Instant expiresAt = now.plus(holdDuration());
        Map<Long, BigDecimal> charged = seats.stream()
                .collect(Collectors.toMap(ShowSeat::getId, s -> PricingService.apply(s.getPrice(), surge)));
        BigDecimal total = charged.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        Booking booking = bookings.save(Booking.held(userId, showId, total, idempotencyKey, expiresAt));

        seats.forEach(s -> s.hold(userId, booking.getId(), expiresAt));
        items.saveAll(seats.stream().map(s -> new BookingItem(booking.getId(), s.getId(), charged.get(s.getId()))).toList());
        showSeats.flush();   // run the UPDATEs now so an optimistic version conflict surfaces inside this method
        publisher.publishEvent(new SeatsChanged(showId));
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

    /** Three queries for a whole page of bookings (shows, seats, refunds), not 3N. */
    private List<BookingView> views(List<Booking> list) {
        if (list.isEmpty()) return List.of();
        List<Long> ids = list.stream().map(Booking::getId).toList();
        Map<Long, ShowView> showById = shows.viewsByIds(list.stream().map(Booking::getShowId).distinct().toList())
                .stream().collect(Collectors.toMap(ShowView::id, Function.identity()));
        Map<Long, List<BookedSeat>> seatsByBooking = items.seatLines(ids)
                .stream().collect(Collectors.groupingBy(SeatLine::bookingId,
                        Collectors.mapping(BookedSeat::from, Collectors.toList())));
        Map<Long, Payment> refundByBooking = payments.findByBookingIdInAndRefundAmountIsNotNull(ids).stream()
                .collect(Collectors.toMap(Payment::getBookingId, Function.identity(),
                        (a, b) -> a.getId() > b.getId() ? a : b));
        Instant now = Instant.now();

        return list.stream().map(b -> {
            ShowView s = showById.get(b.getShowId());
            boolean confirmed = b.getStatus() == BookingStatus.CONFIRMED;
            CancellationQuote quote = confirmed
                    ? refundPolicy.quote(s.startsAt(), b.getTotalAmount(), b.getCheckedInAt() != null, now) : null;
            Payment refund = refundByBooking.get(b.getId());
            return new BookingView(b.getId(), b.getShowId(), s.eventId(), s.eventTitle(), s.venueName(), s.venueCity(),
                    s.startsAt(), b.getStatus(), b.subtotal(), b.getDiscountAmount(), b.getCouponCode(),
                    b.getTotalAmount(), b.getExpiresAt(), b.getCreatedAt(),
                    confirmed ? tickets.codeFor(b.getId()) : null, b.getCheckedInAt(), quote,
                    refund == null ? null : new RefundInfo(refund.getRefundAmount(), refund.getStatus().name()),
                    seatsByBooking.getOrDefault(b.getId(), List.of()));
        }).toList();
    }

    // ------------------------------------------------------------------ coupons

    /** Applies a discount code to a held booking. Only before payment starts: the order amount is fixed after that. */
    @Transactional
    public void applyCoupon(Long userId, Long bookingId, String rawCode) {
        Booking b = owned(bookings.lockById(bookingId), userId, bookingId);
        requireDiscountable(b);
        Show show = shows.findById(b.getShowId()).orElseThrow();
        String code = rawCode.trim().toUpperCase();
        Coupon coupon = coupons.findByEventIdAndCode(show.getEventId(), code)
                .filter(c -> !c.isExpiredAt(Instant.now()))
                .orElseThrow(() -> new BadRequestException("That code is not valid for this event"));
        if (coupon.getId().equals(b.getCouponId())) return;   // already applied: nothing to do

        releaseCoupon(b);
        if (coupons.redeem(coupon.getId()) == 0)
            throw new ConflictException("That code has been fully redeemed");
        BigDecimal discount = b.subtotal().multiply(BigDecimal.valueOf(coupon.getPercentOff()))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        b.applyCoupon(coupon.getId(), coupon.getCode(), discount);
    }

    @Transactional
    public void removeCoupon(Long userId, Long bookingId) {
        Booking b = owned(bookings.lockById(bookingId), userId, bookingId);
        requireDiscountable(b);
        releaseCoupon(b);
    }

    private void requireDiscountable(Booking b) {
        if (b.getStatus() != BookingStatus.HELD)
            throw new ConflictException("Discount codes can only be changed before payment starts");
        if (b.isExpiredAt(Instant.now()))
            throw new ConflictException("Your hold on these seats has expired. Pick your seats again.");
    }

    /** Gives the coupon use back (the booking was abandoned, or the code is being swapped). */
    private void releaseCoupon(Booking b) {
        if (b.getCouponId() != null) coupons.release(b.getCouponId());
        b.removeCoupon();
    }

    // ------------------------------------------------------------------ cancel / expire

    /**
     * Cancels a booking. An unpaid one simply gives the seats back. A paid one gives the seats
     * back too and queues a refund according to {@link RefundPolicy}.
     */
    @Transactional
    public void cancel(Long userId, Long bookingId) {
        Booking b = owned(bookings.lockById(bookingId), userId, bookingId);
        switch (b.getStatus()) {
            case CANCELLED -> { }   // repeating a cancel is harmless
            case HELD, PAYMENT_PENDING -> {
                releaseSeats(b);
                if (b.getCouponId() != null) coupons.release(b.getCouponId());   // discount stays on record, the use goes back
                b.transitionTo(BookingStatus.CANCELLED);
                b.setExpiresAt(null);
            }
            case CONFIRMED -> cancelConfirmed(b);
            case EXPIRED -> throw new ConflictException("This booking has already expired");
        }
    }

    private void cancelConfirmed(Booking b) {
        Show show = shows.findById(b.getShowId()).orElseThrow();
        CancellationQuote quote = refundPolicy.quote(show.getStartsAt(), b.getTotalAmount(), b.getCheckedInAt() != null, Instant.now());
        if (!quote.allowed()) throw new ConflictException(quote.note());

        releaseSeats(b);
        b.transitionTo(BookingStatus.CANCELLED);
        payments.findByBookingIdOrderByIdDesc(b.getId()).stream()
                .filter(p -> p.getStatus() == PaymentStatus.CAPTURED).findFirst()
                .ifPresent(p -> p.requestRefund(quote.refundAmount()));
        email(b, "Your SeatRush booking #" + b.getId() + " was cancelled",
                "Your seats have been released. " + (quote.refundAmount().signum() > 0
                        ? "A refund of Rs. " + quote.refundAmount() + " (" + quote.refundPercent() + "%) is being processed."
                        : "No refund is due for this booking."));
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
        if (b.getCouponId() != null) coupons.release(b.getCouponId());
        b.transitionTo(BookingStatus.EXPIRED);
        return true;
    }

    // ------------------------------------------------------------------ check-in

    /**
     * Admits a ticket at the door. The code's signature is checked first (no database needed to
     * reject a forgery); the booking row lock then makes sure one ticket admits one party, once.
     */
    @Transactional
    public CheckInResult checkIn(AuthUser staff, String code) {
        long bookingId = tickets.verify(code).orElseThrow(() -> new BadRequestException("This is not a valid SeatRush ticket"));
        Booking b = bookings.lockById(bookingId).orElseThrow(() -> new BadRequestException("This is not a valid SeatRush ticket"));
        ShowView show = shows.viewsByIds(List.of(b.getShowId())).get(0);
        events.owned(staff, show.eventId());   // only this event's organizer (or an admin) may admit

        if (b.getStatus() != BookingStatus.CONFIRMED)
            throw new ConflictException("This booking is " + b.getStatus().name().toLowerCase() + ", not a valid ticket");
        if (b.getCheckedInAt() != null)
            throw new ConflictException("Already checked in at " + b.getCheckedInAt());
        b.checkIn(Instant.now());

        List<String> seats = items.seatLines(List.of(b.getId())).stream().map(l -> l.row() + l.number()).toList();
        String guest = users.findById(b.getUserId()).map(User::getName).orElse("Guest");
        return new CheckInResult(b.getId(), show.eventTitle(), show.startsAt(), guest, seats, b.getCheckedInAt());
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
    public Payment attachPayment(Long userId, Long bookingId, String provider, String orderId, BigDecimal orderAmount) {
        Booking b = owned(bookings.lockById(bookingId), userId, bookingId);
        requirePayable(b);
        // The order was opened before we took the lock; a coupon applied in between would make it the wrong amount.
        if (orderAmount.compareTo(b.getTotalAmount()) != 0)
            throw new ConflictException("The booking total changed while payment was starting. Try again.");
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
     * the payment and confirm; if someone else has them, the payment is queued for refund.
     */
    @Transactional
    public PaymentOutcome applyPaymentResult(String orderId, String providerPaymentId, boolean success) {
        Payment p = payments.lockByOrderId(orderId).orElse(null);
        if (p == null) return PaymentOutcome.UNKNOWN_ORDER;
        if (p.getStatus() != PaymentStatus.CREATED && p.getStatus() != PaymentStatus.FAILED)
            return PaymentOutcome.DUPLICATE;      // already captured / refunded: nothing more to do
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
            publisher.publishEvent(new SeatsChanged(b.getShowId()));
            email(b, "Your SeatRush booking #" + b.getId() + " is confirmed",
                    "Payment of Rs. " + b.getTotalAmount() + " received. Your seats are booked. Enjoy the show!\n"
                            + "Ticket code: " + tickets.codeFor(b.getId()));
            return PaymentOutcome.CONFIRMED;
        }

        if (b.getStatus().isActive()) {
            releaseSeats(b);
            b.transitionTo(BookingStatus.EXPIRED);
        }
        p.requestRefund(p.getAmount());
        log.warn("Payment {} for booking {} could not be honoured (booking {}); queued for refund",
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
        List<Long> seatIds = items.findByBookingId(b.getId()).stream().map(BookingItem::getShowSeatId)
                .sorted(Comparator.naturalOrder()).toList();
        List<ShowSeat> seats = showSeats.lockByIds(seatIds);
        boolean claimable = seats.size() == seatIds.size() && seats.stream().allMatch(s ->
                (s.getStatus() == SeatStatus.HELD && b.getId().equals(s.getBookingId())) || s.isFreeAt(now));
        if (claimable) seats.forEach(s -> s.book(b.getUserId(), b.getId()));
        return claimable;
    }

    /** Frees every seat this booking still owns, whether it was only held or already booked. */
    private void releaseSeats(Booking b) {
        List<ShowSeat> owned = showSeats.lockByBookingId(b.getId());
        owned.forEach(ShowSeat::release);
        gate.release(owned.stream().map(ShowSeat::getId).toList());
        publisher.publishEvent(new SeatsChanged(b.getShowId()));
    }

    private void email(Booking b, String subject, String body) {
        // Same transaction as the status change: the email exists if and only if the change committed.
        users.findById(b.getUserId()).ifPresent(u -> outbox.save(new OutboxMessage(u.getEmail(), subject, body)));
    }

    private static Booking owned(Optional<Booking> found, Long userId, Long bookingId) {
        // Someone else's booking is reported as "not found" so ids cannot be probed.
        return found.filter(b -> b.getUserId().equals(userId))
                .orElseThrow(() -> new NotFoundException("Booking " + bookingId + " not found"));
    }
}
