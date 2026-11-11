package com.seatrush.booking;

import com.seatrush.infra.RedisOps;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Redis TTL gate in front of the database: {@code SET seat:hold:<id> <user> NX PX <ttl>}.
 * When 5,000 people click the same seat, one request gets the key and goes on to PostgreSQL;
 * the other 4,999 are rejected in memory without opening a transaction or queueing on a row lock.
 * It is only a fast path. PostgreSQL still decides who owns the seat, so the gate fails open
 * if Redis is down, and a stale key can never sell a seat twice (it just expires with its TTL).
 */
@Component
public class HoldGate {
    private final RedisOps redis;
    private final boolean enabled;

    public HoldGate(RedisOps redis, @Value("${app.booking.redis-gate:true}") boolean enabled) {
        this.redis = redis;
        this.enabled = enabled;
    }

    private static String key(Long showSeatId) { return "seat:hold:" + showSeatId; }

    /** All-or-nothing: on a clash, keys already taken by this call are given back. */
    public boolean tryAcquire(Collection<Long> showSeatIds, Long userId, Duration ttl) {
        if (!enabled) return true;
        List<Long> mine = new ArrayList<>();
        for (Long id : showSeatIds) {
            var won = redis.setIfAbsent(key(id), String.valueOf(userId), ttl);
            if (won.isEmpty()) {            // Redis unavailable: let the database decide
                release(mine);
                return true;
            }
            if (!won.get()) {
                release(mine);
                return false;
            }
            mine.add(id);
        }
        return true;
    }

    public void release(Collection<Long> showSeatIds) {
        if (!enabled || showSeatIds.isEmpty()) return;
        redis.delete(showSeatIds.stream().map(HoldGate::key).toList());
    }
}
