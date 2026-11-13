import { useEffect, useState, type FormEvent } from 'react';
import { Link, Navigate } from 'react-router-dom';
import { api, categoryLabel, errorMessage, type EventInput, type EventItem, type Venue } from '../api';
import { useAuth } from '../auth';
import HostEvent from './HostEvent';

export const CATEGORIES = ['MOVIE', 'CONCERT', 'COMEDY', 'THEATRE', 'SPORTS', 'OTHER'];

/** Organizer console: add a venue, create an event, schedule shows, then watch sales and check guests in. */
export default function Host() {
  const { user } = useAuth();
  const [venues, setVenues] = useState<Venue[]>([]);
  const [events, setEvents] = useState<EventItem[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  const reload = () =>
    Promise.all([api.venues(), api.myEvents()])
      .then(([v, e]) => { setVenues(v); setEvents(e); })
      .catch((e) => setError(errorMessage(e)));

  useEffect(() => { if (user?.role !== 'USER') reload(); }, [user]);

  if (user?.role === 'USER') return <Navigate to="/" replace />;

  return (
    <>
      <div className="page-head">
        <h1>Host console</h1>
        <Link className="btn ghost" to="/host/checkin">Check guests in</Link>
      </div>
      <p className="muted lead">Three steps: add a venue (its seat grid is generated for you), create an event, then schedule shows.</p>
      {error && <p className="form-error" role="alert">{error}</p>}

      <div className="host-grid">
        <VenueForm venues={venues} onCreated={reload} />
        <section className="panel">
          <h2>2. Create an event</h2>
          <EventForm submitLabel="Create event" onSubmit={async (input) => { await api.createEvent(input); await reload(); }} resetOnSave />
        </section>
      </div>

      <h2>Your events</h2>
      {events === null && !error && <p className="page-status">Loading your events...</p>}
      {events?.length === 0 && <p className="empty">Nothing yet. Create your first event above.</p>}
      {events?.map((ev) => <HostEvent key={ev.id} event={ev} venues={venues} onChanged={reload} />)}
    </>
  );
}

function VenueForm({ venues, onCreated }: { venues: Venue[]; onCreated: () => void }) {
  const [name, setName] = useState('');
  const [city, setCity] = useState('');
  const [address, setAddress] = useState('');
  const [rows, setRows] = useState(6);
  const [seatsPerRow, setSeatsPerRow] = useState(10);
  const [vipRows, setVipRows] = useState(0);
  const [premiumRows, setPremiumRows] = useState(2);
  const [aisles, setAisles] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const aisleNumbers = aisles.split(/[\s,]+/).filter(Boolean).map(Number);
  const regularRows = Math.max(0, rows - vipRows - premiumRows);

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (aisleNumbers.some((n) => !Number.isInteger(n) || n < 1 || n >= seatsPerRow)) {
      setError(`Aisles must be seat numbers between 1 and ${seatsPerRow - 1}, separated by commas (for example 3, 7).`);
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await api.createVenue({ name, city, address, rows, seatsPerRow, vipRows, premiumRows, aisles: aisleNumbers });
      setName(''); setCity(''); setAddress(''); setAisles('');
      onCreated();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="panel" onSubmit={submit}>
      <h2>1. Add a venue</h2>
      <label className="field"><span>Venue name</span>
        <input type="text" value={name} onChange={(e) => setName(e.target.value)} required maxLength={150} />
      </label>
      <div className="field-row">
        <label className="field"><span>City</span>
          <input type="text" value={city} onChange={(e) => setCity(e.target.value)} required maxLength={100} />
        </label>
        <label className="field"><span>Address</span>
          <input type="text" value={address} onChange={(e) => setAddress(e.target.value)} required maxLength={255} />
        </label>
      </div>
      <div className="field-row">
        <label className="field"><span>Rows (1-26)</span>
          <input type="number" min={1} max={26} value={rows} onChange={(e) => setRows(Number(e.target.value))} required />
        </label>
        <label className="field"><span>Seats per row (1-50)</span>
          <input type="number" min={1} max={50} value={seatsPerRow} onChange={(e) => setSeatsPerRow(Number(e.target.value))} required />
        </label>
      </div>
      <div className="field-row three">
        <label className="field"><span>VIP rows</span>
          <input type="number" min={0} max={26} value={vipRows} onChange={(e) => setVipRows(Number(e.target.value))} />
        </label>
        <label className="field"><span>Premium rows</span>
          <input type="number" min={0} max={26} value={premiumRows} onChange={(e) => setPremiumRows(Number(e.target.value))} />
        </label>
        <label className="field"><span>Aisles after seat</span>
          <input type="text" value={aisles} onChange={(e) => setAisles(e.target.value)} placeholder="e.g. 3, 7" inputMode="numeric" />
        </label>
      </div>
      <p className="hint">
        {rows * seatsPerRow} seats: from the front, {vipRows} VIP row{vipRows === 1 ? '' : 's'}, {premiumRows} premium,{' '}
        {regularRows} regular. You set each tier's price per show.
      </p>
      {error && <p className="form-error" role="alert">{error}</p>}
      <button className="btn" type="submit" disabled={busy}>{busy ? 'Adding...' : 'Add venue'}</button>
      {venues.length > 0 && (
        <p className="hint">Venues so far: {venues.map((v) => `${v.name} (${v.city})`).join(', ')}</p>
      )}
    </form>
  );
}

/** Shared by "create event" and "edit event". */
export function EventForm({ initial, submitLabel, onSubmit, onCancel, resetOnSave }: {
  initial?: EventItem;
  submitLabel: string;
  onSubmit: (input: EventInput) => Promise<void>;
  onCancel?: () => void;
  resetOnSave?: boolean;
}) {
  const [title, setTitle] = useState(initial?.title ?? '');
  const [description, setDescription] = useState(initial?.description ?? '');
  const [category, setCategory] = useState(initial?.category ?? CATEGORIES[0]);
  const [language, setLanguage] = useState(initial?.language ?? '');
  const [durationMinutes, setDurationMinutes] = useState(initial?.durationMinutes ?? 120);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  // An event saved earlier may have a category that is not in today's list: keep it selectable.
  const options = CATEGORIES.includes(category) ? CATEGORIES : [category, ...CATEGORIES];

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await onSubmit({ title, description, category, language, durationMinutes });
      if (resetOnSave) { setTitle(''); setDescription(''); setLanguage(''); }
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <form onSubmit={submit}>
      <label className="field"><span>Title</span>
        <input type="text" value={title} onChange={(e) => setTitle(e.target.value)} required maxLength={200} />
      </label>
      <label className="field"><span>Description</span>
        <textarea rows={3} value={description} onChange={(e) => setDescription(e.target.value)} maxLength={5000} />
      </label>
      <div className="field-row three">
        <label className="field"><span>Category</span>
          <select value={category} onChange={(e) => setCategory(e.target.value)}>
            {options.map((c) => <option key={c} value={c}>{categoryLabel(c)}</option>)}
          </select>
        </label>
        <label className="field"><span>Language</span>
          <input type="text" value={language} onChange={(e) => setLanguage(e.target.value)} maxLength={50} />
        </label>
        <label className="field"><span>Minutes</span>
          <input type="number" min={1} value={durationMinutes} onChange={(e) => setDurationMinutes(Number(e.target.value))} required />
        </label>
      </div>
      {error && <p className="form-error" role="alert">{error}</p>}
      <div className="actions">
        <button className="btn" type="submit" disabled={busy}>{busy ? 'Saving...' : submitLabel}</button>
        {onCancel && <button className="btn ghost" type="button" onClick={onCancel}>Cancel</button>}
      </div>
    </form>
  );
}
