package com.seatrush.show;

import com.seatrush.infra.RedisOps;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pushes "this show's seats changed" to every browser looking at that seat map (Server-Sent Events).
 * <p>
 * With several app instances a viewer may be connected to a different instance than the one that
 * handled the booking, so every change is also published on a Redis channel and re-broadcast by the
 * other instances. If Redis is unavailable the stream still works for viewers on the same instance,
 * and the page's slow polling covers the rest.
 */
@Slf4j
@Component
public class SeatStream {
    private static final String CHANNEL = "seatrush:seat-events";
    private static final long TIMEOUT_MS = Duration.ofMinutes(30).toMillis();   // browsers reconnect by themselves

    private final Map<Long, Set<SseEmitter>> subscribers = new ConcurrentHashMap<>();
    private final String instanceId = UUID.randomUUID().toString();
    private final RedisOps redis;
    private final RedisConnectionFactory connectionFactory;
    private RedisMessageListenerContainer container;

    public SeatStream(RedisOps redis, RedisConnectionFactory connectionFactory, MeterRegistry metrics) {
        this.redis = redis;
        this.connectionFactory = connectionFactory;
        Gauge.builder("seatrush.seatmap.viewers", this, SeatStream::viewers)
                .description("Open live seat-map connections on this instance").register(metrics);
    }

    public SseEmitter subscribe(Long showId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        Set<SseEmitter> set = subscribers.computeIfAbsent(showId, k -> ConcurrentHashMap.newKeySet());
        set.add(emitter);
        Runnable remove = () -> set.remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        send(showId, emitter, SseEmitter.event().comment("connected"));
        return emitter;
    }

    public int viewers() { return subscribers.values().stream().mapToInt(Set::size).sum(); }

    /** Only after the transaction commits: viewers must never be told about a change that then rolls back. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSeatsChanged(SeatsChanged event) {
        broadcast(event.showId());
        redis.publish(CHANNEL, instanceId + ":" + event.showId());
    }

    private void broadcast(Long showId) {
        Set<SseEmitter> set = subscribers.get(showId);
        if (set == null) return;
        for (SseEmitter emitter : set) send(showId, emitter, SseEmitter.event().name("seats").data(showId));
    }

    private void send(Long showId, SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (Exception e) {            // the browser went away
            Set<SseEmitter> set = subscribers.get(showId);
            if (set != null) set.remove(emitter);
            emitter.completeWithError(e);
        }
    }

    /** Keeps idle connections alive through proxies that drop silent streams. */
    @Scheduled(fixedRate = 25_000)
    public void heartbeat() {
        subscribers.forEach((showId, set) -> set.forEach(e -> send(showId, e, SseEmitter.event().comment("ping"))));
    }

    /** Started by hand (not as a bean) so that Redis being down at boot cannot stop the application from starting. */
    @EventListener(ApplicationReadyEvent.class)
    public void listenForOtherInstances() {
        if (!redis.isEnabled()) return;
        try {
            container = new RedisMessageListenerContainer();
            container.setConnectionFactory(connectionFactory);
            container.addMessageListener((message, pattern) -> {
                String[] parts = new String(message.getBody(), StandardCharsets.UTF_8).split(":", 2);
                if (parts.length == 2 && !parts[0].equals(instanceId)) {     // our own changes were already broadcast
                    try {
                        broadcast(Long.valueOf(parts[1]));
                    } catch (NumberFormatException ignored) {
                        // not one of ours
                    }
                }
            }, new ChannelTopic(CHANNEL));
            container.afterPropertiesSet();
            container.start();
        } catch (RuntimeException e) {
            container = null;
            log.warn("Live seat updates will not be shared between app instances (Redis unavailable): {}", e.getMessage());
        }
    }

    @jakarta.annotation.PreDestroy
    void stop() {
        if (container != null) {
            try {
                container.stop();
                container.destroy();
            } catch (Exception ignored) {
                // shutting down anyway
            }
        }
        subscribers.values().forEach(set -> set.forEach(SseEmitter::complete));
    }
}
