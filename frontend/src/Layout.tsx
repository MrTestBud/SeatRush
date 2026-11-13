import { useState } from 'react';
import { NavLink, Outlet } from 'react-router-dom';
import { api, errorMessage } from './api';
import { useAuth } from './auth';

/** Top bar + page body shared by every signed-in page. */
export default function Layout() {
  const { user, logout } = useAuth();
  const canHost = user?.role === 'ORGANIZER' || user?.role === 'ADMIN';

  return (
    <div className="app">
      <header className="topbar">
        <NavLink to="/" className="brand dark">SeatRush</NavLink>
        <nav className="nav" aria-label="Main">
          <NavLink to="/" end>Events</NavLink>
          <NavLink to="/bookings">My bookings</NavLink>
          {canHost && <NavLink to="/host">Host</NavLink>}
        </nav>
        <div className="who">
          <span className="who-name">{user?.name}</span>
          <span className="badge">{user?.role.toLowerCase()}</span>
          <button className="link-btn" onClick={logout}>Log out</button>
        </div>
      </header>
      {user && !user.emailVerified && <VerifyBanner email={user.email} />}
      <main className="page">
        <Outlet />
      </main>
    </div>
  );
}

function VerifyBanner({ email }: { email: string }) {
  const [state, setState] = useState<'idle' | 'sending' | 'sent'>('idle');
  const [error, setError] = useState<string | null>(null);

  async function resend() {
    setState('sending');
    setError(null);
    try {
      await api.resendVerification();
      setState('sent');
    } catch (e) {
      setError(errorMessage(e));
      setState('idle');
    }
  }

  return (
    <div className="verify-banner" role="status">
      {state === 'sent' ? (
        <span>A new confirmation link is on its way to {email}.</span>
      ) : (
        <>
          <span>Confirm your email: we sent a link to {email}.{error ? ` ${error}` : ''}</span>
          <button className="link-btn" onClick={resend} disabled={state === 'sending'}>
            {state === 'sending' ? 'Sending...' : 'Send it again'}
          </button>
        </>
      )}
    </div>
  );
}
