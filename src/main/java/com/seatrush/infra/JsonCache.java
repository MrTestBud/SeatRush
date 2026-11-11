package com.seatrush.infra;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Supplier;

/** Read-through JSON cache on Redis. A cache miss, a Redis outage and bad JSON all just fall back to the loader. */
@Component
@RequiredArgsConstructor
public class JsonCache {
    private final RedisOps redis;
    private final ObjectMapper om;

    public <T> T get(String key, TypeReference<T> type, Duration ttl, Supplier<T> loader) {
        var cached = redis.get(key);
        if (cached.isPresent()) {
            try {
                return om.readValue(cached.get(), type);
            } catch (JsonProcessingException ignored) {
                // unreadable entry (e.g. shape changed between deploys): reload below
            }
        }
        T value = loader.get();
        try {
            redis.set(key, om.writeValueAsString(value), ttl);
        } catch (JsonProcessingException ignored) {
            // not cacheable; still return the value
        }
        return value;
    }

    public void evict(String key) { redis.delete(key); }

    /**
     * A counter used as a cache "generation". Keys embed it, so bumping it invalidates every
     * derived entry at once (e.g. all cached search pages) without scanning for keys.
     */
    public long generation(String name) {
        return redis.get(name).map(Long::parseLong).orElse(0L);
    }

    public void bumpGeneration(String name) { redis.increment(name); }
}
