package com.seatrush.notification;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OutboxRepository extends JpaRepository<OutboxMessage, Long> {

    /**
     * Claims a batch of pending messages. SKIP LOCKED lets several app instances drain the queue
     * in parallel without ever picking the same row (and without blocking each other).
     */
    @Query(value = "SELECT * FROM outbox WHERE status = 'PENDING' ORDER BY id LIMIT :batch FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<OutboxMessage> claimPending(@Param("batch") int batch);

    List<OutboxMessage> findByRecipientOrderById(String recipient);
}
