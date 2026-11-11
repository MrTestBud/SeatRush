package com.seatrush.infra;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fixed-window rate limiter. Redis (INCR + EXPIRE) makes the limit hold across app instances;
 * if Redis is unavailable it degrades to a per-instance in-memory window rather than failing requests.
 */
@Component
@RequiredArgsConstructor
public class RateLimiter {
    private final RedisOps redis;
    private final Map<String, long[]> local = new ConcurrentHashMap<>();   // key -> {windowStart, count}

    /** @return true if the call is within {@code limit} per {@code window} for this key. */
    public boolean allow(String key, int limit, Duration window) {
        long windowId = System.currentTimeMillis() / window.toMillis();
        String redisKey = "rl:" + key + ":" + windowId;
        var count = redis.increment(redisKey);
        if (count.isPresent()) {
            if (count.get() == 1L) redis.expire(redisKey, window);
            return count.get() <= limit;
        }
        return allowLocal(key, limit, windowId);
    }

    private boolean allowLocal(String key, int limit, long windowId) {
        if (local.size() > 10_000) local.entrySet().removeIf(e -> e.getValue()[0] != windowId);
        long[] slot = local.compute(key, (k, v) ->
                (v == null || v[0] != windowId) ? new long[]{windowId, 1} : new long[]{windowId, v[1] + 1});
        return slot[1] <= limit;
    }
}
