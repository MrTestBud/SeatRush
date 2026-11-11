import { useEffect, useState, type FormEvent } from 'react';
import { Link, Navigate } from 'react-router-dom';
import { api, errorMessage, rupees, showTime, type EventItem, type Show, type Venue } from '../api';
import { useAuth } from '../auth';

const CATEGORIES = ['MOVIE', 'CONCERT', 'COMEDY', 'THEATRE', 'SPORTS'];

/** Organizer console: add a venue, create an event, schedule shows for it. */
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
      <div className="page-head"><h1>Host console</h1></div>
      <p className="muted lead">Three steps: add a venue (its seat grid is generated for you), create an event, then schedule shows.</p>
      {error && <p className="form-error" role="alert">{error}</p>}

      <div className="host-grid">
        <VenueForm venues={venues} onCreated={reload} />
        <EventForm onCreated={reload} />
      </div>

      <h2>Your events</h2>
      {events === null && !error && <p className="page-status">Loading your events...</p>}
      {events?.length === 0 && <p className="empty">Nothing yet. Create your first event above.</p>}
      {events?.map((ev) => <EventShows key={ev.id} event={ev} venues={venues} onDeleted={reload} />)}
    </>
  );
}

function VenueForm({ venues, onCreated }: { venues: Venue[]; onCreated: () => void }) {
  const [name, setName] = useState('');
  const [city, setCity] = useState('');
  const [address, setAddress] = useState('');
  const [rows, setRows] = useState(6);
  const [seatsPerRow, setSeatsPerRow] = useState(10);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await api.createVenue({ name, city, address, rows, seatsPerRow });
      setName(''); setCity(''); setAddress('');
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
      <p className="hint">{rows * seatsPerRow} seats. The first two rows are premium (1.5x the show's base price).</p>
      {error && <p className="form-error" role="alert">{error}</p>}
      <button className="btn" type="submit" disabled={busy}>{busy ? 'Adding...' : 'Add venue'}</button>
      {venues.length > 0 && (
        <p className="hint">Venues so far: {venues.map((v) => `${v.name} (${v.city})`).join(', ')}</p>
      )}
    </form>
  );
}

function EventForm({ onCreated }: { onCreated: () => void }) {
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [category, setCategory] = useState(CATEGORIES[0]);
  const [language, setLanguage] = useState('');
  const [durationMinutes, setDurationMinutes] = useState(120);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await api.createEvent({ title, description, category, language, durationMinutes });
      setTitle(''); setDescription(''); setLanguage('');
      onCreated();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="panel" onSubmit={submit}>
      <h2>2. Create an event</h2>
      <label className="field"><span>Title</span>
        <input type="text" value={title} onChange={(e) => setTitle(e.target.value)} required maxLength={200} />
      </label>
      <label className="field"><span>Description</span>
        <textarea rows={3} value={description} onChange={(e) => setDescription(e.target.value)} maxLength={5000} />
      </label>
      <div className="field-row three">
        <label className="field"><span>Category</span>
          <select value={category} onChange={(e) => setCategory(e.target.value)}>
            {CATEGORIES.map((c) => <option key={c} value={c}>{c.charAt(0) + c.slice(1).toLowerCase()}</option>)}
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
      <button className="btn" type="submit" disabled={busy}>{busy ? 'Creating...' : 'Create event'}</button>
    </form>
  );
}

function EventShows({ event, venues, onDeleted }: { event: EventItem; venues: Venue[]; onDeleted: () => void }) {
  const [shows, setShows] = useState<Show[] | null>(null);
  const [venueId, setVenueId] = useState<number | ''>('');
  const [startsAt, setStartsAt] = useState('');
  const [basePrice, setBasePrice] = useState(200);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = () => api.shows(event.id).then(setShows).catch((e) => setError(errorMessage(e)));
  useEffect(() => { load(); }, [event.id]); // eslint-disable-line react-hooks/exhaustive-deps

  async function addShow(e: FormEvent) {
    e.preventDefault();
    if (venueId === '') return;
    setBusy(true);
    setError(null);
    try {
      await api.createShow(event.id, { venueId, startsAt: new Date(startsAt).toISOString(), basePrice });
      setStartsAt('');
      await load();
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  async function remove() {
    if (!window.confirm(`Delete "${event.title}" and all its shows? This cannot be undone.`)) return;
    setError(null);
    try {
      await api.deleteEvent(event.id);
      onDeleted();
    } catch (err) {
      setError(errorMessage(err));
    }
  }

  return (
    <section className="panel host-event">
      <div className="host-event-head">
        <div>
          <Link to={`/events/${event.id}`}><strong>{event.title}</strong></Link>
          <span className="muted"> · {event.category.toLowerCase()} · {event.durationMinutes} min</span>
        </div>
        <button className="link-btn danger" onClick={remove}>Delete event</button>
      </div>

      {shows && shows.length > 0 && (
        <ul className="host-shows">
          {shows.map((s) => (
            <li key={s.id}>
              <span>{showTime(s.startsAt)} · {s.venueName}</span>
              <span>{rupees(s.basePrice)} <Link to={`/shows/${s.id}`}>Seat map</Link></span>
            </li>
          ))}
        </ul>
      )}
      {shows?.length === 0 && <p className="hint">No shows yet. Nobody can book until you add one.</p>}

      {venues.length === 0 ? (
        <p className="hint">Add a venue first, then you can schedule a show here.</p>
      ) : (
        <form className="show-form" onSubmit={addShow}>
          <label className="field"><span>Venue</span>
            <select value={venueId} onChange={(e) => setVenueId(e.target.value ? Number(e.target.value) : '')} required>
              <option value="">Choose a venue</option>
              {venues.map((v) => <option key={v.id} value={v.id}>{v.name} ({v.city})</option>)}
            </select>
          </label>
          <label className="field"><span>Starts at</span>
            <input type="datetime-local" value={startsAt} onChange={(e) => setStartsAt(e.target.value)} required />
          </label>
          <label className="field"><span>Base price (₹)</span>
            <input type="number" min={0} step="0.01" value={basePrice} onChange={(e) => setBasePrice(Number(e.target.value))} required />
          </label>
          <button className="btn" type="submit" disabled={busy}>{busy ? 'Adding...' : 'Add show'}</button>
        </form>
      )}
      {error && <p className="form-error" role="alert">{error}</p>}
    </section>
  );
}
