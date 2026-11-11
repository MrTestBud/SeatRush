CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    name          VARCHAR(100) NOT NULL,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE venues (
    id      BIGSERIAL PRIMARY KEY,
    name    VARCHAR(150) NOT NULL,
    city    VARCHAR(100) NOT NULL,
    address VARCHAR(255) NOT NULL
);

-- Physical seats of a venue (static layout)
CREATE TABLE seats (
    id          BIGSERIAL PRIMARY KEY,
    venue_id    BIGINT      NOT NULL REFERENCES venues(id) ON DELETE CASCADE,
    row_label   VARCHAR(5)  NOT NULL,
    seat_number INT         NOT NULL,
    tier        VARCHAR(20) NOT NULL,
    UNIQUE (venue_id, row_label, seat_number)
);

CREATE TABLE events (
    id               BIGSERIAL PRIMARY KEY,
    organizer_id     BIGINT       NOT NULL REFERENCES users(id),
    title            VARCHAR(200) NOT NULL,
    description      TEXT,
    category         VARCHAR(50)  NOT NULL,
    language         VARCHAR(50),
    duration_minutes INT          NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    search_vector    tsvector GENERATED ALWAYS AS (
        to_tsvector('english', coalesce(title, '') || ' ' || coalesce(description, ''))
    ) STORED
);
CREATE INDEX idx_events_search ON events USING GIN (search_vector);
CREATE INDEX idx_events_organizer ON events (organizer_id);

CREATE TABLE shows (
    id         BIGSERIAL PRIMARY KEY,
    event_id   BIGINT         NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    venue_id   BIGINT         NOT NULL REFERENCES venues(id),
    starts_at  TIMESTAMPTZ    NOT NULL,
    base_price NUMERIC(10, 2) NOT NULL
);
CREATE INDEX idx_shows_event ON shows (event_id, starts_at);

-- Per-show seat inventory. This is the table the concurrency story revolves around.
CREATE TABLE show_seats (
    id         BIGSERIAL PRIMARY KEY,
    show_id    BIGINT         NOT NULL REFERENCES shows(id) ON DELETE CASCADE,
    seat_id    BIGINT         NOT NULL REFERENCES seats(id),
    status     VARCHAR(20)    NOT NULL DEFAULT 'AVAILABLE',  -- AVAILABLE | HELD | BOOKED
    price      NUMERIC(10, 2) NOT NULL,
    held_by    BIGINT REFERENCES users(id),
    held_until TIMESTAMPTZ,
    version    BIGINT         NOT NULL DEFAULT 0,             -- optimistic locking
    UNIQUE (show_id, seat_id)
);
CREATE INDEX idx_show_seats_show_status ON show_seats (show_id, status);
CREATE INDEX idx_show_seats_expiry ON show_seats (held_until) WHERE status = 'HELD';

-- Booking tables are created now so the schema is stable; logic arrives in weeks 3-6.
CREATE TABLE bookings (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT         NOT NULL REFERENCES users(id),
    show_id         BIGINT         NOT NULL REFERENCES shows(id),
    status          VARCHAR(20)    NOT NULL,  -- HELD | PAYMENT_PENDING | CONFIRMED | EXPIRED | CANCELLED
    total_amount    NUMERIC(10, 2) NOT NULL,
    idempotency_key VARCHAR(100) UNIQUE,
    expires_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE INDEX idx_bookings_user ON bookings (user_id);

CREATE TABLE booking_items (
    id           BIGSERIAL PRIMARY KEY,
    booking_id   BIGINT NOT NULL REFERENCES bookings(id) ON DELETE CASCADE,
    show_seat_id BIGINT NOT NULL REFERENCES show_seats(id)
);

CREATE TABLE payments (
    id                  BIGSERIAL PRIMARY KEY,
    booking_id          BIGINT         NOT NULL REFERENCES bookings(id),
    provider_payment_id VARCHAR(100) UNIQUE,   -- UNIQUE makes webhook processing idempotent
    status              VARCHAR(20)    NOT NULL,
    amount              NUMERIC(10, 2) NOT NULL,
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT now()
);
