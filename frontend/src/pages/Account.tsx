import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import AuthShell from '../AuthShell';
import { api, errorMessage } from '../api';
import { useAuth } from '../auth';

/** Step 1 of a password reset: ask for the address the link should go to. */
export function ForgotPassword() {
  const [email, setEmail] = useState('');
  const [sent, setSent] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await api.forgotPassword(email);
      setSent(true);
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <AuthShell title="Reset your password" subtitle="We'll email you a link to choose a new one.">
      {sent ? (
        <p className="notice" role="status">
          If <strong>{email}</strong> has a SeatRush account, a reset link is on its way. It works once and expires in 30 minutes.
        </p>
      ) : (
        <form onSubmit={submit} noValidate>
          <label className="field">
            <span>Email</span>
            <input type="email" autoComplete="email" value={email} onChange={(e) => setEmail(e.target.value)} required autoFocus />
          </label>
          {error && <p className="form-error" role="alert">{error}</p>}
          <button className="primary" type="submit" disabled={busy || !email}>{busy ? 'Sending...' : 'Send reset link'}</button>
        </form>
      )}
      <p className="switch"><Link to="/login">Back to log in</Link></p>
    </AuthShell>
  );
}

/** Step 2: the link from the email lands here with ?token=... */
export function ResetPassword() {
  const token = useSearchParams()[0].get('token') ?? '';
  const navigate = useNavigate();
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (password.length < 8) { setError('Use at least 8 characters for your password.'); return; }
    setBusy(true);
    setError(null);
    try {
      await api.resetPassword(token, password);
      navigate('/login', { replace: true, state: { notice: 'Your password has been changed. Log in with the new one.' } });
    } catch (err) {
      setError(errorMessage(err));
      setBusy(false);
    }
  }

  if (!token) {
    return (
      <AuthShell title="This link is incomplete" subtitle="Open the link from your reset email again, or ask for a new one.">
        <p className="switch"><Link to="/forgot-password">Send a new reset link</Link></p>
      </AuthShell>
    );
  }

  return (
    <AuthShell title="Choose a new password" subtitle="You'll use it the next time you log in.">
      <form onSubmit={submit} noValidate>
        <label className="field">
          <span>New password</span>
          <div className="password-row">
            <input type={showPassword ? 'text' : 'password'} autoComplete="new-password" value={password}
                   onChange={(e) => setPassword(e.target.value)} required minLength={8} autoFocus />
            <button type="button" className="link-btn" onClick={() => setShowPassword((s) => !s)}>
              {showPassword ? 'Hide' : 'Show'}
            </button>
          </div>
          <small className="hint">At least 8 characters</small>
        </label>
        {error && (
          <p className="form-error" role="alert">
            {error} <Link to="/forgot-password">Send a new link</Link>
          </p>
        )}
        <button className="primary" type="submit" disabled={busy || !password}>{busy ? 'Saving...' : 'Change password'}</button>
      </form>
    </AuthShell>
  );
}

/** The link from the "confirm your email" message lands here. Works whether or not the user is logged in. */
export function VerifyEmail() {
  const token = useSearchParams()[0].get('token') ?? '';
  const { user, refreshUser } = useAuth();
  const [state, setState] = useState<'working' | 'done' | 'failed'>('working');
  const [message, setMessage] = useState('');
  const started = useRef(false);

  useEffect(() => {
    if (started.current) return;   // the link works once: do not send it twice
    started.current = true;
    api.verifyEmail(token)
      .then(() => { setState('done'); refreshUser(); })
      .catch((e) => { setMessage(errorMessage(e)); setState('failed'); });
  }, [token, refreshUser]);

  return (
    <AuthShell
      title={state === 'done' ? 'Email confirmed' : state === 'failed' ? "That link didn't work" : 'Confirming your email...'}
      subtitle={state === 'done' ? "Thanks, you're all set." : state === 'failed' ? message : 'One moment.'}
    >
      {state !== 'working' && (
        <p className="switch">
          <Link to="/">{user ? 'Go to events' : 'Log in'}</Link>
          {state === 'failed' && user && !user.emailVerified && <> · You can send a new link from the banner at the top of any page.</>}
        </p>
      )}
    </AuthShell>
  );
}
