package com.seatrush;

import com.jayway.jsonpath.JsonPath;
import com.seatrush.booking.ExpirySweeper;
import com.seatrush.infra.RateLimiter;
import com.seatrush.notification.OutboxWorker;
import com.seatrush.payment.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Hold -> pay -> confirm, plus every way that flow can go sideways. */
class BookingFlowTest extends IntegrationTestBase {

    @Autowired ExpirySweeper sweeper;
    @Autowired OutboxWorker outboxWorker;
    @Autowired PaymentService paymentService;
    @Autowired RateLimiter rateLimiter;

    // ---------------------------------------------------------------- helpers

    private ResultActions webhook(String orderId, String paymentId, String event) throws Exception {
        String body = om.writeValueAsString(Map.of("event", event, "payload", Map.of("payment",
                Map.of("entity", Map.of("id", paymentId, "order_id", orderId)))));
        return mvc.perform(post("/api/payments/webhook").contentType(MediaType.APPLICATION_JSON)
                .header("X-Razorpay-Signature", paymentService.sign(body)).content(body));
    }

    private int emailsTo(Account who) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox WHERE recipient = ? AND subject LIKE '%confirmed%'", Integer.class, who.email());
    }

    // ---------------------------------------------------------------- happy path

    @Test
    void holdPayConfirm_booksSeatsAndQueuesEmail() throws Exception {
        long showId = newShow(3, 4);
        List<Long> seats = seatIds(showId);
        Account user = account("USER");

        // A1 (premium 300) + C1 (regular 200)
        long bookingId = idOf(hold(user, showId, List.of(seats.get(0), seats.get(8)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("HELD"))
                .andExpect(jsonPath("$.totalAmount").value(500.0))
                .andExpect(jsonPath("$.expiresAt", notNullValue()))
                .andExpect(jsonPath("$.seats", hasSize(2)))
                .andReturn());

        mvc.perform(get("/api/shows/" + showId + "/seats"))
                .andExpect(jsonPath("$[?(@.status=='HELD')]", hasSize(2)));

        String orderId = startPayment(user, bookingId);
        booking(user, bookingId).andExpect(jsonPath("$.status").value("PAYMENT_PENDING"));
        // asking to pay again re-uses the open order instead of creating a second one
        assertThat(startPayment(user, bookingId)).isEqualTo(orderId);

        mvc.perform(post("/api/payments/mock/" + orderId + "/complete").header("Authorization", user.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("CONFIRMED"));

        booking(user, bookingId)
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.expiresAt", nullValue()));
        assertThat(seatStatus(seats.get(0))).isEqualTo("BOOKED");
        assertThat(seatStatus(seats.get(8))).isEqualTo("BOOKED");

        // confirmation email was written in the same transaction, and the worker delivers it
        assertThat(emailsTo(user)).isEqualTo(1);
        outboxWorker.drain();
        assertThat(jdbc.queryForObject("SELECT status FROM outbox WHERE recipient = ? AND subject LIKE '%confirmed%'", String.class, user.email()))
                .isEqualTo("SENT");

        mvc.perform(get("/api/bookings").header("Authorization", user.bearer()))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value((int) bookingId));
    }

    // ---------------------------------------------------------------- contention

    @Test
    void heldSeatCannotBeHeldBySomeoneElse_andAllOrNothing() throws Exception {
        long showId = newShow(1, 4);
        List<Long> seats = seatIds(showId);
        Account first = account("USER");
        Account second = account("USER");

        holdOk(first, showId, List.of(seats.get(0)));

        hold(second, showId, List.of(seats.get(0))).andExpect(status().isConflict());
        // one taken seat fails the whole request: the free seat next to it must NOT end up held
        hold(second, showId, List.of(seats.get(0), seats.get(1))).andExpect(status().isConflict());
        assertThat(seatStatus(seats.get(1))).isEqualTo("AVAILABLE");
        // ...and it is still bookable right away (the Redis gate key was given back)
        holdOk(second, showId, List.of(seats.get(1)));
    }

    @Test
    void holdValidation() throws Exception {
        long showId = newShow(1, 10);
        long otherShow = newShow(1, 2);
        List<Long> seats = seatIds(showId);
        Account user = account("USER");

        hold(user, showId, List.of()).andExpect(status().isBadRequest());
        hold(user, showId, seats.subList(0, 9)).andExpect(status().isBadRequest());           // more than 8 seats
        hold(user, showId, List.of(seatIds(otherShow).get(0))).andExpect(status().isNotFound()); // seat of another show
        mvc.perform(post("/api/shows/" + showId + "/holds").contentType(MediaType.APPLICATION_JSON)
                .content("{\"showSeatIds\":[1]}")).andExpect(status().isUnauthorized());
    }

    @Test
    void idempotencyKeyReturnsTheSameBooking() throws Exception {
        long showId = newShow(1, 2);
        List<Long> seats = seatIds(showId);
        Account user = account("USER");
        String key = UUID.randomUUID().toString();
        String body = om.writeValueAsString(Map.of("showSeatIds", List.of(seats.get(0))));

        long first = idOf(mvc.perform(post("/api/shows/" + showId + "/holds").header("Authorization", user.bearer())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn());
        long retry = idOf(mvc.perform(post("/api/shows/" + showId + "/holds").header("Authorization", user.bearer())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn());

        assertThat(retry).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bookings WHERE show_id = ?", Integer.class, showId)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- cancel / expiry

    @Test
    void cancelReleasesSeats() throws Exception {
        long showId = newShow(1, 2);
        List<Long> seats = seatIds(showId);
        Account user = account("USER");
        Account other = account("USER");
        long bookingId = holdOk(user, showId, seats);

        // someone else's booking looks like it does not exist
        booking(other, bookingId).andExpect(status().isNotFound());
        mvc.perform(post("/api/bookings/" + bookingId + "/cancel").header("Authorization", other.bearer()))
                .andExpect(status().isNotFound());

        mvc.perform(post("/api/bookings/" + bookingId + "/cancel").header("Authorization", user.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(seatStatus(seats.get(0))).isEqualTo("AVAILABLE");

        holdOk(other, showId, seats);   // seats are free for the next person
        mvc.perform(post("/api/bookings/" + bookingId + "/pay").header("Authorization", user.bearer()))
                .andExpect(status().isConflict());
    }

    @Test
    void expiredHoldIsSweptAndSeatsBecomeBookableAgain() throws Exception {
        long showId = newShow(1, 2);
        List<Long> seats = seatIds(showId);
        Account user = account("USER");
        Account other = account("USER");
        long bookingId = holdOk(user, showId, seats);

        assertThat(sweeper.sweep()).as("a live hold is not swept").isZero();
        lapse(bookingId);

        // even before the sweeper runs, the seat map already shows the lapsed hold as available
        mvc.perform(get("/api/shows/" + showId + "/seats"))
                .andExpect(jsonPath("$[*].status", everyItem(is("AVAILABLE"))));
        mvc.perform(post("/api/bookings/" + bookingId + "/pay").header("Authorization", user.bearer()))
                .andExpect(status().isConflict());

        assertThat(sweeper.sweep()).isGreaterThanOrEqualTo(1);
        booking(user, bookingId).andExpect(jsonPath("$.status").value("EXPIRED"));
        assertThat(seatStatus(seats.get(0))).isEqualTo("AVAILABLE");
        holdOk(other, showId, seats);
    }

    // ---------------------------------------------------------------- webhooks

    @Test
    void duplicateWebhookConfirmsOnlyOnce_andBadSignatureIsRejected() throws Exception {
        long showId = newShow(1, 2);
        Account user = account("USER");
        long bookingId = holdOk(user, showId, seatIds(showId));
        String orderId = startPayment(user, bookingId);
        String paymentId = "pay_" + UUID.randomUUID();

        mvc.perform(post("/api/payments/webhook").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Razorpay-Signature", "deadbeef")
                        .content("{\"event\":\"payment.captured\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/payments/webhook").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"event\":\"payment.captured\"}"))
                .andExpect(status().isUnauthorized());
        booking(user, bookingId).andExpect(jsonPath("$.status").value("PAYMENT_PENDING"));

        webhook(orderId, paymentId, "payment.captured").andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("CONFIRMED"));
        webhook(orderId, paymentId, "payment.captured").andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("DUPLICATE"));
        // a late "failed" for an already captured payment must not undo the booking
        webhook(orderId, paymentId, "payment.failed").andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("DUPLICATE"));

        booking(user, bookingId).andExpect(jsonPath("$.status").value("CONFIRMED"));
        assertThat(emailsTo(user)).as("exactly one confirmation email").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments WHERE booking_id = ? AND status = 'CAPTURED'",
                Integer.class, bookingId)).isEqualTo(1);
    }

    @Test
    void failedPaymentCanBeRetried() throws Exception {
        long showId = newShow(1, 1);
        Account user = account("USER");
        long bookingId = holdOk(user, showId, seatIds(showId));

        String firstOrder = startPayment(user, bookingId);
        mvc.perform(post("/api/payments/mock/" + firstOrder + "/complete?outcome=failure")
                        .header("Authorization", user.bearer()))
                .andExpect(jsonPath("$.outcome").value("FAILED"));
        booking(user, bookingId).andExpect(jsonPath("$.status").value("PAYMENT_PENDING"));

        String secondOrder = startPayment(user, bookingId);
        assertThat(secondOrder).isNotEqualTo(firstOrder);
        mvc.perform(post("/api/payments/mock/" + secondOrder + "/complete").header("Authorization", user.bearer()))
                .andExpect(jsonPath("$.outcome").value("CONFIRMED"));
        booking(user, bookingId).andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    void paymentArrivingAfterExpiry_confirmsIfSeatsAreStillFree() throws Exception {
        long showId = newShow(1, 2);
        List<Long> seats = seatIds(showId);
        Account user = account("USER");
        long bookingId = holdOk(user, showId, seats);
        String orderId = startPayment(user, bookingId);

        lapse(bookingId);
        sweeper.sweep();
        booking(user, bookingId).andExpect(jsonPath("$.status").value("EXPIRED"));
        assertThat(seatStatus(seats.get(0))).isEqualTo("AVAILABLE");

        webhook(orderId, "pay_" + UUID.randomUUID(), "payment.captured")
                .andExpect(jsonPath("$.outcome").value("CONFIRMED"));
        booking(user, bookingId).andExpect(jsonPath("$.status").value("CONFIRMED"));
        assertThat(seatStatus(seats.get(0))).isEqualTo("BOOKED");
        assertThat(seatStatus(seats.get(1))).isEqualTo("BOOKED");
    }

    @Test
    void paymentArrivingAfterExpiry_isRefundedIfSeatsWereResold() throws Exception {
        long showId = newShow(1, 2);
        List<Long> seats = seatIds(showId);
        Account late = account("USER");
        Account quick = account("USER");
        long lateBooking = holdOk(late, showId, seats);
        String orderId = startPayment(late, lateBooking);

        lapse(lateBooking);
        sweeper.sweep();
        long quickBooking = holdOk(quick, showId, List.of(seats.get(0)));   // someone else grabs one of the seats

        webhook(orderId, "pay_" + UUID.randomUUID(), "payment.captured")
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("REFUND_PENDING"));

        booking(late, lateBooking).andExpect(jsonPath("$.status").value("EXPIRED"));
        booking(quick, quickBooking).andExpect(jsonPath("$.status").value("HELD"));
        assertThat(jdbc.queryForObject("SELECT booking_id FROM show_seats WHERE id = ?", Long.class, seats.get(0)))
                .as("the seat still belongs to the user who holds it now").isEqualTo(quickBooking);
        assertThat(seatStatus(seats.get(1))).as("no partial booking").isEqualTo("AVAILABLE");
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE provider_order_id = ?", String.class, orderId))
                .isEqualTo("REFUND_PENDING");
        assertThat(jdbc.queryForObject("SELECT subject FROM outbox WHERE recipient = ? AND subject LIKE 'Refund%'", String.class, late.email()))
                .startsWith("Refund");
    }

    // ---------------------------------------------------------------- week 7: cache + rate limit

    @Test
    void cachedEventIsEvictedOnUpdate() throws Exception {
        Account org = account("ORGANIZER");
        long id = createEvent(org.token(), "Original Title", "zebracorn premiere");

        mvc.perform(get("/api/events/" + id)).andExpect(jsonPath("$.title").value("Original Title"));   // cache it
        mvc.perform(get("/api/events").param("q", "zebracorn")).andExpect(jsonPath("$.items", hasSize(1)));

        mvc.perform(put("/api/events/" + id).header("Authorization", org.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("title", "New Title", "description", "zebracorn premiere",
                                "category", "MOVIE", "durationMinutes", 90))))
                .andExpect(status().isOk());

        mvc.perform(get("/api/events/" + id)).andExpect(jsonPath("$.title").value("New Title"));
        mvc.perform(get("/api/events").param("q", "zebracorn"))
                .andExpect(jsonPath("$.items[0].title").value("New Title"));
        mvc.perform(get("/api/events/mine").header("Authorization", org.bearer()))
                .andExpect(jsonPath("$[0].title").value("New Title"));
    }

    @Test
    void rateLimiterBlocksAfterTheLimit() {
        String key = "test:" + UUID.randomUUID();
        assertThat(rateLimiter.allow(key, 2, Duration.ofMinutes(1))).isTrue();
        assertThat(rateLimiter.allow(key, 2, Duration.ofMinutes(1))).isTrue();
        assertThat(rateLimiter.allow(key, 2, Duration.ofMinutes(1))).isFalse();
        assertThat(rateLimiter.allow(key + "-other", 2, Duration.ofMinutes(1))).isTrue();
    }
}
