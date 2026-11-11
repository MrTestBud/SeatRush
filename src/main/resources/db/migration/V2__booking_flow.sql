-- Which booking currently owns a held/booked seat. Makes "release this booking's seats" a
-- single indexed lookup and makes it impossible to release a seat someone else re-held.
ALTER TABLE show_seats ADD COLUMN booking_id BIGINT REFERENCES bookings(id) ON DELETE SET NULL;
CREATE INDEX idx_show_seats_booking ON show_seats (booking_id) WHERE booking_id IS NOT NULL;

ALTER TABLE booking_items ADD CONSTRAINT uq_booking_items UNIQUE (booking_id, show_seat_id);

-- The expiry sweeper only ever looks at live bookings, so index only those.
CREATE INDEX idx_bookings_expiry ON bookings (expires_at) WHERE status IN ('HELD', 'PAYMENT_PENDING');
CREATE INDEX idx_bookings_show ON bookings (show_id);

ALTER TABLE payments
    ADD COLUMN provider          VARCHAR(20)  NOT NULL DEFAULT 'MOCK',
    ADD COLUMN provider_order_id VARCHAR(100) UNIQUE,
    ADD COLUMN updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now();
CREATE INDEX idx_payments_booking ON payments (booking_id);

-- Transactional outbox: rows are written in the same transaction as the business change,
-- and a worker drains them with FOR UPDATE SKIP LOCKED. No email is lost or sent for a rolled-back booking.
CREATE TABLE outbox (
    id         BIGSERIAL PRIMARY KEY,
    recipient  VARCHAR(255) NOT NULL,
    subject    VARCHAR(255) NOT NULL,
    body       TEXT         NOT NULL,
    status     VARCHAR(20)  NOT NULL DEFAULT 'PENDING',  -- PENDING | SENT | FAILED
    attempts   INT          NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sent_at    TIMESTAMPTZ
);
CREATE INDEX idx_outbox_pending ON outbox (id) WHERE status = 'PENDING';
