import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import {
  api, categoryLabel, errorMessage, rupees, showTime,
  type Coupon, type EventItem, type EventStats, type Show, type ShowInput, type ShowStats, type Venue,
} from '../api';
import { EventForm } from './Host';

/** datetime-local wants local time without a zone: 2026-12-05T20:00 */
function toLocalInput(iso: string) {
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** One event in the host console: details, poster, sales, shows and discount codes. */
export default function HostEvent({ event, venues, onChanged }: { event: EventItem; venues: Venue[]; onChanged: () => void }) {
  const [shows, setShows] = useState<Show[] | null>(null);
  const [stats, setStats] = useState<EventStats | null>(null);
  const [editing, setEditing] = useState(false);
  const [editingShow, setEditingShow] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);

  const load = () =>
    Promise.all([api.shows(event.id), api.eventStats(event.id)])
      .then(([s, st]) => { setShows(s); setStats(st); })
      .catch((e) => setError(errorMessage(e)));
  useEffect(() => { load(); }, [event.id]); // eslint-disable-line react-hooks/exhaustive-deps

  async function attempt(action: () => Promise<unknown>) {
    setError(null);
    try {
      await action();
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  const removeEvent = () => {
    if (!window.confirm(`Delete "${event.title}" and all its shows? This cannot be undone.`)) return;
    attempt(async () => { await api.deleteEvent(event.id); onChanged(); });
  };

  const removeShow = (s: Show) => {
    if (!window.confirm(`Delete the ${showTime(s.startsAt)} show? This cannot be undone.`)) return;
    attempt(async () => { await api.deleteShow(s.id); await load(); });
  };

  const uploadPoster = (file: File | undefined) => {
    if (!file) return;
    attempt(async () => { await api.uploadPoster(event.id, file); onChanged(); });
    if (fileInput.current) fileInput.current.value = '';
  };

  const statsFor = (showId: number) => stats?.shows.find((s) => s.showId === showId);

  return (
    <section className="panel host-event">
      <div className="host-event-head">
        <div className="host-event-title">
          {event.posterUrl
            ? <img className="thumb" src={event.posterUrl} alt="" />
            : <div className="thumb placeholder" aria-hidden="true">{event.title.charAt(0).toUpperCase()}</div>}
          <div>
            <Link to={`/events/${event.id}`}><strong>{event.title}</strong></Link>
            <span className="muted"> · {categoryLabel(event.category)} · {event.durationMinutes} min</span>
            <div className="host-links">
              <button className="link-btn" onClick={() => setEditing((v) => !v)}>{editing ? 'Close' : 'Edit details'}</button>
              <button className="link-btn" onClick={() => fileInput.current?.click()}>{event.posterUrl ? 'Replace poster' : 'Add poster'}</button>
              {event.posterUrl && (
                <button className="link-btn" onClick={() => attempt(async () => { await api.deletePoster(event.id); onChanged(); })}>Remove poster</button>
              )}
              <button className="link-btn danger" onClick={removeEvent}>Delete event</button>
              <input ref={fileInput} type="file" accept="image/jpeg,image/png,image/webp" hidden
                     aria-label={`Poster for ${event.title}`} onChange={(e) => uploadPoster(e.target.files?.[0])} />
            </div>
          </div>
        </div>
        {stats && stats.totalSeats > 0 && (
          <dl className="stat-row">
            <div><dt>Sold</dt><dd>{stats.booked}<span className="muted"> / {stats.totalSeats}</span></dd></div>
            <div><dt>Revenue</dt><dd>{rupees(stats.revenue)}</dd></div>
            <div><dt>Checked in</dt><dd>{stats.checkedIn}<span className="muted"> / {stats.confirmedBookings}</span></dd></div>
          </dl>
        )}
      </div>

      {error && <p className="form-error" role="alert">{error}</p>}

      {editing && (
        <div className="subpanel">
          <EventForm initial={event} submitLabel="Save changes" onCancel={() => setEditing(false)}
                     onSubmit={async (input) => { await api.updateEvent(event.id, input); setEditing(false); onChanged(); }} />
        </div>
      )}

      <h3>Shows</h3>
      {shows?.length === 0 && <p className="hint">No shows yet. Nobody can book until you add one.</p>}
      {shows && shows.length > 0 && (
        <ul className="host-shows">
          {shows.map((s) => (
            <li key={s.id}>
              <div className="host-show-line">
                <div>
                  <strong>{showTime(s.startsAt)}</strong> <span className="muted">· {s.venueName} · from {rupees(s.basePrice)}</span>
                  {s.waitingRoom && <span className="tag">Waiting room</span>}
                  {s.dynamicPricing && <span className="tag">Demand pricing</span>}
                </div>
                <div className="host-links">
                  <Link to={`/shows/${s.id}`}>Seat map</Link>
                  <button className="link-btn" onClick={() => setEditingShow(editingShow === s.id ? null : s.id)}>
                    {editingShow === s.id ? 'Close' : 'Edit'}
                  </button>
                  <button className="link-btn danger" onClick={() => removeShow(s)}>Delete</button>
                </div>
              </div>
              <Occupancy stats={statsFor(s.id)} />
              {editingShow === s.id && (
                <div className="subpanel">
                  <ShowForm initial={s} submitLabel="Save show" onCancel={() => setEditingShow(null)}
                            hint="New prices apply to seats that are still available. Seats already held or booked keep their price."
                            onSubmit={async (input) => { await api.updateShow(s.id, input); setEditingShow(null); await load(); }} />
                </div>
              )}
            </li>
          ))}
        </ul>
      )}

      {venues.length === 0 ? (
        <p className="hint">Add a venue first, then you can schedule a show here.</p>
      ) : (
        <details className="adder">
          <summary>Add a show</summary>
          <ShowForm venues={venues} submitLabel="Add show"
                    onSubmit={async (input, venueId) => { await api.createShow(event.id, { ...input, venueId: venueId! }); await load(); }} />
        </details>
      )}

      <Coupons eventId={event.id} />
    </section>
  );
}

function Occupancy({ stats }: { stats?: ShowStats }) {
  if (!stats || stats.totalSeats === 0) return null;
  const pct = (n: number) => `${(n / stats.totalSeats) * 100}%`;
  return (
    <div className="occupancy">
      <div className="occupancy-bar" role="img"
           aria-label={`${stats.booked} booked, ${stats.held} on hold, ${stats.available} available of ${stats.totalSeats}`}>
        <span className="booked" style={{ width: pct(stats.booked) }} />
        <span className="held" style={{ width: pct(stats.held) }} />
      </div>
      <span className="muted small">
        {stats.booked} sold · {stats.held} on hold · {stats.available} left · {rupees(stats.revenue)} · {stats.checkedIn} of {stats.confirmedBookings} bookings checked in
      </span>
    </div>
  );
}

/** Shared by "add show" (pick a venue) and "edit show" (venue is fixed). */
function ShowForm({ initial, venues, submitLabel, hint, onSubmit, onCancel }: {
  initial?: Show;
  venues?: Venue[];
  submitLabel: string;
  hint?: string;
  onSubmit: (input: ShowInput, venueId?: number) => Promise<void>;
  onCancel?: () => void;
}) {
  const [venueId, setVenueId] = useState<number | ''>('');
  const [startsAt, setStartsAt] = useState(initial ? toLocalInput(initial.startsAt) : '');
  const [basePrice, setBasePrice] = useState(initial?.basePrice ?? 200);
  const [premiumPrice, setPremiumPrice] = useState('');
  const [vipPrice, setVipPrice] = useState('');
  const [waitingRoom, setWaitingRoom] = useState(initial?.waitingRoom ?? false);
  const [dynamicPricing, setDynamicPricing] = useState(initial?.dynamicPricing ?? false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (venues && venueId === '') return;
    setBusy(true);
    setError(null);
    try {
      await onSubmit({
        startsAt: new Date(startsAt).toISOString(),
        basePrice,
        premiumPrice: premiumPrice === '' ? undefined : Number(premiumPrice),
        vipPrice: vipPrice === '' ? undefined : Number(vipPrice),
        waitingRoom,
        dynamicPricing,
      }, venueId === '' ? undefined : venueId);
      if (!initial) setStartsAt('');
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="show-form" onSubmit={submit}>
      <div className="field-row">
        {venues && (
          <label className="field"><span>Venue</span>
            <select value={venueId} onChange={(e) => setVenueId(e.target.value ? Number(e.target.value) : '')} required>
              <option value="">Choose a venue</option>
              {venues.map((v) => <option key={v.id} value={v.id}>{v.name} ({v.city})</option>)}
            </select>
          </label>
        )}
        <label className="field"><span>Starts at</span>
          <input type="datetime-local" value={startsAt} onChange={(e) => setStartsAt(e.target.value)} required />
        </label>
      </div>
      <div className="field-row three">
        <label className="field"><span>Regular price (₹)</span>
          <input type="number" min={0} step="0.01" value={basePrice} onChange={(e) => setBasePrice(Number(e.target.value))} required />
        </label>
        <label className="field"><span>Premium price (₹)</span>
          <input type="number" min={0} step="0.01" value={premiumPrice} onChange={(e) => setPremiumPrice(e.target.value)}
                 placeholder={`${rupees(basePrice * 1.5)} if blank`} />
        </label>
        <label className="field"><span>VIP price (₹)</span>
          <input type="number" min={0} step="0.01" value={vipPrice} onChange={(e) => setVipPrice(e.target.value)}
                 placeholder={`${rupees(basePrice * 2)} if blank`} />
        </label>
      </div>
      <label className="check">
        <input type="checkbox" checked={waitingRoom} onChange={(e) => setWaitingRoom(e.target.checked)} />
        <span><strong>Waiting room</strong> Buyers queue first come, first served and are let in a few at a time. For shows you expect to sell out fast.</span>
      </label>
      <label className="check">
        <input type="checkbox" checked={dynamicPricing} onChange={(e) => setDynamicPricing(e.target.checked)} />
        <span><strong>Demand pricing</strong> Remaining seats cost 10% more once half the show is sold, and 25% more past 80%.</span>
      </label>
      {hint && <p className="hint">{hint}</p>}
      {error && <p className="form-error" role="alert">{error}</p>}
      <div className="actions">
        <button className="btn" type="submit" disabled={busy}>{busy ? 'Saving...' : submitLabel}</button>
        {onCancel && <button className="btn ghost" type="button" onClick={onCancel}>Cancel</button>}
      </div>
    </form>
  );
}

function Coupons({ eventId }: { eventId: number }) {
  const [coupons, setCoupons] = useState<Coupon[]>([]);
  const [code, setCode] = useState('');
  const [percentOff, setPercentOff] = useState(10);
  const [maxUses, setMaxUses] = useState(100);
  const [error, setError] = useState<string | null>(null);

  const load = () => api.coupons(eventId).then(setCoupons).catch((e) => setError(errorMessage(e)));
  useEffect(() => { load(); }, [eventId]); // eslint-disable-line react-hooks/exhaustive-deps

  async function add(e: FormEvent) {
    e.preventDefault();
    setError(null);
    try {
      await api.createCoupon(eventId, { code, percentOff, maxUses });
      setCode('');
      await load();
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  async function remove(c: Coupon) {
    if (!window.confirm(`Delete the code ${c.code}? Bookings that already used it keep their discount.`)) return;
    setError(null);
    try {
      await api.deleteCoupon(c.id);
      await load();
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  return (
    <details className="adder" open={coupons.length > 0}>
      <summary>Discount codes{coupons.length > 0 ? ` (${coupons.length})` : ''}</summary>
      {coupons.length > 0 && (
        <ul className="host-shows">
          {coupons.map((c) => (
            <li key={c.id} className="host-show-line">
              <span><code>{c.code}</code> <span className="muted">· {c.percentOff}% off · used {c.usedCount} of {c.maxUses}</span></span>
              <button className="link-btn danger" onClick={() => remove(c)}>Delete</button>
            </li>
          ))}
        </ul>
      )}
      <form className="coupon-add" onSubmit={add}>
        <label className="field"><span>Code</span>
          <input type="text" value={code} onChange={(e) => setCode(e.target.value.toUpperCase())} required
                 pattern="[A-Za-z0-9_\-]{3,40}" title="3 to 40 letters, digits, - or _" placeholder="EARLYBIRD" />
        </label>
        <label className="field"><span>% off (1-90)</span>
          <input type="number" min={1} max={90} value={percentOff} onChange={(e) => setPercentOff(Number(e.target.value))} required />
        </label>
        <label className="field"><span>Max uses</span>
          <input type="number" min={1} value={maxUses} onChange={(e) => setMaxUses(Number(e.target.value))} required />
        </label>
        <button className="btn" type="submit">Add code</button>
      </form>
      {error && <p className="form-error" role="alert">{error}</p>}
    </details>
  );
}
