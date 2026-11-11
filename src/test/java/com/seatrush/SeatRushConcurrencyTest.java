package com.seatrush;

import com.seatrush.booking.BookingService;
import com.seatrush.booking.HoldCoordinator;
import com.seatrush.booking.LockStrategy;
import com.seatrush.booking.SeatUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The point of the project: 200 users hit "book" on the same seat at the same instant,
 * and exactly one of them may win. Proven against a real PostgreSQL for both locking strategies.
 */
class SeatRushConcurrencyTest extends IntegrationTestBase {
    private static final int USERS = 200;

    @Autowired BookingService bookingService;
    @Autowired HoldCoordinator holdCoordinator;

    /** Users are inserted directly: 200 BCrypt registrations would only slow the test down. */
    private List<Long> users(int n) {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ids.add(jdbc.queryForObject(
                    "INSERT INTO users (name, email, password_hash, role) VALUES ('Racer', ?, 'x', 'USER') RETURNING id",
                    Long.class, UUID.randomUUID() + "@race.test"));
        }
        return ids;
    }

    private record Tally(int won, int lost, List<Throwable> unexpected, long millis) {}

    /** Runs one task per user, all released by the same latch so they really do collide. */
    private Tally race(List<Callable<Void>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger won = new AtomicInteger();
        AtomicInteger lost = new AtomicInteger();
        List<Throwable> unexpected = Collections.synchronizedList(new ArrayList<>());
        List<Future<?>> futures = new ArrayList<>();
        for (Callable<Void> task : tasks) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    task.call();
                    won.incrementAndGet();
                } catch (SeatUnavailableException | OptimisticLockingFailureException e) {
                    lost.incrementAndGet();          // the two legitimate ways to lose
                } catch (Throwable t) {
                    unexpected.add(t);               // deadlocks, constraint violations, timeouts...
                }
            }));
        }
        ready.await();
        long start = System.nanoTime();
        go.countDown();
        for (Future<?> f : futures) f.get(60, TimeUnit.SECONDS);
        long millis = (System.nanoTime() - start) / 1_000_000;
        pool.shutdown();
        return new Tally(won.get(), lost.get(), unexpected, millis);
    }

    private int count(String sql, Object... args) { return jdbc.queryForObject(sql, Integer.class, args); }

    @ParameterizedTest
    @EnumSource(LockStrategy.class)
    void twoHundredUsersOneSeat_exactlyOneWins(LockStrategy strategy) throws Exception {
        long showId = newShow(1, 1);
        long seat = seatIds(showId).get(0);
        List<Callable<Void>> tasks = new ArrayList<>();
        for (Long user : users(USERS)) {
            // BookingService directly = database locking only, no Redis gate in front
            tasks.add(() -> { bookingService.hold(user, showId, List.of(seat), null, strategy); return null; });
        }

        Tally t = race(tasks);
        System.out.printf("[%s] %d users, 1 seat: %d won, %d lost in %d ms%n", strategy, USERS, t.won(), t.lost(), t.millis());

        assertThat(t.unexpected()).isEmpty();
        assertThat(t.won()).as("exactly one winner").isEqualTo(1);
        assertThat(t.lost()).isEqualTo(USERS - 1);
        assertThat(count("SELECT count(*) FROM bookings WHERE show_id = ?", showId)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM booking_items bi JOIN bookings b ON b.id = bi.booking_id WHERE b.show_id = ?", showId))
                .isEqualTo(1);
        assertThat(seatStatus(seat)).isEqualTo("HELD");
        assertThat(count("SELECT count(*) FROM show_seats ss JOIN bookings b ON b.id = ss.booking_id AND b.user_id = ss.held_by WHERE ss.id = ?", seat))
                .as("the seat points at the winner's booking").isEqualTo(1);
    }

    @Test
    void twoHundredUsersOneSeat_throughTheRedisGate_exactlyOneWins() throws Exception {
        long showId = newShow(1, 1);
        long seat = seatIds(showId).get(0);
        List<Callable<Void>> tasks = new ArrayList<>();
        for (Long user : users(USERS)) {
            tasks.add(() -> { holdCoordinator.hold(user, showId, List.of(seat), null); return null; });
        }

        Tally t = race(tasks);
        System.out.printf("[REDIS GATE + PESSIMISTIC] %d users, 1 seat: %d won, %d lost in %d ms%n", USERS, t.won(), t.lost(), t.millis());

        assertThat(t.unexpected()).isEmpty();
        assertThat(t.won()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM bookings WHERE show_id = ?", showId)).isEqualTo(1);
    }

    /**
     * Messier and closer to real life: 150 users each grab 1-3 seats out of 12, in overlapping
     * combinations and arbitrary order. Nobody may deadlock and no seat may end up in two bookings.
     */
    @ParameterizedTest
    @EnumSource(LockStrategy.class)
    void overlappingMultiSeatHolds_neverDoubleBookAndNeverDeadlock(LockStrategy strategy) throws Exception {
        long showId = newShow(2, 6);
        List<Long> seats = seatIds(showId);
        Random random = new Random(42);
        List<Callable<Void>> tasks = new ArrayList<>();
        for (Long user : users(150)) {
            List<Long> pick = new ArrayList<>(seats);
            Collections.shuffle(pick, random);
            List<Long> wanted = List.copyOf(pick.subList(0, 1 + random.nextInt(3)));
            tasks.add(() -> { bookingService.hold(user, showId, wanted, null, strategy); return null; });
        }

        Tally t = race(tasks);
        System.out.printf("[%s] 150 users, 12 seats, overlapping picks: %d won, %d lost in %d ms%n", strategy, t.won(), t.lost(), t.millis());

        assertThat(t.unexpected()).as("no deadlocks or constraint errors").isEmpty();
        assertThat(t.won()).isGreaterThanOrEqualTo(1);
        int held = count("SELECT count(*) FROM show_seats WHERE show_id = ? AND status = 'HELD'", showId);
        int items = count("SELECT count(*) FROM booking_items bi JOIN bookings b ON b.id = bi.booking_id WHERE b.show_id = ?", showId);
        assertThat(items).as("every booked item is a distinct held seat (no seat sold twice)").isEqualTo(held);
        assertThat(count("""
                SELECT count(*) FROM (SELECT bi.show_seat_id FROM booking_items bi JOIN bookings b ON b.id = bi.booking_id
                                      WHERE b.show_id = ? GROUP BY bi.show_seat_id HAVING count(*) > 1) dup""", showId))
                .isZero();
        assertThat(count("SELECT count(*) FROM bookings WHERE show_id = ?", showId)).isEqualTo(t.won());
    }
}
