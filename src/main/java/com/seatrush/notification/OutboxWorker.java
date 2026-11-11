package com.seatrush.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** Drains the outbox. Delivery is at-least-once: a crash between send and commit re-sends on the next run. */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxWorker {
    private static final int BATCH = 20;
    private static final int MAX_ATTEMPTS = 5;

    private final OutboxRepository outbox;
    private final EmailSender sender;

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:5000}")
    @Transactional
    public int drain() {
        List<OutboxMessage> batch = outbox.claimPending(BATCH);
        for (OutboxMessage m : batch) {
            m.setAttempts(m.getAttempts() + 1);
            try {
                sender.send(m.getRecipient(), m.getSubject(), m.getBody());
                m.setStatus("SENT");
                m.setSentAt(Instant.now());
            } catch (RuntimeException e) {
                log.warn("Email {} failed (attempt {}): {}", m.getId(), m.getAttempts(), e.getMessage());
                if (m.getAttempts() >= MAX_ATTEMPTS) m.setStatus("FAILED");
            }
        }
        return batch.size();
    }
}
