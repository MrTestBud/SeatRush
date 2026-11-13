# Changelog

All notable changes to SeatRush are recorded here. Versions follow [Semantic Versioning](https://semver.org/).

## [1.1.0] - Unreleased

### Added
- **Real-time seat map**: seat changes are pushed to every viewer over Server-Sent Events, shared between app instances through Redis.
- **Virtual waiting room** (per show): a fair first-come-first-served queue that admits a limited number of buyers at a time.
- **QR e-tickets and check-in**: signed ticket codes on confirmed bookings and a check-in page for organizers (camera scan or typed code).
- **Cancellation of paid bookings** with a refund policy (full, partial or none depending on time to the show).
- **Automatic refunds**: a worker issues queued refunds through the payment provider, with retries.
- **Coupons**: organizer-defined discount codes with usage caps.
- **Demand pricing** (per show): remaining seats cost more as the show fills up. The price paid is stored on the booking.
- **Three seat tiers** (VIP, Premium, Regular) priced per show, and aisles in the seat map.
- **Editing**: events can be edited from the host console; shows can be rescheduled, repriced and deleted.
- **Event posters**: image upload, validated by content.
- **Organizer dashboard**: seats sold, on hold, revenue and check-ins per show.
- **Category filter** on the events page, and an "Other" category.
- **Email verification and password reset** with single-use, expiring links.
- **Reviews and ratings** from attendees.
- **Observability**: Prometheus metrics, and an optional Prometheus + Grafana stack with a ready-made dashboard.
- **Deployment overlay**: Docker Compose file with automatic TLS (Caddy) and no exposed internal ports.

### Changed
- A late payment that cannot be honoured is now refunded automatically instead of only being flagged.
- Event categories are stored in upper case.
- Tests: 40 integration tests (was 25).

### Known limits
- The Razorpay provider, including its refund call, has not been verified against a live account.
- Camera scanning on the check-in page relies on the browser's built-in barcode detector (Chrome on Android and macOS); other browsers type the code.
- A password reset does not end sessions that are already logged in.
- Not deployed to a public host.

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
