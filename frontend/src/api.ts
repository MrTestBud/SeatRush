export type Role = 'USER' | 'ORGANIZER' | 'ADMIN';

export interface User {
  id: number;
  name: string;
  email: string;
  role: Role;
}

export interface AuthResponse {
  token: string;
  user: User;
}

export interface EventItem {
  id: number;
  organizerId: number;
  title: string;
  description: string | null;
  category: string;
  language: string | null;
  durationMinutes: number;
}

export interface EventInput {
  title: string;
  description: string;
  category: string;
  language: string;
  durationMinutes: number;
}

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}

export interface Venue {
  id: number;
  name: string;
  city: string;
  address: string;
}

export interface VenueInput {
  name: string;
  city: string;
  address: string;
  rows: number;
  seatsPerRow: number;
}

export interface Show {
  id: number;
  eventId: number;
  eventTitle: string;
  venueId: number;
  venueName: string;
  venueCity: string;
  startsAt: string;
  basePrice: number;
}

export type SeatStatus = 'AVAILABLE' | 'HELD' | 'BOOKED';
export type SeatTier = 'PREMIUM' | 'REGULAR';

export interface Seat {
  showSeatId: number;
  row: string;
  number: number;
  tier: SeatTier;
  status: SeatStatus;
  price: number;
}

export type BookingStatus = 'HELD' | 'PAYMENT_PENDING' | 'CONFIRMED' | 'EXPIRED' | 'CANCELLED';

export interface Booking {
  id: number;
  showId: number;
  eventId: number;
  eventTitle: string;
  venueName: string;
  venueCity: string;
  startsAt: string;
  status: BookingStatus;
  totalAmount: number;
  expiresAt: string | null;
  createdAt: string;
  seats: { showSeatId: number; row: string; number: number; tier: SeatTier; price: number }[];
}

export interface PaymentOrder {
  provider: 'MOCK' | 'RAZORPAY';
  orderId: string;
  amount: number;
  amountMinor: number;
  currency: string;
  keyId: string | null;
}

const TOKEN_KEY = 'seatrush_token';

export const tokenStore = {
  get: () => localStorage.getItem(TOKEN_KEY),
  set: (token: string) => localStorage.setItem(TOKEN_KEY, token),
  clear: () => localStorage.removeItem(TOKEN_KEY),
};

export class ApiError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const headers = new Headers(options.headers);
  if (options.body) headers.set('Content-Type', 'application/json');
  const token = tokenStore.get();
  if (token) headers.set('Authorization', `Bearer ${token}`);

  let res: Response;
  try {
    res = await fetch(`/api${path}`, { ...options, headers });
  } catch {
    throw new ApiError(0, 'Cannot reach the server. Check that the backend is running on port 8080.');
  }

  if (!res.ok) {
    // An expired session anywhere except the login form itself: send the user back to log in.
    if (res.status === 401 && token && !path.startsWith('/auth/')) {
      tokenStore.clear();
      window.location.assign('/login');
    }
    let message = `Request failed (${res.status})`;
    if (res.status === 403) message = 'You are not allowed to do that.';
    try {
      const body = await res.json();
      if (body?.detail) message = body.detail;
    } catch {
      /* response had no JSON body */
    }
    throw new ApiError(res.status, message);
  }
  if (res.status === 204) return undefined as T;
  return res.json() as Promise<T>;
}

const post = <T>(path: string, body?: unknown, headers?: Record<string, string>) =>
  request<T>(path, { method: 'POST', body: body === undefined ? undefined : JSON.stringify(body), headers });

export const api = {
  register: (name: string, email: string, password: string, role: Role) =>
    post<AuthResponse>('/auth/register', { name, email, password, role }),
  login: (email: string, password: string) => post<AuthResponse>('/auth/login', { email, password }),
  me: () => request<User>('/auth/me'),

  events: (q: string, page: number, size = 12) =>
    request<Page<EventItem>>(`/events?q=${encodeURIComponent(q)}&page=${page}&size=${size}`),
  event: (id: number) => request<EventItem>(`/events/${id}`),
  myEvents: () => request<EventItem[]>('/events/mine'),
  createEvent: (input: EventInput) => post<EventItem>('/events', input),
  deleteEvent: (id: number) => request<void>(`/events/${id}`, { method: 'DELETE' }),

  venues: () => request<Venue[]>('/venues'),
  createVenue: (input: VenueInput) => post<Venue>('/venues', input),

  shows: (eventId: number) => request<Show[]>(`/events/${eventId}/shows`),
  show: (id: number) => request<Show>(`/shows/${id}`),
  createShow: (eventId: number, input: { venueId: number; startsAt: string; basePrice: number }) =>
    post<Show>(`/events/${eventId}/shows`, input),
  seats: (showId: number) => request<Seat[]>(`/shows/${showId}/seats`),

  // The Idempotency-Key makes a double click or a retried request return the same booking.
  hold: (showId: number, showSeatIds: number[], idempotencyKey: string) =>
    post<Booking>(`/shows/${showId}/holds`, { showSeatIds }, { 'Idempotency-Key': idempotencyKey }),
  bookings: (page = 0) => request<Page<Booking>>(`/bookings?page=${page}&size=20`),
  booking: (id: number) => request<Booking>(`/bookings/${id}`),
  cancelBooking: (id: number) => post<Booking>(`/bookings/${id}/cancel`),
  pay: (bookingId: number) => post<PaymentOrder>(`/bookings/${bookingId}/pay`),
  completeMockPayment: (orderId: string, outcome: 'success' | 'failure') =>
    post<{ outcome: string }>(`/payments/mock/${orderId}/complete?outcome=${outcome}`),
};

export const rupees = (amount: number) =>
  `₹${amount.toLocaleString('en-IN', { minimumFractionDigits: 0, maximumFractionDigits: 2 })}`;

export const showTime = (iso: string) =>
  new Date(iso).toLocaleString('en-IN', {
    weekday: 'short', day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit',
  });

export const errorMessage = (e: unknown) => (e instanceof Error ? e.message : 'Something went wrong. Try again.');
