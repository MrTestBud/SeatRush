import { useCallback, useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api, errorMessage, rupees, showTime, type Booking, type PaymentOrder } from '../api';
import { useAuth } from '../auth';

declare global {
  interface Window {
    Razorpay?: new (options: Record<string, unknown>) => { open: () => void };
  }
}

function loadRazorpay(): Promise<void> {
  if (window.Razorpay) return Promise.resolve();
  return new Promise((resolve, reject) => {
    const script = document.createElement('script');
    script.src = 'https://checkout.razorpay.com/v1/checkout.js';
    script.onload = () => resolve();
    script.onerror = () => reject(new Error('Could not load the payment page. Check your connection and try again.'));
    document.body.appendChild(script);
  });
}

/** Seconds left until the hold runs out, ticking once a second. */
function useCountdown(expiresAt: string | null) {
  const [left, setLeft] = useState(0);
  useEffect(() => {
    if (!expiresAt) return;
    const tick = () => setLeft(Math.max(0, Math.round((new Date(expiresAt).getTime() - Date.now()) / 1000)));
    tick();
    const timer = setInterval(tick, 1000);
    return () => clearInterval(timer);
  }, [expiresAt]);
  return left;
}

const STATUS_LABEL: Record<Booking['status'], string> = {
  HELD: 'Seats held',
  PAYMENT_PENDING: 'Waiting for payment',
  CONFIRMED: 'Confirmed',
  EXPIRED: 'Expired',
  CANCELLED: 'Cancelled',
};

export default function BookingPage() {
  const id = Number(useParams().id);
  const { user } = useAuth();
  const [booking, setBooking] = useState<Booking | null>(null);
  const [order, setOrder] = useState<PaymentOrder | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const refresh = useCallback(() => api.booking(id).then(setBooking), [id]);
  const active = booking?.status === 'HELD' || booking?.status === 'PAYMENT_PENDING';
  const secondsLeft = useCountdown(active ? booking.expiresAt : null);

  useEffect(() => {
    refresh().catch((e) => setError(errorMessage(e)));
  }, [refresh]);

  // While the booking is live, keep checking: the webhook (or the expiry job) changes it server-side.
  useEffect(() => {
    if (!active) return;
    const timer = setInterval(() => { refresh().catch(() => undefined); }, 3000);
    return () => clearInterval(timer);
  }, [active, refresh]);

  async function run(action: () => Promise<unknown>) {
    setBusy(true);
    setNotice(null);
    try {
      await action();
      await refresh();
    } catch (e) {
      setNotice(errorMessage(e));
      refresh().catch(() => undefined);
    } finally {
      setBusy(false);
    }
  }

  const startPayment = () => run(async () => {
    const o = await api.pay(id);
    setOrder(o);
    if (o.provider === 'RAZORPAY') {
      await loadRazorpay();
      new window.Razorpay!({
        key: o.keyId,
        order_id: o.orderId,
        amount: o.amountMinor,
        currency: o.currency,
        name: 'SeatRush',
        description: booking?.eventTitle,
        prefill: { name: user?.name, email: user?.email },
        // The booking is confirmed by Razorpay's webhook, not by this callback; we just re-check.
        handler: () => { setNotice('Payment received. Confirming your seats...'); refresh().catch(() => undefined); },
      }).open();
    }
  });

  const completeMock = (outcome: 'success' | 'failure') => run(async () => {
    const res = await api.completeMockPayment(order!.orderId, outcome);
    if (res.outcome === 'FAILED') {
      setOrder(null);
      setNotice('That payment failed. Your seats are still held, so you can try again.');
    }
  });

  const cancel = () => run(async () => {
    await api.cancelBooking(id);
    setOrder(null);
  });

  if (error) return <><p className="form-error" role="alert">{error}</p><Link to="/bookings">My bookings</Link></>;
  if (!booking) return <p className="page-status">Loading booking...</p>;

  const mm = String(Math.floor(secondsLeft / 60)).padStart(2, '0');
  const ss = String(secondsLeft % 60).padStart(2, '0');

  return (
    <div className="booking-page">
      <Link to="/bookings" className="back">← My bookings</Link>
      <div className={`ticket ${booking.status.toLowerCase()}`}>
        <div className="ticket-head">
          <span className={`status ${booking.status.toLowerCase()}`}>{STATUS_LABEL[booking.status]}</span>
          <span className="muted">Booking #{booking.id}</span>
        </div>
        <h1>{booking.eventTitle}</h1>
        <p className="muted">{showTime(booking.startsAt)} · {booking.venueName}, {booking.venueCity}</p>

        <ul className="ticket-seats">
          {booking.seats.map((s) => (
            <li key={s.showSeatId}>
              <span><strong>{s.row}{s.number}</strong> <span className="muted">{s.tier.toLowerCase()}</span></span>
              <span>{rupees(s.price)}</span>
            </li>
          ))}
          <li className="ticket-total"><span>Total</span><span>{rupees(booking.totalAmount)}</span></li>
        </ul>

        {notice && <p className="form-error" role="alert">{notice}</p>}

        {active && (
          <>
            <p className={`countdown${secondsLeft <= 60 ? ' urgent' : ''}`} aria-live="off">
              {secondsLeft > 0
                ? <>Your seats are held for <strong>{mm}:{ss}</strong></>
                : 'Your hold has run out. Releasing seats...'}
            </p>

            {order?.provider === 'MOCK' ? (
              <div className="mock-pay">
                <p><strong>Test payment</strong> <span className="muted">No real money moves. Choose how the payment should end.</span></p>
                <div className="actions">
                  <button className="btn" disabled={busy} onClick={() => completeMock('success')}>
                    Pay {rupees(booking.totalAmount)}
                  </button>
                  <button className="btn ghost" disabled={busy} onClick={() => completeMock('failure')}>
                    Simulate a failed payment
                  </button>
                </div>
              </div>
            ) : (
              <div className="actions">
                <button className="btn" disabled={busy || secondsLeft === 0} onClick={startPayment}>
                  {booking.status === 'PAYMENT_PENDING' ? 'Continue to payment' : `Pay ${rupees(booking.totalAmount)}`}
                </button>
                <button className="btn ghost" disabled={busy} onClick={cancel}>Release seats</button>
              </div>
            )}
          </>
        )}

        {booking.status === 'CONFIRMED' && (
          <p className="ticket-note">You're going! A confirmation email is on its way to {user?.email}.</p>
        )}
        {booking.status === 'EXPIRED' && (
          <p className="ticket-note">
            The hold ran out before payment finished, so the seats were released.{' '}
            <Link to={`/shows/${booking.showId}`}>Pick seats again</Link>
          </p>
        )}
        {booking.status === 'CANCELLED' && (
          <p className="ticket-note">
            You released these seats. <Link to={`/shows/${booking.showId}`}>Pick seats again</Link>
          </p>
        )}
      </div>
    </div>
  );
}
