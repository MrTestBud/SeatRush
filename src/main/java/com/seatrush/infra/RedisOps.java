package com.seatrush.infra;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collection;
import java.util.Optional;
import java.util.function.Function;

/**
 * Thin, fail-open wrapper around Redis. Redis is an accelerator here (hold gate, cache, rate limits);
 * PostgreSQL stays the source of truth. So if Redis is down or disabled every call returns
 * {@link Optional#empty()} and the caller carries on without it. After a failure we skip Redis
 * for a few seconds instead of paying a timeout on every request.
 */
@Slf4j
@Component
public class RedisOps {
    private static final long BACKOFF_MS = 10_000;

    private final StringRedisTemplate redis;
    private final boolean enabled;
    private volatile long downUntil = 0;

    public RedisOps(StringRedisTemplate redis, @Value("${app.redis.enabled:true}") boolean enabled) {
        this.redis = redis;
        this.enabled = enabled;
    }

    public boolean isUsable() { return enabled && System.currentTimeMillis() >= downUntil; }

    private <T> Optional<T> call(Function<StringRedisTemplate, T> fn) {
        if (!isUsable()) return Optional.empty();
        try {
            return Optional.ofNullable(fn.apply(redis));
        } catch (RuntimeException e) {
            downUntil = System.currentTimeMillis() + BACKOFF_MS;
            log.warn("Redis unavailable, continuing without it for {}s: {}", BACKOFF_MS / 1000, e.getMessage());
            return Optional.empty();
        }
    }

    /** SET key value NX PX ttl. Empty = Redis unavailable (caller should not treat that as "lost"). */
    public Optional<Boolean> setIfAbsent(String key, String value, Duration ttl) {
        return call(r -> r.opsForValue().setIfAbsent(key, value, ttl));
    }

    public Optional<String> get(String key) { return call(r -> r.opsForValue().get(key)); }

    public void set(String key, String value, Duration ttl) {
        call(r -> { r.opsForValue().set(key, value, ttl); return true; });
    }

    public void delete(Collection<String> keys) {
        if (!keys.isEmpty()) call(r -> r.delete(keys));
    }

    public void delete(String key) { call(r -> r.delete(key)); }

    public Optional<Long> increment(String key) { return call(r -> r.opsForValue().increment(key)); }

    public void expire(String key, Duration ttl) { call(r -> r.expire(key, ttl)); }
}
