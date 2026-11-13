package com.seatrush.booking;

import com.seatrush.infra.RedisOps;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Virtual waiting room for high-demand shows (opt-in per show).
 * <p>
 * Instead of letting 50,000 people hammer the seat map at once, buyers join a first-come-first-served
 * queue and are let in a limited number at a time. Each admission is a time-limited pass; when it is
 * used (seats held) or runs out, the next person in line gets in.
 * <p>
 * State lives in three Redis sorted sets per show, all touched by one Lua script so that
 * "check my place, admit whoever is next" is atomic even with many app instances:
 * <ul>
 *   <li>queue: waiting users, scored by join time (that is the fairness)</li>
 *   <li>admitted: users currently allowed in, scored by when their pass expires</li>
 *   <li>seen: last time each waiting user checked in, so people who closed the tab do not hold up the line</li>
 * </ul>
 * If Redis is unavailable the waiting room fails open (everyone is admitted): PostgreSQL still
 * prevents double booking, the queue only protects it from the stampede.
 */
@Component
public class WaitingRoom {
    public record Ticket(boolean admitted, int position, Instant admittedUntil) {}

    private static final Ticket OPEN = new Ticket(true, 0, null);

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> ENTER = new DefaultRedisScript<>("""
            local queue, admitted, seen = KEYS[1], KEYS[2], KEYS[3]
            local user, now, capacity, passMs, staleMs = ARGV[1], tonumber(ARGV[2]), tonumber(ARGV[3]), tonumber(ARGV[4]), tonumber(ARGV[5])

            redis.call('ZREMRANGEBYSCORE', admitted, '-inf', now)              -- passes that ran out
            local pass = redis.call('ZSCORE', admitted, user)
            if pass then return {'1', '0', pass} end

            if not redis.call('ZSCORE', queue, user) then redis.call('ZADD', queue, now, user) end
            redis.call('ZADD', seen, now, user)
            for _, gone in ipairs(redis.call('ZRANGEBYSCORE', seen, '-inf', now - staleMs)) do   -- closed the tab
                redis.call('ZREM', queue, gone)
                redis.call('ZREM', seen, gone)
            end

            local free = capacity - redis.call('ZCARD', admitted)
            if free > 0 then
                for _, nextUser in ipairs(redis.call('ZRANGE', queue, 0, free - 1)) do
                    redis.call('ZREM', queue, nextUser)
                    redis.call('ZREM', seen, nextUser)
                    redis.call('ZADD', admitted, now + passMs, nextUser)
                end
            end
            for _, key in ipairs(KEYS) do redis.call('PEXPIRE', key, 86400000) end

            pass = redis.call('ZSCORE', admitted, user)
            if pass then return {'1', '0', pass} end
            return {'0', tostring(redis.call('ZRANK', queue, user) + 1), '0'}
            """, List.class);

    private final RedisOps redis;
    private final int capacity;
    private final Duration pass;
    private final Duration stale;

    public WaitingRoom(RedisOps redis,
                       @Value("${app.waiting-room.capacity:50}") int capacity,
                       @Value("${app.waiting-room.pass-minutes:10}") long passMinutes,
                       @Value("${app.waiting-room.stale-seconds:30}") long staleSeconds) {
        this.redis = redis;
        this.capacity = capacity;
        this.pass = Duration.ofMinutes(passMinutes);
        this.stale = Duration.ofSeconds(staleSeconds);
    }

    private static List<String> keys(Long showId) {
        // {..} is a Redis Cluster hash tag: all three keys of a show land on the same node, as a script requires.
        String p = "wr:{" + showId + "}:";
        return List.of(p + "queue", p + "admitted", p + "seen");
    }

    /** Joins the queue (or keeps the caller's place) and reports where they stand. Call it repeatedly while waiting. */
    public Ticket enter(Long showId, Long userId) {
        return redis.eval(ENTER, keys(showId), String.valueOf(userId), String.valueOf(System.currentTimeMillis()),
                        String.valueOf(capacity), String.valueOf(pass.toMillis()), String.valueOf(stale.toMillis()))
                .map(r -> {
                    boolean in = "1".equals(String.valueOf(r.get(0)));
                    return new Ticket(in, Integer.parseInt(String.valueOf(r.get(1))),
                            in ? Instant.ofEpochMilli((long) Double.parseDouble(String.valueOf(r.get(2)))) : null);
                })
                .orElse(OPEN);
    }

    /** True if the user currently holds a pass (or the waiting room cannot be consulted). */
    public boolean isAdmitted(Long showId, Long userId) {
        if (!redis.isUsable()) return true;
        return redis.zscore(keys(showId).get(1), String.valueOf(userId))
                .map(until -> until > System.currentTimeMillis())
                .orElse(!redis.isUsable());   // no score: not admitted, unless Redis just failed (then fail open)
    }

    /** The pass is single-use: once seats are held, the slot goes to the next person in line. */
    public void leave(Long showId, Long userId) {
        redis.zrem(keys(showId).get(1), String.valueOf(userId));
    }
}
