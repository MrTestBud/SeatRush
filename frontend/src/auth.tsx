import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react';
import { Navigate } from 'react-router-dom';
import { api, tokenStore, type Role, type User } from './api';

interface AuthState {
  user: User | null;
  loading: boolean;
  login: (email: string, password: string) => Promise<void>;
  register: (name: string, email: string, password: string, role: Role) => Promise<void>;
  logout: () => void;
  /** Re-reads the account (for example after the email address was confirmed). */
  refreshUser: () => void;
}

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState<boolean>(() => tokenStore.get() !== null);

  // Restore the session on page load if a token is saved.
  useEffect(() => {
    if (!tokenStore.get()) return;
    api
      .me()
      .then(setUser)
      .catch(() => tokenStore.clear())
      .finally(() => setLoading(false));
  }, []);

  const refreshUser = useCallback(() => {
    if (tokenStore.get()) api.me().then(setUser).catch(() => undefined);
  }, []);

  const value: AuthState = {
    refreshUser,
    user,
    loading,
    login: async (email, password) => {
      const res = await api.login(email, password);
      tokenStore.set(res.token);
      setUser(res.user);
    },
    register: async (name, email, password, role) => {
      const res = await api.register(name, email, password, role);
      tokenStore.set(res.token);
      setUser(res.user);
    },
    logout: () => {
      tokenStore.clear();
      setUser(null);
    },
  };

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside AuthProvider');
  return ctx;
}

export function RequireAuth({ children }: { children: ReactNode }) {
  const { user, loading } = useAuth();
  if (loading) return <p className="page-status">Loading your session...</p>;
  return user ? <>{children}</> : <Navigate to="/login" replace />;
}

export function GuestOnly({ children }: { children: ReactNode }) {
  const { user, loading } = useAuth();
  if (loading) return <p className="page-status">Loading your session...</p>;
  return user ? <Navigate to="/" replace /> : <>{children}</>;
}
