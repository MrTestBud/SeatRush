# Changelog

All notable changes to SeatRush are recorded here. Versions follow [Semantic Versioning](https://semver.org/).

## [1.0.0] - 2026-11-11

First complete release.

### Booking
- Seat holds that are all-or-nothing and expire after 5 minutes.
- Two locking strategies, selectable with `LOCK_STRATEGY`: pessimistic (`SELECT ... FOR UPDATE`) and optimistic (`@Version`).
- Redis hold gate that rejects losers of a seat race before they reach the database. Fails open if Redis is down.
- Booking state machine: `HELD → PAYMENT_PENDING → CONFIRMED | EXPIRED | CANCELLED`.
- Expiry sweeper that releases unpaid bookings.
- `Idempotency-Key` support on seat holds.

### Payments
- Test-mode payment provider (default) and a Razorpay provider.
- HMAC-signed, idempotent webhooks.
- Late-payment handling: confirm if the seats are still free, flag for refund otherwise.

### Platform
- JWT authentication with user, organizer and admin roles.
- Events, venues and shows, with automatic seat-grid and inventory generation.
- PostgreSQL full-text search.
- Transactional email outbox.
- Redis caching for event pages and rate limiting on login and seat holds.

### Web app
- Register and log in, browse and search events, live seat map, booking page with countdown and payment, booking history, host console.

### Tooling
- 25 integration tests on real PostgreSQL and Redis, including a 200-thread double-booking test.
- k6 load test script.
- Docker Compose stack (PostgreSQL, Redis, API, web) and GitHub Actions CI.

### Known limits
- The Razorpay provider has not been verified against a live account.
- Refunds are flagged and emailed, not issued automatically.

[1.0.0]: https://github.com/MrTestBud/SeatRush/releases/tag/v1.0.0
