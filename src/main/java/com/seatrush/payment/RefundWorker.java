package com.seatrush.payment;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Background job: drains the refund queue, one transaction per refund (see {@link RefundProcessor}). */
@Component
@RequiredArgsConstructor
public class RefundWorker {
    private static final int MAX_PER_RUN = 50;

    private final RefundProcessor processor;

    @Scheduled(fixedDelayString = "${app.payment.refund-interval-ms:10000}")
    public int run() {
        int done = 0;
        while (done < MAX_PER_RUN && processor.refundOne()) done++;
        return done;
    }
}
