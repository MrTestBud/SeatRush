package com.seatrush;

import com.jayway.jsonpath.JsonPath;
import com.seatrush.payment.RefundWorker;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Everything added after 1.0: one test per feature, against real PostgreSQL and Redis. */
class FeaturesTest extends IntegrationTestBase {

    @Autowired RefundWorker refundWorker;
    @Autowired com.seatrush.booking.ExpirySweeper sweeper;
    @Autowired MeterRegistry metrics;

    private record Setup(Account org, long eventId, long venueId, long showId) {}

    /** An organizer with one event and one show. All seats REGULAR at 200 unless the maps say otherwise. */
    private Setup setup(int rows, int seatsPerRow, Map<String, Object> venueExtras, Map<String, Object> showExtras) throws Exception {
        Account org = account("ORGANIZER");
        long eventId = createEvent(org.token(), "Event " + UUID.randomUUID(), "desc");
        Map<String, Object> venue = new HashMap<>(Map.of("name", "Hall", "city", "Delhi", "address", "Somewhere",
                "rows", rows, "seatsPerRow", seatsPerRow, "premiumRows", 0));
        venue.putAll(venueExtras);
        long venueId = idOf(postJson(org, "/api/venues", venue).andExpect(status().isCreated()).andReturn());
        Map<String, Object> show = new HashMap<>(Map.of("venueId", venueId,
                "startsAt", Instant.now().plus(2, ChronoUnit.DAYS).toString(), "basePrice", 200));
        show.putAll(showExtras);
        long showId = idOf(postJson(org, "/api/events/" + eventId + "/shows", show).andExpect(status().isCreated()).andReturn());
        return new Setup(org, eventId, venueId, showId);
    }

    private Setup setup(int rows, int seatsPerRow) throws Exception { return setup(rows, seatsPerRow, Map.of(), Map.of()); }

    // ---------------------------------------------------------------- browse

    @Test
    void categoryFilter() throws Exception {
        Account org = account("ORGANIZER");
        String category = "CAT" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        long id = idOf(postJson(org, "/api/events", Map.of("title", "Quizzical Night", "description", "trivia with friends",
                "category", category.toLowerCase(), "durationMinutes", 60)).andExpect(status().isCreated()).andReturn());
        createEvent(org.token(), "Quizzical Movie", "trivia on film");   // category MOVIE

        mvc.perform(get("/api/events").param("category", category))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value((int) id))
                .andExpect(jsonPath("$.items[0].category").value(category));   // stored upper-case
        mvc.perform(get("/api/events").param("q", "trivia").param("category", category.toLowerCase()))
                .andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/events").param("q", "trivia"))
                .andExpect(jsonPath("$.total", greaterThanOrEqualTo(2)));
        mvc.perform(get("/api/events/categories")).andExpect(jsonPath("$", hasItems(category, "MOVIE")));
    }

    @Test
    void posterUploadIsValidatedAndServed() throws Exception {
        Account org = account("ORGANIZER");
        Account other = account("ORGANIZER");
        long id = createEvent(org.token(), "Poster Event", "desc");
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4};

        mvc.perform(get("/api/events/" + id)).andExpect(jsonPath("$.posterUrl", nullValue()));
        mvc.perform(get("/api/events/" + id + "/poster")).andExpect(status().isNotFound());

        // the browser says it is a PNG, but the bytes are not an image
        mvc.perform(multipart(HttpMethod.PUT, "/api/events/" + id + "/poster")
                        .file(new MockMultipartFile("file", "evil.png", "image/png", "<script>alert(1)</script>".getBytes()))
                        .header("Authorization", org.bearer()))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart(HttpMethod.PUT, "/api/events/" + id + "/poster")
                        .file(new MockMultipartFile("file", "p.png", "image/png", png)).header("Authorization", other.bearer()))
                .andExpect(status().isForbidden());

        mvc.perform(multipart(HttpMethod.PUT, "/api/events/" + id + "/poster")
                        .file(new MockMultipartFile("file", "p.png", "application/octet-stream", png)).header("Authorization", org.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.posterUrl").value("/api/events/" + id + "/poster?v=1"));
        mvc.perform(get("/api/events/" + id)).andExpect(jsonPath("$.posterUrl").value("/api/events/" + id + "/poster?v=1"));
        mvc.perform(get("/api/events/" + id + "/poster"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))      // from the magic bytes, not the upload header
                .andExpect(content().bytes(png));

        mvc.perform(delete("/api/events/" + id + "/poster").header("Authorization", org.bearer())).andExpect(status().isNoContent());
        mvc.perform(get("/api/events/" + id)).andExpect(jsonPath("$.posterUrl", nullValue()));
    }

    // ---------------------------------------------------------------- layouts, tiers, show editing, pricing

    @Test
    void venueLayoutWithThreeTiersAndAisles() throws Exception {
        Setup s = setup(3, 6, Map.of("vipRows", 1, "premiumRows", 1, "aisles", List.of(4, 2, 9)), Map.of("vipPrice", 900));

        mvc.perform(get("/api/shows/" + s.showId()))
                .andExpect(jsonPath("$.venueAisles").value("2,4"));          // sorted; 9 is beyond the row and dropped
        mvc.perform(get("/api/shows/" + s.showId() + "/seats"))
                .andExpect(jsonPath("$[?(@.row=='A')].tier", everyItem(is("VIP"))))
                .andExpect(jsonPath("$[?(@.row=='A')].price", everyItem(is(900.0))))   // explicit VIP price
                .andExpect(jsonPath("$[?(@.row=='B')].tier", everyItem(is("PREMIUM"))))
                .andExpect(jsonPath("$[?(@.row=='B')].price", everyItem(is(300.0))))   // default 1.5x
                .andExpect(jsonPath("$[?(@.row=='C')].tier", everyItem(is("REGULAR"))))
                .andExpect(jsonPath("$[?(@.row=='C')].price", everyItem(is(200.0))));
    }

    @Test
    void showCanBeRepricedRescheduledAndDeleted() throws Exception {
        Setup s = setup(1, 3);
        List<Long> seats = seatIds(s.showId());
        Account user = account("USER");
        long bookingId = holdOk(user, s.showId(), List.of(seats.get(0)));
        String newTime = Instant.now().plus(5, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString();
        Map<String, Object> update = Map.of("startsAt", newTime, "basePrice", 350);

        mvc.perform(put("/api/shows/" + s.showId()).header("Authorization", account("ORGANIZER").bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(update)))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/shows/" + s.showId()).header("Authorization", s.org().bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.basePrice").value(350))
                .andExpect(jsonPath("$.startsAt").value(newTime));

        // free seats take the new price; the seat that is already held keeps what its holder agreed to
        mvc.perform(get("/api/shows/" + s.showId() + "/seats"))
                .andExpect(jsonPath("$[?(@.status=='AVAILABLE')].price", everyItem(is(350.0))))
                .andExpect(jsonPath("$[?(@.status=='HELD')].price", everyItem(is(200.0))));
        booking(user, bookingId).andExpect(jsonPath("$.totalAmount").value(200.0));

        // a show somebody has booked cannot be deleted...
        mvc.perform(delete("/api/shows/" + s.showId()).header("Authorization", s.org().bearer())).andExpect(status().isConflict());
        // ...an untouched one can
        long empty = createShow(s.org().token(), s.eventId(), s.venueId(), 100);
        mvc.perform(delete("/api/shows/" + empty).header("Authorization", s.org().bearer())).andExpect(status().isNoContent());
        mvc.perform(get("/api/shows/" + empty)).andExpect(status().isNotFound());
    }

    @Test
    void demandPricingRaisesThePriceOfRemainingSeats() throws Exception {
        Setup s = setup(1, 10, Map.of(), Map.of("dynamicPricing", true));
        List<Long> seats = seatIds(s.showId());

        mvc.perform(get("/api/shows/" + s.showId())).andExpect(jsonPath("$.surgePercent").value(0));
        long early = holdOk(account("USER"), s.showId(), seats.subList(0, 5));      // 50% full
        mvc.perform(get("/api/shows/" + s.showId())).andExpect(jsonPath("$.surgePercent").value(10));
        mvc.perform(get("/api/shows/" + s.showId() + "/seats"))
                .andExpect(jsonPath("$[?(@.status=='AVAILABLE')].price", everyItem(is(220.0))));

        Account late = account("USER");
        long lateBooking = holdOk(late, s.showId(), seats.subList(5, 8));           // 80% full after this
        booking(late, lateBooking)
                .andExpect(jsonPath("$.totalAmount").value(660.0))                  // 3 x 220: the price they were shown
                .andExpect(jsonPath("$.seats[0].price").value(220.0));
        mvc.perform(get("/api/shows/" + s.showId())).andExpect(jsonPath("$.surgePercent").value(25));
        mvc.perform(get("/api/shows/" + s.showId() + "/seats"))
                .andExpect(jsonPath("$[?(@.status=='AVAILABLE')].price", everyItem(is(250.0))));
        assertThat(jdbc.queryForObject("SELECT total_amount FROM bookings WHERE id = ?", Double.class, early))
                .as("earlier bookings keep their price").isEqualTo(1000.0);
    }

    // ---------------------------------------------------------------- coupons

    @Test
    void couponDiscountsABookingAndCannotBeOverRedeemed() throws Exception {
        Setup s = setup(1, 4);
        List<Long> seats = seatIds(s.showId());
        Account first = account("USER");
        Account second = account("USER");

        postJson(first, "/api/events/" + s.eventId() + "/coupons", Map.of("code", "nope", "percentOff", 20, "maxUses", 1))
                .andExpect(status().isForbidden());
        postJson(s.org(), "/api/events/" + s.eventId() + "/coupons", Map.of("code", "early20", "percentOff", 20, "maxUses", 1))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.code").value("EARLY20"));
        postJson(s.org(), "/api/events/" + s.eventId() + "/coupons", Map.of("code", "EARLY20", "percentOff", 5, "maxUses", 9))
                .andExpect(status().isConflict());
        postJson(s.org(), "/api/events/" + s.eventId() + "/coupons", Map.of("code", "FREE", "percentOff", 100, "maxUses", 1))
                .andExpect(status().isBadRequest());     // capped at 90%

        long a = holdOk(first, s.showId(), seats.subList(0, 2));     // 400
        long b = holdOk(second, s.showId(), seats.subList(2, 3));    // 200
        postJson(first, "/api/bookings/" + a + "/coupon", Map.of("code", "WRONG")).andExpect(status().isBadRequest());
        postJson(first, "/api/bookings/" + a + "/coupon", Map.of("code", " early20 "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subtotal").value(400.0))
                .andExpect(jsonPath("$.discountAmount").value(80.0))
                .andExpect(jsonPath("$.totalAmount").value(320.0))
                .andExpect(jsonPath("$.couponCode").value("EARLY20"));
        // applying the same code again must not take a second use
        postJson(first, "/api/bookings/" + a + "/coupon", Map.of("code", "EARLY20")).andExpect(jsonPath("$.totalAmount").value(320.0));
        postJson(second, "/api/bookings/" + b + "/coupon", Map.of("code", "EARLY20")).andExpect(status().isConflict());   // used up

        // the first buyer walks away: their use of the coupon comes back
        postJson(first, "/api/bookings/" + a + "/cancel", Map.of()).andExpect(status().isOk());
        postJson(second, "/api/bookings/" + b + "/coupon", Map.of("code", "EARLY20"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalAmount").value(160.0));

        String orderId = startPayment(second, b);
        assertThat(jdbc.queryForObject("SELECT amount FROM payments WHERE provider_order_id = ?", Double.class, orderId))
                .as("the discounted amount is what gets charged").isEqualTo(160.0);
        postJson(second, "/api/bookings/" + b + "/coupon", Map.of("code", "EARLY20")).andExpect(status().isConflict());   // too late to change
        mvc.perform(get("/api/events/" + s.eventId() + "/coupons").header("Authorization", s.org().bearer()))
                .andExpect(jsonPath("$[0].usedCount").value(1));
    }

    // ---------------------------------------------------------------- tickets, check-in, dashboard

    @Test
    void ticketAdmitsOnce_andDashboardCountsIt() throws Exception {
        Setup s = setup(1, 4);
        List<Long> seats = seatIds(s.showId());
        Account guest = account("USER");
        long held = holdOk(guest, s.showId(), List.of(seats.get(3)));
        booking(guest, held).andExpect(jsonPath("$.ticketCode", nullValue()));       // no ticket until it is paid for

        long bookingId = bookOk(guest, s.showId(), seats.subList(0, 2));
        String json = booking(guest, bookingId).andExpect(jsonPath("$.ticketCode", startsWith("SR-" + bookingId + "-")))
                .andReturn().getResponse().getContentAsString();
        String code = JsonPath.read(json, "$.ticketCode");

        postJson(guest, "/api/checkin", Map.of("code", code)).andExpect(status().isForbidden());                 // not staff
        postJson(account("ORGANIZER"), "/api/checkin", Map.of("code", code)).andExpect(status().isForbidden());  // someone else's event
        postJson(s.org(), "/api/checkin", Map.of("code", "SR-" + bookingId + "-0000000000000000")).andExpect(status().isBadRequest());   // forged
        postJson(s.org(), "/api/checkin", Map.of("code", "SR-" + (bookingId + 1) + code.substring(code.lastIndexOf('-'))))
                .andExpect(status().isBadRequest());                                                             // signature of another booking

        postJson(s.org(), "/api/checkin", Map.of("code", code.toLowerCase()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.guestName").value("Test User"))
                .andExpect(jsonPath("$.seats", hasSize(2)));
        postJson(s.org(), "/api/checkin", Map.of("code", code)).andExpect(status().isConflict());                // second scan
        booking(guest, bookingId).andExpect(jsonPath("$.checkedInAt", notNullValue()))
                .andExpect(jsonPath("$.cancellation.allowed").value(false));

        mvc.perform(get("/api/events/" + s.eventId() + "/stats").header("Authorization", s.org().bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSeats").value(4))
                .andExpect(jsonPath("$.booked").value(2))
                .andExpect(jsonPath("$.confirmedBookings").value(1))
                .andExpect(jsonPath("$.checkedIn").value(1))
                .andExpect(jsonPath("$.revenue").value(400.0))
                .andExpect(jsonPath("$.shows[0].held").value(1))
                .andExpect(jsonPath("$.shows[0].available").value(1));
        mvc.perform(get("/api/events/" + s.eventId() + "/stats").header("Authorization", account("ORGANIZER").bearer()))
                .andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- cancellation + refunds

    @Test
    void cancellingAPaidBookingRefundsByPolicy() throws Exception {
        Setup s = setup(1, 4);
        List<Long> seats = seatIds(s.showId());
        Account user = account("USER");

        // more than 24h before the show: full refund
        long full = bookOk(user, s.showId(), List.of(seats.get(0)));
        booking(user, full).andExpect(jsonPath("$.cancellation.allowed").value(true))
                .andExpect(jsonPath("$.cancellation.refundPercent").value(100));
        postJson(user, "/api/bookings/" + full + "/cancel", Map.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.refund.status").value("REFUND_PENDING"))
                .andExpect(jsonPath("$.refund.amount").value(200.0));
        assertThat(seatStatus(seats.get(0))).as("the seat goes back on sale").isEqualTo("AVAILABLE");

        // the refund worker sends the money back, once
        assertThat(refundWorker.run()).isGreaterThanOrEqualTo(1);
        booking(user, full).andExpect(jsonPath("$.refund.status").value("REFUNDED"));
        assertThat(jdbc.queryForObject("SELECT provider_refund_id FROM payments WHERE booking_id = ?", String.class, full))
                .startsWith("rfnd_mock_");
        refundWorker.run();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE recipient = ? AND subject LIKE '%refund has been issued%'",
                Integer.class, user.email())).as("refunded exactly once").isEqualTo(1);

        // 5 hours before: half back
        long half = bookOk(user, s.showId(), List.of(seats.get(1)));
        long none = bookOk(user, s.showId(), List.of(seats.get(2)));
        jdbc.update("UPDATE shows SET starts_at = now() + interval '5 hours' WHERE id = ?", s.showId());
        postJson(user, "/api/bookings/" + half + "/cancel", Map.of())
                .andExpect(jsonPath("$.refund.amount").value(100.0));

        // 1 hour before: too late
        jdbc.update("UPDATE shows SET starts_at = now() + interval '1 hour' WHERE id = ?", s.showId());
        booking(user, none).andExpect(jsonPath("$.cancellation.allowed").value(false));
        postJson(user, "/api/bookings/" + none + "/cancel", Map.of()).andExpect(status().isConflict());
        assertThat(seatStatus(seats.get(2))).isEqualTo("BOOKED");
    }

    @Test
    void latePaymentThatCannotBeHonouredIsRefundedAutomatically() throws Exception {
        Setup s = setup(1, 1);
        List<Long> seats = seatIds(s.showId());
        Account late = account("USER");
        long lateBooking = holdOk(late, s.showId(), seats);
        String orderId = startPayment(late, lateBooking);
        lapse(lateBooking);
        sweeper.sweep();
        holdOk(account("USER"), s.showId(), seats);     // someone else takes the seat

        mvc.perform(post("/api/payments/mock/" + orderId + "/complete").header("Authorization", late.bearer()))
                .andExpect(jsonPath("$.outcome").value("REFUND_PENDING"));
        refundWorker.run();
        booking(late, lateBooking).andExpect(jsonPath("$.status").value("EXPIRED"))
                .andExpect(jsonPath("$.refund.status").value("REFUNDED"))
                .andExpect(jsonPath("$.refund.amount").value(200.0));
    }

    // ---------------------------------------------------------------- accounts

    @Test
    void emailVerification() throws Exception {
        Account user = account("USER");
        mvc.perform(get("/api/auth/me").header("Authorization", user.bearer())).andExpect(jsonPath("$.emailVerified").value(false));

        String token = tokenFromEmail(user.email(), "/verify-email");
        mvc.perform(post("/api/auth/verify-email").contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"not-a-token\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/verify-email").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("token", token))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.emailVerified").value(true));
        mvc.perform(get("/api/auth/me").header("Authorization", user.bearer())).andExpect(jsonPath("$.emailVerified").value(true));
        mvc.perform(post("/api/auth/verify-email").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("token", token))))
                .andExpect(status().isBadRequest());     // links work once
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_tokens WHERE token_hash = ?", Integer.class, token))
                .as("the raw token is never stored").isZero();
    }

    @Test
    void passwordReset() throws Exception {
        Account user = account("USER");
        String unknown = UUID.randomUUID() + "@nobody.test";

        // an unknown address gets the same answer and no email: the endpoint does not reveal who has an account
        postAnon("/api/auth/forgot-password", Map.of("email", unknown)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE recipient = ?", Integer.class, unknown)).isZero();

        postAnon("/api/auth/forgot-password", Map.of("email", user.email().toUpperCase())).andExpect(status().isOk());
        String token = tokenFromEmail(user.email(), "/reset-password");

        postAnon("/api/auth/reset-password", Map.of("token", token, "password", "short")).andExpect(status().isBadRequest());
        postAnon("/api/auth/reset-password", Map.of("token", token, "password", "BrandNewPass1")).andExpect(status().isOk());
        postAnon("/api/auth/login", Map.of("email", user.email(), "password", "Passw0rd!")).andExpect(status().isUnauthorized());
        postAnon("/api/auth/login", Map.of("email", user.email(), "password", "BrandNewPass1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.emailVerified").value(true));
        postAnon("/api/auth/reset-password", Map.of("token", token, "password", "AnotherPass2")).andExpect(status().isBadRequest());
    }

    private org.springframework.test.web.servlet.ResultActions postAnon(String url, Object body) throws Exception {
        return mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body)));
    }

    // ---------------------------------------------------------------- reviews

    @Test
    void onlyAttendeesCanReview_onePerPerson() throws Exception {
        Setup s = setup(1, 2);
        Account fan = account("USER");
        Account stranger = account("USER");
        bookOk(fan, s.showId(), List.of(seatIds(s.showId()).get(0)));
        String url = "/api/events/" + s.eventId() + "/reviews";

        mvc.perform(get(url)).andExpect(status().isOk()).andExpect(jsonPath("$.count").value(0))
                .andExpect(jsonPath("$.canReview").value(false));
        mvc.perform(put(url).header("Authorization", fan.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"rating\":5}")).andExpect(status().isConflict());            // the show has not happened yet

        jdbc.update("UPDATE shows SET starts_at = now() - interval '1 day' WHERE id = ?", s.showId());
        mvc.perform(get(url).header("Authorization", fan.bearer())).andExpect(jsonPath("$.canReview").value(true));
        mvc.perform(put(url).header("Authorization", fan.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"rating\":6}")).andExpect(status().isBadRequest());
        mvc.perform(put(url).header("Authorization", fan.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":5,\"comment\":\"Loved it\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.average").value(5.0)).andExpect(jsonPath("$.count").value(1));
        mvc.perform(put(url).header("Authorization", fan.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":3,\"comment\":\"On reflection, fine\"}"))
                .andExpect(jsonPath("$.average").value(3.0)).andExpect(jsonPath("$.count").value(1))   // edited, not duplicated
                .andExpect(jsonPath("$.myReview.comment").value("On reflection, fine"));
        mvc.perform(put(url).header("Authorization", stranger.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"rating\":1}")).andExpect(status().isConflict());            // never went
        mvc.perform(get(url)).andExpect(jsonPath("$.items[0].author").value("Test"))    // first name only
                .andExpect(jsonPath("$.items[0].mine").value(false));
    }

    // ---------------------------------------------------------------- waiting room, live seat map, metrics

    @Test
    void waitingRoomLetsBuyersInAFewAtATime() throws Exception {       // capacity is 2 in tests
        Setup s = setup(1, 5, Map.of(), Map.of("waitingRoom", true));
        List<Long> seats = seatIds(s.showId());
        Account a = account("USER"), b = account("USER"), c = account("USER"), d = account("USER");
        String url = "/api/shows/" + s.showId() + "/queue";

        hold(a, s.showId(), List.of(seats.get(0))).andExpect(status().isConflict());      // must queue first
        postJson(a, url, Map.of()).andExpect(jsonPath("$.enabled").value(true)).andExpect(jsonPath("$.admitted").value(true))
                .andExpect(jsonPath("$.admittedUntil", notNullValue()));
        postJson(b, url, Map.of()).andExpect(jsonPath("$.admitted").value(true));
        postJson(c, url, Map.of()).andExpect(jsonPath("$.admitted").value(false)).andExpect(jsonPath("$.position").value(1));
        postJson(d, url, Map.of()).andExpect(jsonPath("$.admitted").value(false)).andExpect(jsonPath("$.position").value(2));
        postJson(c, url, Map.of()).andExpect(jsonPath("$.position").value(1));            // asking again keeps your place
        hold(c, s.showId(), List.of(seats.get(1))).andExpect(status().isConflict());      // not their turn yet

        holdOk(a, s.showId(), List.of(seats.get(0)));                                     // a is done: a slot opens
        postJson(d, url, Map.of()).andExpect(jsonPath("$.admitted").value(false)).andExpect(jsonPath("$.position").value(1));
        postJson(c, url, Map.of()).andExpect(jsonPath("$.admitted").value(true));         // first come, first served
        holdOk(c, s.showId(), List.of(seats.get(1)));

        // a normal show has no queue
        postJson(a, "/api/shows/" + newShow(1, 1) + "/queue", Map.of())
                .andExpect(jsonPath("$.enabled").value(false)).andExpect(jsonPath("$.admitted").value(true));
    }

    @Test
    void seatMapStreamPushesChangesAfterCommit() throws Exception {
        Setup s = setup(1, 2);
        Setup other = setup(1, 1);
        MvcResult stream = mvc.perform(get("/api/shows/" + s.showId() + "/seats/stream"))
                .andExpect(request().asyncStarted()).andReturn();
        assertThat(stream.getResponse().getContentAsString()).doesNotContain("event:seats");

        holdOk(account("USER"), other.showId(), seatIds(other.showId()));       // a different show: not our business
        assertThat(stream.getResponse().getContentAsString()).doesNotContain("event:seats");

        holdOk(account("USER"), s.showId(), List.of(seatIds(s.showId()).get(0)));
        assertThat(stream.getResponse().getContentAsString()).contains("event:seats");
        mvc.perform(get("/api/shows/999999999/seats/stream")).andExpect(status().isNotFound());
    }

    @Test
    void holdsAreMeasured() throws Exception {
        Setup s = setup(1, 1);
        List<Long> seats = seatIds(s.showId());
        holdOk(account("USER"), s.showId(), seats);
        hold(account("USER"), s.showId(), seats).andExpect(status().isConflict());

        assertThat(metrics.find("seatrush.hold").tag("outcome", "held").timer().count()).isGreaterThanOrEqualTo(1);
        assertThat(metrics.find("seatrush.hold").tag("outcome", "conflict").timer().count()).isGreaterThanOrEqualTo(1);
    }
}
