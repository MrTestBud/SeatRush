import { useState, type FormEvent } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import AuthShell from '../AuthShell';
import { useAuth } from '../auth';
import type { Role } from '../api';

export default function Register() {
  const { register } = useAuth();
  const navigate = useNavigate();
  const [name, setName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [role, setRole] = useState<Role>('USER');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const passwordOk = password.length >= 8;

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    if (!passwordOk) {
      setError('Use at least 8 characters for your password.');
      return;
    }
    setBusy(true);
    try {
      await register(name, email, password, role);
      navigate('/', { replace: true });
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Something went wrong. Try again.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <AuthShell title="Create your account" subtitle="It takes under a minute.">
      <form onSubmit={onSubmit} noValidate>
        <label className="field">
          <span>Full name</span>
          <input type="text" autoComplete="name" value={name} onChange={(e) => setName(e.target.value)} required autoFocus />
        </label>
        <label className="field">
          <span>Email</span>
          <input type="email" autoComplete="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
        </label>
        <label className="field">
          <span>Password</span>
          <div className="password-row">
            <input
              type={showPassword ? 'text' : 'password'}
              autoComplete="new-password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
              minLength={8}
            />
            <button type="button" className="link-btn" onClick={() => setShowPassword((s) => !s)}>
              {showPassword ? 'Hide' : 'Show'}
            </button>
          </div>
          <small className={passwordOk || password.length === 0 ? 'hint' : 'hint warn'}>At least 8 characters</small>
        </label>

        <fieldset className="role-picker">
          <legend>I want to</legend>
          <label className={role === 'USER' ? 'role on' : 'role'}>
            <input type="radio" name="role" checked={role === 'USER'} onChange={() => setRole('USER')} />
            <strong>Book tickets</strong>
            <span>Browse events and reserve seats</span>
          </label>
          <label className={role === 'ORGANIZER' ? 'role on' : 'role'}>
            <input type="radio" name="role" checked={role === 'ORGANIZER'} onChange={() => setRole('ORGANIZER')} />
            <strong>Host events</strong>
            <span>Create events, venues and shows</span>
          </label>
        </fieldset>

        {error && <p className="form-error" role="alert">{error}</p>}
        <button className="primary" type="submit" disabled={busy || !name || !email || !password}>
          {busy ? 'Creating account...' : 'Create account'}
        </button>
      </form>
      <p className="switch">
        Already have an account? <Link to="/login">Log in</Link>
      </p>
    </AuthShell>
  );
}
