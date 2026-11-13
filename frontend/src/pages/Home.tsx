import { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { api, categoryLabel, errorMessage, type EventItem, type Page } from '../api';
import { useAuth } from '../auth';

const PAGE_SIZE = 12;

export default function Home() {
  const { user } = useAuth();
  const [params, setParams] = useSearchParams();
  const q = params.get('q') ?? '';
  const category = params.get('category') ?? '';
  const page = Math.max(0, Number(params.get('page') ?? 0) || 0);

  const [text, setText] = useState(q);
  const [categories, setCategories] = useState<string[]>([]);
  const [result, setResult] = useState<Page<EventItem> | null>(null);
  const [error, setError] = useState<string | null>(null);

  const go = (next: { q?: string; category?: string; page?: number }) => {
    const merged = { q, category, page: 0, ...next };
    const out: Record<string, string> = {};
    if (merged.q) out.q = merged.q;
    if (merged.category) out.category = merged.category;
    if (merged.page > 0) out.page = String(merged.page);
    setParams(out);
  };

  useEffect(() => { api.categories().then(setCategories).catch(() => undefined); }, []);

  // Typing updates the URL after a short pause, so search is shareable and Back works.
  useEffect(() => {
    if (text.trim() === q) return;
    const t = setTimeout(() => go({ q: text.trim() }), 300);
    return () => clearTimeout(t);
  }, [text]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    let stale = false;
    setError(null);
    api.events(q, category, page, PAGE_SIZE)
      .then((p) => { if (!stale) setResult(p); })
      .catch((e) => { if (!stale) setError(errorMessage(e)); });
    return () => { stale = true; };
  }, [q, category, page]);

  const pages = result ? Math.ceil(result.total / PAGE_SIZE) : 0;
  const filtered = q !== '' || category !== '';

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

      {categories.length > 1 && (
        <div className="filters" role="group" aria-label="Filter by category">
          <button className={category === '' ? 'filter on' : 'filter'} aria-pressed={category === ''} onClick={() => go({ category: '' })}>
            All
          </button>
          {categories.map((c) => (
            <button key={c} className={category === c ? 'filter on' : 'filter'} aria-pressed={category === c} onClick={() => go({ category: c })}>
              {categoryLabel(c)}
            </button>
          ))}
        </div>
      )}

      {error && <p className="form-error" role="alert">{error}</p>}
      {!error && result === null && <p className="page-status">Loading events...</p>}

      {result && result.items.length === 0 && (
        <div className="empty-state">
          {filtered ? (
            <>
              <p>No {category ? `${categoryLabel(category).toLowerCase()} ` : ''}events{q ? ` match "${q}"` : ' yet'}.</p>
              <button className="link-btn" onClick={() => { setText(''); setParams({}); }}>Show all events</button>
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
                  {ev.posterUrl
                    ? <img className="poster" src={ev.posterUrl} alt="" loading="lazy" />
                    : <div className="poster placeholder" aria-hidden="true">{ev.title.charAt(0).toUpperCase()}</div>}
                  <div className="event-card-body">
                    <span className="chip">{categoryLabel(ev.category)}</span>
                    <strong>{ev.title}</strong>
                    <span className="muted">
                      {ev.durationMinutes} min{ev.language ? ` · ${ev.language}` : ''}
                    </span>
                    {ev.description && <p>{ev.description}</p>}
                  </div>
                </Link>
              </li>
            ))}
          </ul>
          {pages > 1 && (
            <nav className="pager" aria-label="Pages">
              <button className="btn ghost" disabled={page === 0} onClick={() => go({ page: page - 1 })}>Previous</button>
              <span>Page {page + 1} of {pages}</span>
              <button className="btn ghost" disabled={page + 1 >= pages} onClick={() => go({ page: page + 1 })}>Next</button>
            </nav>
          )}
        </>
      )}
    </>
  );
}
