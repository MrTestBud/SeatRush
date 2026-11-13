import { useEffect, useState, type FormEvent } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api, categoryLabel, errorMessage, rupees, showTime, type EventItem, type Reviews, type Show } from '../api';

const stars = (n: number) => '★'.repeat(n) + '☆'.repeat(5 - n);

export default function EventDetail() {
  const id = Number(useParams().id);
  const [event, setEvent] = useState<EventItem | null>(null);
  const [shows, setShows] = useState<Show[] | null>(null);
  const [reviews, setReviews] = useState<Reviews | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    setEvent(null);
    setShows(null);
    setReviews(null);
    setError(null);
    Promise.all([api.event(id), api.shows(id)])
      .then(([e, s]) => { setEvent(e); setShows(s); })
      .catch((e) => setError(errorMessage(e)));
    api.reviews(id).then(setReviews).catch(() => undefined);   // the page is still useful without reviews
  }, [id]);

  if (error) return <><p className="form-error" role="alert">{error}</p><Link to="/">Back to events</Link></>;
  if (!event || !shows) return <p className="page-status">Loading event...</p>;

  const upcoming = shows.filter((s) => new Date(s.startsAt).getTime() > Date.now());

  return (
    <>
      <Link to="/" className="back">← All events</Link>
      <div className={event.posterUrl ? 'event-hero with-poster' : 'event-hero'}>
        {event.posterUrl && <img className="hero-poster" src={event.posterUrl} alt={`Poster for ${event.title}`} />}
        <div>
          <span className="chip">{categoryLabel(event.category)}</span>
          <h1>{event.title}</h1>
          <p className="muted">
            {event.durationMinutes} min{event.language ? ` · ${event.language}` : ''}
            {reviews && reviews.count > 0 && (
              <> · <span className="stars" aria-hidden="true">★</span> {reviews.average.toFixed(1)} ({reviews.count})</>
            )}
          </p>
          {event.description && <p className="event-desc">{event.description}</p>}
        </div>
      </div>

      <h2>Shows</h2>
      {upcoming.length === 0 ? (
        <p className="empty">No upcoming shows for this event{shows.length > 0 ? ' right now' : ' yet'}.</p>
      ) : (
        <ul className="show-list">
          {upcoming.map((s) => (
            <li key={s.id}>
              <div>
                <strong>{showTime(s.startsAt)}</strong>
                <span className="muted">{s.venueName}, {s.venueCity}</span>
                {(s.waitingRoom || s.dynamicPricing) && (
                  <span className="tags">
                    {s.waitingRoom && <span className="tag">Waiting room</span>}
                    {s.dynamicPricing && <span className="tag">Demand pricing</span>}
                  </span>
                )}
              </div>
              <div className="show-cta">
                <span>from {rupees(s.basePrice)}</span>
                <Link className="btn" to={`/shows/${s.id}`}>Pick seats</Link>
              </div>
            </li>
          ))}
        </ul>
      )}

      {reviews && <ReviewSection eventId={id} reviews={reviews} onSaved={setReviews} />}
    </>
  );
}

function ReviewSection({ eventId, reviews, onSaved }: { eventId: number; reviews: Reviews; onSaved: (r: Reviews) => void }) {
  const [rating, setRating] = useState(reviews.myReview?.rating ?? 0);
  const [comment, setComment] = useState(reviews.myReview?.comment ?? '');
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (rating === 0) { setError('Choose a rating from 1 to 5 stars.'); return; }
    setBusy(true);
    setError(null);
    try {
      onSaved(await api.saveReview(eventId, rating, comment));
      setSaved(true);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section>
      <h2>
        Reviews
        {reviews.count > 0 && <span className="muted review-summary"> {reviews.average.toFixed(1)} out of 5 · {reviews.count}</span>}
      </h2>

      {reviews.canReview ? (
        <form className="panel review-form" onSubmit={submit}>
          <fieldset className="star-picker">
            <legend>{reviews.myReview ? 'Your review' : 'You went to this. How was it?'}</legend>
            {[1, 2, 3, 4, 5].map((n) => (
              <label key={n} className={n <= rating ? 'star on' : 'star'}>
                <input type="radio" name="rating" value={n} checked={rating === n} onChange={() => { setRating(n); setSaved(false); }} />
                <span aria-hidden="true">★</span>
                <span className="sr-only">{n} star{n > 1 ? 's' : ''}</span>
              </label>
            ))}
          </fieldset>
          <label className="field">
            <span>Anything to add? (optional)</span>
            <textarea rows={3} maxLength={2000} value={comment} onChange={(e) => { setComment(e.target.value); setSaved(false); }} />
          </label>
          {error && <p className="form-error" role="alert">{error}</p>}
          <button className="btn" type="submit" disabled={busy}>
            {busy ? 'Saving...' : reviews.myReview ? 'Update review' : 'Post review'}
          </button>
          {saved && <span className="saved" role="status"> Saved</span>}
        </form>
      ) : (
        <p className="hint">Reviews come from people who attended. You can add yours after going to a show.</p>
      )}

      {reviews.count === 0 ? (
        <p className="empty">No reviews yet.</p>
      ) : (
        <ul className="review-list">
          {reviews.items.map((r) => (
            <li key={r.id}>
              <div>
                <span className="stars" aria-label={`${r.rating} out of 5`}>{stars(r.rating)}</span>
                <strong> {r.author}</strong>{r.mine && <span className="tag">You</span>}
                <span className="muted"> · {new Date(r.createdAt).toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' })}</span>
              </div>
              {r.comment && <p>{r.comment}</p>}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
