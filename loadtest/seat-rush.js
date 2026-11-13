// k6 load test: a crowd of users all go for the same few seats at once, through real HTTP.
//
//   docker compose up -d --build
//   k6 run loadtest/seat-rush.js                       (install: https://k6.io/docs/get-started/installation/)
//   k6 run -e VUS=500 -e SEATS=3 loadtest/seat-rush.js
//
// Rate limits are per IP and k6 is one IP, so start the stack with them raised:
//   RATE_LIMIT_AUTH=100000 RATE_LIMIT_HOLD=100000 docker compose up -d --build
// Add `--profile observability` and watch the run live in Grafana at http://localhost:3001.
//
// Pass criteria (see thresholds): every hold answers 201 or 409, never a 5xx; and the number of
// 201s equals the number of contested seats, i.e. no seat was sold twice.
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const VUS = Number(__ENV.VUS || 200);
const SEATS = Number(__ENV.SEATS || 1);

const won = new Counter('holds_won');
const lost = new Counter('holds_lost');
const broken = new Counter('holds_broken');

export const options = {
  scenarios: {
    rush: { executor: 'per-vu-iterations', vus: VUS, iterations: 1, maxDuration: '2m' },
  },
  thresholds: {
    holds_broken: ['count==0'],
    holds_won: [`count==${SEATS}`],
    'http_req_duration{name:hold}': ['p(95)<1000'],
  },
};

const json = { headers: { 'Content-Type': 'application/json' } };
const authed = (token) => ({ headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` } });

function signUp(role) {
  const email = `k6-${Date.now()}-${Math.random().toString(36).slice(2)}@load.test`;
  const res = http.post(`${BASE}/api/auth/register`,
    JSON.stringify({ name: 'Load Tester', email, password: 'Passw0rd!', role }), json);
  if (res.status !== 201) throw new Error(`register failed: ${res.status} ${res.body}`);
  return res.json('token');
}

// Runs once: an organizer creates a show with exactly SEATS seats, and every virtual user gets an account.
export function setup() {
  const org = authed(signUp('ORGANIZER'));
  const eventId = http.post(`${BASE}/api/events`, JSON.stringify({
    title: `Load test ${Date.now()}`, description: 'k6 seat rush', category: 'CONCERT', durationMinutes: 120,
  }), org).json('id');
  const venueId = http.post(`${BASE}/api/venues`, JSON.stringify({
    name: 'k6 Arena', city: 'Delhi', address: 'Load test lane', rows: 1, seatsPerRow: SEATS,
  }), org).json('id');
  const showId = http.post(`${BASE}/api/events/${eventId}/shows`, JSON.stringify({
    venueId, startsAt: new Date(Date.now() + 86400000).toISOString(), basePrice: 500,
  }), org).json('id');
  const seatIds = http.get(`${BASE}/api/shows/${showId}/seats`).json().map((s) => s.showSeatId);

  const tokens = [];
  for (let i = 0; i < VUS; i++) tokens.push(signUp('USER'));
  return { showId, seatIds, tokens };
}

export default function (data) {
  const token = data.tokens[__VU - 1];
  const seat = data.seatIds[(__VU - 1) % data.seatIds.length];
  const res = http.post(`${BASE}/api/shows/${data.showId}/holds`,
    JSON.stringify({ showSeatIds: [seat] }), { ...authed(token), tags: { name: 'hold' } });

  if (res.status === 201) won.add(1);
  else if (res.status === 409) lost.add(1);
  else broken.add(1);
  check(res, { 'held (201) or politely refused (409)': (r) => r.status === 201 || r.status === 409 });
}

export function teardown(data) {
  const seats = http.get(`${BASE}/api/shows/${data.showId}/seats`).json();
  const held = seats.filter((s) => s.status === 'HELD').length;
  console.log(`${VUS} users, ${SEATS} seat(s): ${held} held in the database (must equal ${SEATS})`);
}
