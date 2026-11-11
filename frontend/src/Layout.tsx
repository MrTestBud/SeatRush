import { NavLink, Outlet } from 'react-router-dom';
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
      <main className="page">
        <Outlet />
      </main>
    </div>
  );
}
