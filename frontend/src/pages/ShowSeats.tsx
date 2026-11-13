import { Fragment, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { api, ApiError, errorMessage, rupees, showTime, type QueueStatus, type Seat, type SeatTier, type Show } from '../api';

const MAX_SEATS = 8;
const FALLBACK_REFRESH_MS = 20000;   // safety net only: changes normally arrive over the live stream
const QUEUE_POLL_MS = 3000;

const TIER_LABEL: Record<SeatTier, string> = { VIP: 'VIP', PREMIUM: 'Premium', REGULAR: 'Regular' };

export default function ShowSeats() {
  const showId = Number(useParams().id);
  const navigate = useNavigate();
  const [show, setShow] = useState<Show | null>(null);
  const [seats, setSeats] = useState<Seat[] | null>(null);
  const [queue, setQueue] = useState<QueueStatus | null>(null);
  const [picked, setPicked] = useState<Set<number>>(new Set());
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [live, setLive] = useState(false);
  // One key per selection: a double click sends the same key twice and gets the same booking back.
  const idempotencyKey = useRef(crypto.randomUUID());

  const admitted = queue?.admitted ?? false;

  const loadSeats = useCallback(async () => {
    const [freshSeats, freshShow] = await Promise.all([api.seats(showId), api.show(showId)]);
    setSeats(freshSeats);
    setShow(freshShow);   // demand pricing may have moved
  }, [showId]);

  // Waiting room: join the line (or find out there is none), then keep our place until we are let in.
  useEffect(() => {
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout>;
    const check = () => {
      api.queue(showId)
        .then((q) => {
          if (cancelled) return;
          setQueue(q);
          if (!q.admitted) timer = setTimeout(check, QUEUE_POLL_MS);
        })
        .catch((e) => { if (!cancelled) setError(errorMessage(e)); });
    };
    setQueue(null);
    check();
    return () => { cancelled = true; clearTimeout(timer); };
  }, [showId]);

  // Seat map: load once we are in, then follow the live stream (other people are booking while you look).
  useEffect(() => {
    if (!admitted) return;
    loadSeats().catch((e) => setError(errorMessage(e)));
    const refresh = () => { loadSeats().catch(() => undefined); };
    const stream = new EventSource(`/api/shows/${showId}/seats/stream`);
    stream.addEventListener('seats', refresh);
    stream.onopen = () => setLive(true);
    stream.onerror = () => setLive(false);          // the browser reconnects on its own
    const timer = setInterval(refresh, FALLBACK_REFRESH_MS);
    return () => { stream.close(); clearInterval(timer); setLive(false); };
  }, [showId, admitted, loadSeats]);

  // If someone else took a seat we had selected, drop it and say so.
  useEffect(() => {
    if (!seats) return;
    const free = new Set(seats.filter((s) => s.status === 'AVAILABLE').map((s) => s.showSeatId));
    const kept = [...picked].filter((id) => free.has(id));
    if (kept.length === picked.size) return;
    setPicked(new Set(kept));
    setNotice('Some seats you picked were just taken by someone else, so we removed them from your selection.');
    idempotencyKey.current = crypto.randomUUID();
  }, [seats]); // eslint-disable-line react-hooks/exhaustive-deps

  const rows = useMemo(() => {
    const byRow = new Map<string, Seat[]>();
    for (const s of seats ?? []) byRow.set(s.row, [...(byRow.get(s.row) ?? []), s]);
    return [...byRow.entries()];
  }, [seats]);

  const aisles = useMemo(
    () => new Set((show?.venueAisles ?? '').split(',').filter(Boolean).map(Number)),
    [show?.venueAisles],
  );

  // What a seat of each tier costs right now (available seats carry the current price).
  const tierPrices = useMemo(() => {
    const found: Partial<Record<SeatTier, number>> = {};
    for (const s of seats ?? []) {
      if (found[s.tier] === undefined || s.status === 'AVAILABLE') found[s.tier] = s.price;
    }
    return (['VIP', 'PREMIUM', 'REGULAR'] as SeatTier[]).filter((t) => found[t] !== undefined).map((t) => [t, found[t]!] as const);
  }, [seats]);

  const chosen = (seats ?? []).filter((s) => picked.has(s.showSeatId));
  const total = chosen.reduce((sum, s) => sum + s.price, 0);

  function toggle(seat: Seat) {
    const next = new Set(picked);
    if (next.has(seat.showSeatId)) next.delete(seat.showSeatId);
    else if (next.size >= MAX_SEATS) {
      setNotice(`You can book up to ${MAX_SEATS} seats at a time.`);
      return;
    } else next.add(seat.showSeatId);
    setNotice(null);
    setPicked(next);
    idempotencyKey.current = crypto.randomUUID();
  }

  async function holdSeats() {
    setBusy(true);
    setNotice(null);
    try {
      const booking = await api.hold(showId, [...picked], idempotencyKey.current);
      navigate(`/bookings/${booking.id}`);
    } catch (e) {
      setNotice(errorMessage(e));
      if (e instanceof ApiError && e.status === 409) {
        setPicked(new Set());
        idempotencyKey.current = crypto.randomUUID();
        loadSeats().catch(() => undefined);
        // Our waiting-room pass may have run out: find out where we stand.
        api.queue(showId).then(setQueue).catch(() => undefined);
      }
      setBusy(false);
    }
  }

  if (error) return <><p className="form-error" role="alert">{error}</p><Link to="/">Back to events</Link></>;
  if (!queue) return <p className="page-status">Loading...</p>;

  if (!queue.admitted) {
    return (
      <div className="waiting-room" aria-live="polite">
        <h1>You're in the waiting room</h1>
        <p className="queue-position">
          <strong>#{queue.position}</strong>
          <span>
            {queue.position === 1
              ? "You're next in line"
              : `${queue.position - 1} ${queue.position === 2 ? 'person is' : 'people are'} ahead of you`}
          </span>
        </p>
        <p className="muted">
          This show is in high demand, so buyers are let in a few at a time, first come first served.
          Keep this tab open: you move up automatically, and leaving the page gives up your place.
        </p>
        <div className="queue-pulse" aria-hidden="true" />
      </div>
    );
  }

  if (!show || !seats) return <p className="page-status">Loading seats...</p>;

  const left = seats.filter((s) => s.status === 'AVAILABLE').length;
  const passUntil = queue.enabled && queue.admittedUntil
    ? new Date(queue.admittedUntil).toLocaleTimeString('en-IN', { hour: 'numeric', minute: '2-digit' })
    : null;

  return (
    <div className="seats-page">
      <Link to={`/events/${show.eventId}`} className="back">← {show.eventTitle}</Link>
      <h1>{show.eventTitle}</h1>
      <p className="muted">
        {showTime(show.startsAt)} · {show.venueName}, {show.venueCity} · {left} of {seats.length} seats left
        {live && <span className="live-dot" title="This map updates as other people book"> ● Live</span>}
      </p>
      {passUntil && <p className="banner">It's your turn. Hold your seats before {passUntil}, then the next person is let in.</p>}
      {show.surgePercent > 0 && (
        <p className="banner">
          This show is filling up, so remaining seats are {show.surgePercent}% above the starting price.
          The price you see is the price you pay once you hold.
        </p>
      )}

      <div className="hall">
        <div className="hall-screen" aria-hidden="true">Screen this way</div>
        <div className="hall-rows" role="group" aria-label="Seat map">
          {rows.map(([row, rowSeats]) => (
            <div className="hall-row" key={row}>
              <span className="row-label" aria-hidden="true">{row}</span>
              <div className="row-seats">
                {rowSeats.map((s) => {
                  const on = picked.has(s.showSeatId);
                  const taken = s.status !== 'AVAILABLE';
                  return (
                    <Fragment key={s.showSeatId}>
                      <button
                        type="button"
                        className={`hseat ${s.tier.toLowerCase()}${taken ? ' taken' : ''}${on ? ' picked' : ''}`}
                        disabled={taken || busy}
                        aria-pressed={on}
                        aria-label={`Row ${s.row} seat ${s.number}, ${TIER_LABEL[s.tier]}, ${rupees(s.price)}${taken ? ', unavailable' : ''}`}
                        title={`${s.row}${s.number} · ${TIER_LABEL[s.tier]} · ${rupees(s.price)}`}
                        onClick={() => toggle(s)}
                      >
                        {s.number}
                      </button>
                      {aisles.has(s.number) && <span className="aisle" aria-hidden="true" />}
                    </Fragment>
                  );
                })}
              </div>
              <span className="row-label" aria-hidden="true">{row}</span>
            </div>
          ))}
        </div>
        <ul className="legend">
          {tierPrices.map(([tier, price]) => (
            <li key={tier}><i className={`hseat ${tier.toLowerCase()}`} /> {TIER_LABEL[tier]} {rupees(price)}</li>
          ))}
          <li><i className="hseat regular picked" /> Your pick</li>
          <li><i className="hseat regular taken" /> Taken</li>
        </ul>
      </div>

      <div className="checkout-bar" aria-live="polite">
        <div>
          {notice && <p className="bar-notice" role="alert">{notice}</p>}
          {chosen.length === 0 ? (
            <span className="muted">Pick up to {MAX_SEATS} seats</span>
          ) : (
            <>
              <strong>{chosen.map((s) => `${s.row}${s.number}`).join(', ')}</strong>
              <span className="muted"> · {chosen.length} seat{chosen.length > 1 ? 's' : ''} · {rupees(total)}</span>
            </>
          )}
        </div>
        <button className="btn" disabled={chosen.length === 0 || busy} onClick={holdSeats}>
          {busy ? 'Holding...' : 'Hold seats'}
        </button>
      </div>
    </div>
  );
}
