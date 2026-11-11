# SeatRush

A concurrency-safe event & ticket booking platform. The interesting problem: **what happens when thousands of users try to book the same last seats at once?**

Answer, proven by a test that runs on every build: 200 users hit the same seat at the same instant, **exactly one wins**, 199 get a clean `409`, and the database never holds two bookings for one seat.

**Stack:** Java 17 · Spring Boot 3 · PostgreSQL 16 · Flyway · Redis 7 · Spring Security (JWT) · React 18 + Vite · Testcontainers · Docker · k6

## Status

| Week | Scope | State |
|------|-------|-------|
| 1 | Schema, auth (JWT + roles), events/venues/shows CRUD, seat inventory generation, full-text search | ✅ |
| 2 | React frontend: register/login, browse + search, live seat map, booking + payment page, my bookings, host console | ✅ |
| 3-4 | Seat holds, double-booking prevention (pessimistic **and** optimistic), 200-thread test | ✅ |
| 5 | Redis TTL hold gate, expiry sweeper, booking state machine | ✅ |
| 6 | Payments: mock provider + Razorpay, signed idempotent webhooks, late-payment handling | ✅ (Razorpay path not exercised against a live account, see below) |
| 7 | Email queue (transactional outbox), Redis caching, rate limiting | ✅ |
| 8-10 | Load test script (k6), Docker Compose for the whole stack, CI, docs | ✅ (not deployed to a public host) |

## Run with Docker (whole stack)

```bash
docker compose up -d --build   # postgres + redis + backend + frontend
```
App: http://localhost:3000 · API: http://localhost:8080 · Swagger: http://localhost:8080/swagger-ui/index.html · Stop: `docker compose down`

## Run locally (hot reload)

```bash
docker compose up -d postgres redis   # data stores only (Docker Desktop must be running)
mvn spring-boot:run                   # API on :8080

cd frontend
npm install
npm run dev                           # UI on http://localhost:5173 (proxies /api to :8080)
```

Try it: register as **Host events** → Host → add a venue, create an event, add a show. Then (same or another account) Events → pick the show → pick seats → Hold seats → Pay. Payments run in **test mode** by default: the pay step lets you choose a successful or a failed payment, no real money involved.

Local demo accounts created while verifying the app (only in your local Docker volume; password `seatrush-demo`): `host@seatrush.local` (organizer).

## Tests

```bash
mvn verify            # needs Docker running: starts real PostgreSQL + Redis via Testcontainers
```

25 tests, three classes:

- `ApiIntegrationTest`: auth, roles, ownership, full-text search, seat inventory generation.
- `BookingFlowTest`: hold → pay → confirm; all-or-nothing holds; idempotency key; cancel; expiry sweep; duplicate webhooks; bad signatures; failed payment + retry; payment after expiry (seats free → confirmed, seats resold → refund flagged); cache eviction; rate limiter.
- `SeatRushConcurrencyTest`: **200 threads, 1 seat, exactly one winner**, for both locking strategies and through the Redis gate; plus 150 users grabbing overlapping groups of seats with no deadlock and no seat in two bookings.

Sample output from the concurrency test on a laptop (PostgreSQL in Docker):

```
[PESSIMISTIC]              200 users, 1 seat: 1 won, 199 lost in 155 ms
[OPTIMISTIC]               200 users, 1 seat: 1 won, 199 lost in  74 ms
[REDIS GATE + PESSIMISTIC] 200 users, 1 seat: 1 won, 199 lost in  18 ms
```

Load test over real HTTP (needs [k6](https://k6.io/docs/get-started/installation/)): `k6 run loadtest/seat-rush.js`. It fails unless every hold answers 201 or 409 and the number of winners equals the number of seats. The script's header explains the rate-limit setting to raise first.

## API

```
POST /api/auth/register            POST /api/auth/login           GET /api/auth/me
GET  /api/events?q=&page=&size=    GET  /api/events/{id}          GET /api/events/mine          (organizer)
POST /api/events                   PUT  /api/events/{id}          DELETE /api/events/{id}       (ORGANIZER/ADMIN, owner only)
GET  /api/venues                   POST /api/venues               (ORGANIZER/ADMIN; generates the seat grid)
POST /api/events/{id}/shows        GET  /api/events/{id}/shows    GET /api/shows/{id}
GET  /api/shows/{id}/seats         live seat map with status + tiered price

POST /api/shows/{id}/holds         {showSeatIds:[..]} + optional Idempotency-Key header → booking (HELD, 5 min)
GET  /api/bookings                 GET  /api/bookings/{id}        POST /api/bookings/{id}/cancel
POST /api/bookings/{id}/pay        opens a payment order → PAYMENT_PENDING (hold extended to 10 min)
POST /api/payments/webhook         provider → us; authenticated by HMAC-SHA256 signature, not a JWT
POST /api/payments/mock/{orderId}/complete?outcome=success|failure     test-mode checkout
```

## How double booking is prevented

A hold is one transaction: read the requested `show_seats` rows, check they are all free, mark them `HELD` with an expiry, create the booking. Two strategies do the "check then write" safely; pick one with `LOCK_STRATEGY`:

| | PESSIMISTIC (default) | OPTIMISTIC |
|---|---|---|
| How | `SELECT ... FOR UPDATE` on the seat rows | plain read, `UPDATE ... WHERE version = ?` (`@Version`) |
| Losers | wait on the row lock, then see `HELD` → 409 | fail at flush with a version conflict → 409 |
| Good at | hot seats: one round trip per loser, no wasted writes | low contention: no lock held while reading |
| Cost | lock queue on the hottest rows | every loser does the work, then throws it away |

Rows are always locked in id order, so users picking overlapping seats cannot deadlock (tested).

In front of the database sits a **Redis gate**: `SET seat:hold:<id> <user> NX PX <ttl>`. When 5,000 people click the same seat, one gets the key and goes to PostgreSQL; the rest are refused in memory without opening a transaction. Redis is only a fast path: PostgreSQL decides who owns a seat, the gate fails open if Redis is down, and a stale key can only expire, never sell a seat twice.

## Booking lifecycle

```
HELD ──pay──► PAYMENT_PENDING ──webhook: captured──► CONFIRMED
  │                 │
  ├─────────────────┴──deadline passed──► EXPIRED ──late payment, seats still free──► CONFIRMED
  └─────────────────┴──user cancels────► CANCELLED
```

- Transitions go through one method (`Booking.transitionTo`); an illegal jump throws.
- **Expiry:** a sweeper runs every 5 s and releases unpaid bookings past their deadline, each in its own short transaction. Availability does not wait for it: a lapsed hold is already bookable by the next user.
- **Webhooks are idempotent.** The handler locks the payment row, so duplicate deliveries are serialised and the second one is a no-op. `provider_payment_id` and `provider_order_id` are `UNIQUE` as a last line of defence.
- **Payment arrives after the hold expired.** If the seats are still free we honour the payment and confirm. If someone else has them, nothing is double-sold: the payment is marked `REFUND_PENDING` and the user is emailed. (Calling the provider's refund API from that state is the one piece left as a manual/ops step.)
- **Every writer locks in the same order** (payment → booking → seats), so the sweeper, a cancel and a webhook for the same booking cannot interleave or deadlock.

## Design decisions (interview notes)

- **Modular monolith, not microservices.** One deployable, clear package boundaries (`auth`, `event`, `venue`, `show`, `booking`, `payment`, `notification`, `infra`). Notification is the first thing to extract at scale, and the outbox table is already the seam.
- **PostgreSQL over MongoDB.** Booking needs multi-row ACID transactions, foreign keys and row-level locks.
- **Flyway owns the schema; Hibernate runs in `validate` mode.** No silent `ddl-auto` drift.
- **`show_seats` separate from `seats`.** `seats` is the static layout; `show_seats` is per-show inventory (hot, contended). `show_seats.booking_id` makes "release this booking's seats" one indexed lookup and makes it impossible to release a seat someone else has since taken.
- **Seat inventory generated with one `INSERT ... SELECT`** inside the show-creation transaction.
- **Full-text search via a generated `tsvector` column + GIN index**, instead of adding Elasticsearch.
- **Indexes by access pattern:** `(show_id, status)` for the seat map; partial index on `bookings(expires_at)` for live bookings only, for the sweeper.
- **Emails use a transactional outbox, not a broker.** The email row is written in the same transaction as the booking change (so it exists if and only if the change committed) and a worker drains it with `FOR UPDATE SKIP LOCKED`, which is safe with several app instances. Delivery is at-least-once. Without SMTP configured (`SPRING_MAIL_HOST`), emails are logged.
- **Caching:** event detail and search pages are cached in Redis. Search-page keys embed a generation counter, so any event write invalidates all of them with one `INCR`. The seat map is never cached.
- **Rate limiting:** fixed window in Redis (`INCR` + `EXPIRE`), per IP on login/register and per user on holds; falls back to in-memory if Redis is down.
- **The provider HTTP call is made outside any database transaction**; the booking is re-validated under lock afterwards.
- **Stateless JWT auth, BCrypt hashing, ADMIN never self-assignable.** Ownership checks live in the service layer; someone else's booking answers 404, not 403.

## Configuration

Everything has a local default; set these as environment variables (or in a `.env` next to `docker-compose.yml`) anywhere else.

| Variable | Default | Purpose |
|---|---|---|
| `JWT_SECRET` | dev value | **Change it.** HMAC key for login tokens (32+ chars) |
| `PAYMENT_WEBHOOK_SECRET` | dev value | **Change it.** Shared secret that signs payment webhooks |
| `PAYMENT_PROVIDER` | `mock` | `razorpay` to take real payments |
| `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET` | empty | Razorpay API keys; set the webhook URL to `/api/payments/webhook` with event `payment.captured` (and `payment.failed`) and the same secret |
| `LOCK_STRATEGY` | `PESSIMISTIC` | or `OPTIMISTIC` |
| `HOLD_MINUTES` | `5` | how long picked seats are reserved |
| `REDIS_ENABLED` | `true` | `false` runs without Redis (no gate/cache, in-memory rate limits) |
| `RATE_LIMIT_AUTH`, `RATE_LIMIT_HOLD` | `20`, `30` | requests per minute |
| `SPRING_MAIL_HOST` (+ `_PORT`, `_USERNAME`, `_PASSWORD`) | unset | SMTP server; unset = emails are logged |

## Known limits

- The Razorpay integration follows their Orders + webhook contract and shares all its logic with the tested mock path, but it has not been run against a live Razorpay account.
- Refunds are flagged (`REFUND_PENDING`) and emailed, not issued automatically.
- Not deployed anywhere public. `docker compose up -d --build` is the deployment unit; put it behind TLS and set the two secrets first.
