package com.seatrush;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Week-1 API: auth, roles, ownership, full-text search, seat inventory generation. */
class ApiIntegrationTest extends IntegrationTestBase {

    @Test
    void registerThenLoginReturnsToken() throws Exception {
        String email = UUID.randomUUID() + "@test.com";
        String reg = om.writeValueAsString(Map.of("name", "A", "email", email, "password", "Passw0rd!"));
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(reg))
                .andExpect(status().isCreated());

        String login = om.writeValueAsString(Map.of("email", email, "password", "Passw0rd!"));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(login))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token", not(emptyString())))
                .andExpect(jsonPath("$.user.role").value("USER"));
    }

    @Test
    void wrongPasswordIsUnauthorized() throws Exception {
        String email = UUID.randomUUID() + "@test.com";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content(om.writeValueAsString(Map.of("name", "A", "email", email, "password", "Passw0rd!"))));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("email", email, "password", "wrong-password"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void duplicateEmailIsConflict() throws Exception {
        String body = om.writeValueAsString(Map.of("name", "A", "email", "dup@test.com", "password", "Passw0rd!"));
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
    }

    @Test
    void regularUserCannotCreateEvent() throws Exception {
        String token = register("USER");
        String body = om.writeValueAsString(Map.of("title", "X", "category", "MOVIE", "durationMinutes", 90));
        mvc.perform(post("/api/events").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousCannotCreateEventButCanBrowse() throws Exception {
        mvc.perform(post("/api/events").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/events")).andExpect(status().isOk());
    }

    @Test
    void organizerCannotEditAnotherOrganizersEvent() throws Exception {
        String owner = register("ORGANIZER");
        String other = register("ORGANIZER");
        long id = createEvent(owner, "Owner Event", "desc");
        String body = om.writeValueAsString(Map.of("title", "Hijacked", "category", "MOVIE", "durationMinutes", 90));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/events/" + id)
                        .header("Authorization", "Bearer " + other)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void fullTextSearchFindsEventByDescriptionWord() throws Exception {
        String org = register("ORGANIZER");
        createEvent(org, "Interstellar Night", "A journey through wormholes and space");
        mvc.perform(get("/api/events").param("q", "wormholes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].title", hasItem("Interstellar Night")));
    }

    @Test
    void creatingShowGeneratesSeatInventoryWithTieredPricing() throws Exception {
        String org = register("ORGANIZER");
        long eventId = createEvent(org, "Seat Test Event", "desc");

        // 3 rows x 4 seats = 12 seats; rows A,B are PREMIUM (8 seats), row C REGULAR (4 seats)
        MvcResult v = mvc.perform(post("/api/venues").header("Authorization", "Bearer " + org)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("name", "PVR Test", "city", "Delhi",
                                "address", "Somewhere", "rows", 3, "seatsPerRow", 4))))
                .andExpect(status().isCreated()).andReturn();
        long venueId = ((Number) JsonPath.read(v.getResponse().getContentAsString(), "$.id")).longValue();

        MvcResult s = mvc.perform(post("/api/events/" + eventId + "/shows").header("Authorization", "Bearer " + org)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("venueId", venueId,
                                "startsAt", Instant.now().plus(2, ChronoUnit.DAYS).toString(),
                                "basePrice", 200))))
                .andExpect(status().isCreated()).andReturn();
        long showId = ((Number) JsonPath.read(s.getResponse().getContentAsString(), "$.id")).longValue();

        mvc.perform(get("/api/shows/" + showId + "/seats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(12)))
                .andExpect(jsonPath("$[*].status", everyItem(is("AVAILABLE"))))
                .andExpect(jsonPath("$[?(@.tier=='PREMIUM')]", hasSize(8)))
                .andExpect(jsonPath("$[?(@.row=='A')].price", everyItem(is(300.0))))
                .andExpect(jsonPath("$[?(@.row=='C')].price", everyItem(is(200.0))));
    }
}
