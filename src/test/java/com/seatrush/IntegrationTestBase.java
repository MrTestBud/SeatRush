package com.seatrush;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.lifecycle.Startables;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real PostgreSQL and Redis (Testcontainers), started once and shared by every test class, so
 * Flyway migrations, row locks, full-text search and the Redis gate are all exercised for real.
 * Needs Docker running; without it the tests fail loudly rather than being silently skipped.
 * Background jobs are off: tests call the sweeper / outbox worker directly so nothing is timing-dependent.
 */
@SpringBootTest(properties = {
        "app.scheduling.enabled=false",
        "app.rate-limit.auth-per-minute=1000000",
        "app.rate-limit.hold-per-minute=1000000",
        "app.waiting-room.capacity=2",
        "spring.datasource.hikari.maximum-pool-size=20"
})
@AutoConfigureMockMvc
abstract class IntegrationTestBase {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry registry) {
        Startables.deepStart(POSTGRES, REDIS).join();   // idempotent: already-running containers are reused
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper om;
    @Autowired protected JdbcTemplate jdbc;

    protected record Account(long id, String email, String token) {
        String bearer() { return "Bearer " + token; }
    }

    protected Account account(String role) throws Exception {
        String email = UUID.randomUUID() + "@test.com";
        String body = om.writeValueAsString(Map.of(
                "name", "Test User", "email", email, "password", "Passw0rd!", "role", role));
        String json = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return new Account(((Number) JsonPath.read(json, "$.user.id")).longValue(), email, JsonPath.read(json, "$.token"));
    }

    protected String register(String role) throws Exception { return account(role).token(); }

    protected long createEvent(String token, String title, String description) throws Exception {
        String body = om.writeValueAsString(Map.of("title", title, "description", description,
                "category", "MOVIE", "language", "English", "durationMinutes", 120));
        return idOf(mvc.perform(post("/api/events").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn());
    }

    protected long createVenue(String token, int rows, int seatsPerRow) throws Exception {
        return idOf(mvc.perform(post("/api/venues").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("name", "PVR Test", "city", "Delhi",
                                "address", "Somewhere", "rows", rows, "seatsPerRow", seatsPerRow))))
                .andExpect(status().isCreated()).andReturn());
    }

    protected long createShow(String token, long eventId, long venueId, int basePrice) throws Exception {
        return idOf(mvc.perform(post("/api/events/" + eventId + "/shows").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("venueId", venueId,
                                "startsAt", Instant.now().plus(2, ChronoUnit.DAYS).toString(),
                                "basePrice", basePrice))))
                .andExpect(status().isCreated()).andReturn());
    }

    /** A fresh show with rows x seatsPerRow seats at base price 200 (rows A and B are PREMIUM at 300). */
    protected long newShow(int rows, int seatsPerRow) throws Exception {
        String org = register("ORGANIZER");
        long eventId = createEvent(org, "Event " + UUID.randomUUID(), "desc");
        return createShow(org, eventId, createVenue(org, rows, seatsPerRow), 200);
    }

    /** show_seat ids of a show, ordered by row then seat number. */
    protected List<Long> seatIds(long showId) throws Exception {
        String json = mvc.perform(get("/api/shows/" + showId + "/seats")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Number> ids = JsonPath.read(json, "$[*].showSeatId");
        return ids.stream().map(Number::longValue).toList();
    }

    protected String seatStatus(long showSeatId) {
        return jdbc.queryForObject("SELECT status FROM show_seats WHERE id = ?", String.class, showSeatId);
    }

    // ---------------------------------------------------------------- booking helpers

    protected org.springframework.test.web.servlet.ResultActions postJson(Account who, String url, Object body) throws Exception {
        return mvc.perform(post(url).header("Authorization", who.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body)));
    }

    protected org.springframework.test.web.servlet.ResultActions hold(Account who, long showId, List<Long> seats) throws Exception {
        return postJson(who, "/api/shows/" + showId + "/holds", Map.of("showSeatIds", seats));
    }

    protected long holdOk(Account who, long showId, List<Long> seats) throws Exception {
        return idOf(hold(who, showId, seats).andExpect(status().isCreated()).andReturn());
    }

    protected String startPayment(Account who, long bookingId) throws Exception {
        String json = mvc.perform(post("/api/bookings/" + bookingId + "/pay").header("Authorization", who.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.orderId");
    }

    /** Holds the seats and pays for them with the mock provider; returns the confirmed booking id. */
    protected long bookOk(Account who, long showId, List<Long> seats) throws Exception {
        long bookingId = holdOk(who, showId, seats);
        mvc.perform(post("/api/payments/mock/" + startPayment(who, bookingId) + "/complete")
                .header("Authorization", who.bearer())).andExpect(status().isOk());
        return bookingId;
    }

    protected org.springframework.test.web.servlet.ResultActions booking(Account who, long bookingId) throws Exception {
        return mvc.perform(get("/api/bookings/" + bookingId).header("Authorization", who.bearer()));
    }

    /** Pretends the clock moved past the hold deadline. */
    protected void lapse(long bookingId) {
        jdbc.update("UPDATE bookings SET expires_at = now() - interval '1 minute' WHERE id = ?", bookingId);
        jdbc.update("UPDATE show_seats SET held_until = now() - interval '1 minute' WHERE booking_id = ?", bookingId);
    }

    /** The one-time token from the newest email to this address whose link contains the given path. */
    protected String tokenFromEmail(String email, String path) {
        String body = jdbc.queryForObject(
                "SELECT body FROM outbox WHERE recipient = ? AND body LIKE ? ORDER BY id DESC LIMIT 1",
                String.class, email, "%" + path + "?token=%");
        String rest = body.substring(body.indexOf(path + "?token=") + path.length() + 7);
        return rest.split("\\s", 2)[0];
    }

    protected static long idOf(MvcResult r) throws Exception {
        return ((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.id")).longValue();
    }
}
