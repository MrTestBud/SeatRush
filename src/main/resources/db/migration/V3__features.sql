-- ---------- browse: category filter + posters
CREATE INDEX idx_events_category ON events (upper(category));

ALTER TABLE events ADD COLUMN poster_version INT;   -- NULL = no poster; bumped on every upload (cache busting)
CREATE TABLE event_posters (
    event_id     BIGINT PRIMARY KEY REFERENCES events(id) ON DELETE CASCADE,
    content_type VARCHAR(50) NOT NULL,
    data         BYTEA       NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------- venues: aisles (seat numbers after which a gap is drawn, e.g. '3,7'); seats.tier may now also be 'VIP'
ALTER TABLE venues ADD COLUMN aisles VARCHAR(200) NOT NULL DEFAULT '';

-- ---------- shows: opt-in waiting room and demand-based pricing
ALTER TABLE shows
    ADD COLUMN waiting_room    BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN dynamic_pricing BOOLEAN NOT NULL DEFAULT false;

-- ---------- bookings: the price actually charged per seat (list price can move with demand), coupons, check-in
ALTER TABLE booking_items ADD COLUMN price NUMERIC(10, 2);
UPDATE booking_items bi SET price = ss.price FROM show_seats ss WHERE ss.id = bi.show_seat_id;
ALTER TABLE booking_items ALTER COLUMN price SET NOT NULL;

CREATE TABLE coupons (
    id          BIGSERIAL PRIMARY KEY,
    event_id    BIGINT      NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    code        VARCHAR(40) NOT NULL,
    percent_off INT         NOT NULL CHECK (percent_off BETWEEN 1 AND 90),
    max_uses    INT         NOT NULL CHECK (max_uses > 0),
    used_count  INT         NOT NULL DEFAULT 0,
    expires_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (event_id, code),
    CHECK (used_count >= 0 AND used_count <= max_uses)   -- the database refuses to over-redeem a coupon
);

ALTER TABLE bookings
    ADD COLUMN discount_amount NUMERIC(10, 2) NOT NULL DEFAULT 0,
    ADD COLUMN coupon_id       BIGINT REFERENCES coupons(id) ON DELETE SET NULL,
    ADD COLUMN coupon_code     VARCHAR(40),
    ADD COLUMN checked_in_at   TIMESTAMPTZ;

-- ---------- refunds
ALTER TABLE payments
    ADD COLUMN refund_amount      NUMERIC(10, 2),
    ADD COLUMN provider_refund_id VARCHAR(100),
    ADD COLUMN refund_attempts    INT NOT NULL DEFAULT 0;
CREATE INDEX idx_payments_refund_pending ON payments (id) WHERE status = 'REFUND_PENDING';

-- ---------- accounts: email verification + password reset (only a SHA-256 of the token is stored)
ALTER TABLE users ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT false;
CREATE TABLE user_tokens (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type       VARCHAR(20) NOT NULL,   -- VERIFY_EMAIL | RESET_PASSWORD
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_user_tokens_user ON user_tokens (user_id, type);

-- ---------- reviews: one per user per event
CREATE TABLE reviews (
    id         BIGSERIAL PRIMARY KEY,
    event_id   BIGINT      NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    user_id    BIGINT      NOT NULL REFERENCES users(id),
    rating     INT         NOT NULL CHECK (rating BETWEEN 1 AND 5),
    comment    VARCHAR(2000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (event_id, user_id)
);
