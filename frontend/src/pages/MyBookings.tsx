import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api, errorMessage, rupees, showTime, type Booking } from '../api';

const LABEL: Record<Booking['status'], string> = {
  HELD: 'Seats held',
  PAYMENT_PENDING: 'Waiting for payment',
  CONFIRMED: 'Confirmed',
  EXPIRED: 'Expired',
  CANCELLED: 'Cancelled',
};

export default function MyBookings() {
  const [bookings, setBookings] = useState<Booking[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.bookings().then((p) => setBookings(p.items)).catch((e) => setError(errorMessage(e)));
  }, []);

  return (
    <>
      <div className="page-head"><h1>My bookings</h1></div>
      {error && <p className="form-error" role="alert">{error}</p>}
      {!error && bookings === null && <p className="page-status">Loading bookings...</p>}
      {bookings && bookings.length === 0 && (
        <div className="empty-state">
          <p>You haven't booked anything yet.</p>
          <Link className="btn" to="/">Browse events</Link>
        </div>
      )}
      {bookings && bookings.length > 0 && (
        <ul className="booking-list">
          {bookings.map((b) => (
            <li key={b.id}>
              <Link to={`/bookings/${b.id}`}>
                <div>
                  <strong>{b.eventTitle}</strong>
                  <span className="muted">{showTime(b.startsAt)} · {b.venueName}</span>
                  <span className="muted">
                    {b.seats.map((s) => `${s.row}${s.number}`).join(', ')} · {rupees(b.totalAmount)}
                  </span>
                </div>
                <span className={`status ${b.status.toLowerCase()}`}>{LABEL[b.status]}</span>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </>
  );
}
