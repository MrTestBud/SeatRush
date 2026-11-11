import { useState, type ReactNode } from 'react';

const ROWS = 6;
const COLS = 8;
// seats already "taken" in the preview, as row-col keys
const TAKEN = new Set(['0-2', '0-3', '1-5', '2-1', '2-2', '3-6', '4-0', '4-4', '4-5', '5-3']);

/** The one memorable element: a seat map you can click, hinting at what the product does. */
function SeatPreview() {
  const [picked, setPicked] = useState<Set<string>>(new Set(['2-4', '2-5']));

  const toggle = (key: string) =>
    setPicked((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });

  return (
    <div className="seat-preview">
      <div className="screen-bar" aria-hidden="true">Screen</div>
      <div className="seat-grid" role="group" aria-label="Sample seat map">
        {Array.from({ length: ROWS * COLS }, (_, i) => {
          const r = Math.floor(i / COLS);
          const c = i % COLS;
          const key = `${r}-${c}`;
          const taken = TAKEN.has(key);
          const on = picked.has(key);
          return (
            <button
              key={key}
              type="button"
              className={`seat${taken ? ' taken' : ''}${on ? ' picked' : ''}`}
              disabled={taken}
              aria-pressed={on}
              aria-label={`Row ${String.fromCharCode(65 + r)}, seat ${c + 1}${taken ? ', taken' : ''}`}
              onClick={() => toggle(key)}
            />
          );
        })}
      </div>
      <p className="seat-caption">
        {picked.size === 0 ? 'Pick your seats' : `${picked.size} seat${picked.size > 1 ? 's' : ''} held for you`}
      </p>
    </div>
  );
}

export default function AuthShell({ title, subtitle, children }: { title: string; subtitle: string; children: ReactNode }) {
  return (
    <main className="auth-layout">
      <section className="auth-aside">
        <div className="brand">SeatRush</div>
        <div>
          <h2 className="aside-title">The last seat is gone in seconds.</h2>
          <p className="aside-copy">Book yours before anyone else does.</p>
        </div>
        <SeatPreview />
      </section>
      <section className="auth-main">
        <div className="auth-card">
          <h1>{title}</h1>
          <p className="auth-sub">{subtitle}</p>
          {children}
        </div>
      </section>
    </main>
  );
}
