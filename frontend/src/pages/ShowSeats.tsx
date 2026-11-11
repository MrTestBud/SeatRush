import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { api, ApiError, errorMessage, rupees, showTime, type Seat, type Show } from '../api';

const MAX_SEATS = 8;
const REFRESH_MS = 4000;

export default function ShowSeats() {
  const showId = Number(useParams().id);
  const navigate = useNavigate();
  const [show, setShow] = useState<Show | null>(null);
  const [seats, setSeats] = useState<Seat[] | null>(null);
  const [picked, setPicked] = useState<Set<number>>(new Set());
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  // One key per selection: a double click sends the same key twice and gets the same booking back.
  const idempotencyKey = useRef(crypto.randomUUID());

  const loadSeats = useCallback(async () => setSeats(await api.seats(showId)), [showId]);

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

  useEffect(() => {
    api.show(showId).then(setShow).catch((e) => setError(errorMessage(e)));
    loadSeats().catch((e) => setError(errorMessage(e)));
    // The map is live: other people are booking while you look at it.
    const timer = setInterval(() => { loadSeats().catch(() => undefined); }, REFRESH_MS);
    return () => clearInterval(timer);
  }, [showId, loadSeats]);

  const rows = useMemo(() => {
    const byRow = new Map<string, Seat[]>();
    for (const s of seats ?? []) byRow.set(s.row, [...(byRow.get(s.row) ?? []), s]);
    return [...byRow.entries()];
  }, [seats]);

  const chosen = (seats ?? []).filter((s) => picked.has(s.showSeatId));
  const total = chosen.reduce((sum, s) => sum + s.price, 0);
  const prices = useMemo(() => {
    const tier = (t: string) => seats?.find((s) => s.tier === t)?.price;
    return { premium: tier('PREMIUM'), regular: tier('REGULAR') };
  }, [seats]);

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
      }
      setBusy(false);
    }
  }

  if (error) return <><p className="form-error" role="alert">{error}</p><Link to="/">Back to events</Link></>;
  if (!show || !seats) return <p className="page-status">Loading seats...</p>;

  const left = seats.filter((s) => s.status === 'AVAILABLE').length;

  return (
    <div className="seats-page">
      <Link to={`/events/${show.eventId}`} className="back">← {show.eventTitle}</Link>
      <h1>{show.eventTitle}</h1>
      <p className="muted">{showTime(show.startsAt)} · {show.venueName}, {show.venueCity} · {left} of {seats.length} seats left</p>

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
                    <button
                      key={s.showSeatId}
                      type="button"
                      className={`hseat ${s.tier.toLowerCase()}${taken ? ' taken' : ''}${on ? ' picked' : ''}`}
                      disabled={taken || busy}
                      aria-pressed={on}
                      aria-label={`Row ${s.row} seat ${s.number}, ${s.tier.toLowerCase()}, ${rupees(s.price)}${taken ? ', unavailable' : ''}`}
                      title={`${s.row}${s.number} · ${rupees(s.price)}`}
                      onClick={() => toggle(s)}
                    >
                      {s.number}
                    </button>
                  );
                })}
              </div>
              <span className="row-label" aria-hidden="true">{row}</span>
            </div>
          ))}
        </div>
        <ul className="legend">
          {prices.premium !== undefined && <li><i className="hseat premium" /> Premium {rupees(prices.premium)}</li>}
          {prices.regular !== undefined && <li><i className="hseat regular" /> Regular {rupees(prices.regular)}</li>}
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
