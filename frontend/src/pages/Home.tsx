import { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { api, errorMessage, type EventItem, type Page } from '../api';
import { useAuth } from '../auth';

const PAGE_SIZE = 12;

export default function Home() {
  const { user } = useAuth();
  const [params, setParams] = useSearchParams();
  const q = params.get('q') ?? '';
  const page = Math.max(0, Number(params.get('page') ?? 0) || 0);

  const [text, setText] = useState(q);
  const [result, setResult] = useState<Page<EventItem> | null>(null);
  const [error, setError] = useState<string | null>(null);

  // Typing updates the URL after a short pause, so search is shareable and Back works.
  useEffect(() => {
    if (text.trim() === q) return;
    const t = setTimeout(() => setParams(text.trim() ? { q: text.trim() } : {}), 300);
    return () => clearTimeout(t);
  }, [text, q, setParams]);

  useEffect(() => {
    let stale = false;
    setError(null);
    api.events(q, page, PAGE_SIZE)
      .then((p) => { if (!stale) setResult(p); })
      .catch((e) => { if (!stale) setError(errorMessage(e)); });
    return () => { stale = true; };
  }, [q, page]);

  const goTo = (p: number) => setParams({ ...(q ? { q } : {}), ...(p > 0 ? { page: String(p) } : {}) });
  const pages = result ? Math.ceil(result.total / PAGE_SIZE) : 0;

  return (
    <>
      <div className="page-head">
        <h1>What are you watching next?</h1>
        <input
          type="search"
          className="search"
          placeholder="Search events by title or description"
          aria-label="Search events"
          value={text}
          onChange={(e) => setText(e.target.value)}
        />
      </div>

      {error && <p className="form-error" role="alert">{error}</p>}
      {!error && result === null && <p className="page-status">Loading events...</p>}

      {result && result.items.length === 0 && (
        <div className="empty-state">
          {q ? (
            <>
              <p>No events match "{q}".</p>
              <button className="link-btn" onClick={() => setText('')}>Clear search</button>
            </>
          ) : (
            <>
              <p>No events yet.</p>
              {user?.role === 'USER'
                ? <p className="muted">Check back soon.</p>
                : <Link className="btn" to="/host">Create the first event</Link>}
            </>
          )}
        </div>
      )}

      {result && result.items.length > 0 && (
        <>
          <ul className="event-grid">
            {result.items.map((ev) => (
              <li key={ev.id}>
                <Link to={`/events/${ev.id}`} className="event-card">
                  <span className="chip">{ev.category.toLowerCase()}</span>
                  <strong>{ev.title}</strong>
                  <span className="muted">
                    {ev.durationMinutes} min{ev.language ? ` · ${ev.language}` : ''}
                  </span>
                  {ev.description && <p>{ev.description}</p>}
                </Link>
              </li>
            ))}
          </ul>
          {pages > 1 && (
            <nav className="pager" aria-label="Pages">
              <button className="btn ghost" disabled={page === 0} onClick={() => goTo(page - 1)}>Previous</button>
              <span>Page {page + 1} of {pages}</span>
              <button className="btn ghost" disabled={page + 1 >= pages} onClick={() => goTo(page + 1)}>Next</button>
            </nav>
          )}
        </>
      )}
    </>
  );
}
