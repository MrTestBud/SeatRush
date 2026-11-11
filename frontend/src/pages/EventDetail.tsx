import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api, errorMessage, rupees, showTime, type EventItem, type Show } from '../api';

export default function EventDetail() {
  const id = Number(useParams().id);
  const [event, setEvent] = useState<EventItem | null>(null);
  const [shows, setShows] = useState<Show[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    setEvent(null);
    setShows(null);
    setError(null);
    Promise.all([api.event(id), api.shows(id)])
      .then(([e, s]) => { setEvent(e); setShows(s); })
      .catch((e) => setError(errorMessage(e)));
  }, [id]);

  if (error) return <><p className="form-error" role="alert">{error}</p><Link to="/">Back to events</Link></>;
  if (!event || !shows) return <p className="page-status">Loading event...</p>;

  const upcoming = shows.filter((s) => new Date(s.startsAt).getTime() > Date.now());

  return (
    <>
      <Link to="/" className="back">← All events</Link>
      <div className="event-hero">
        <span className="chip">{event.category.toLowerCase()}</span>
        <h1>{event.title}</h1>
        <p className="muted">{event.durationMinutes} min{event.language ? ` · ${event.language}` : ''}</p>
        {event.description && <p className="event-desc">{event.description}</p>}
      </div>

      <h2>Shows</h2>
      {upcoming.length === 0 ? (
        <p className="empty">No upcoming shows for this event yet.</p>
      ) : (
        <ul className="show-list">
          {upcoming.map((s) => (
            <li key={s.id}>
              <div>
                <strong>{showTime(s.startsAt)}</strong>
                <span className="muted">{s.venueName}, {s.venueCity}</span>
              </div>
              <div className="show-cta">
                <span>from {rupees(s.basePrice)}</span>
                <Link className="btn" to={`/shows/${s.id}`}>Pick seats</Link>
              </div>
            </li>
          ))}
        </ul>
      )}
    </>
  );
}
