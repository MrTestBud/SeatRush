export type Role = 'USER' | 'ORGANIZER' | 'ADMIN';

export interface User {
  id: number;
  name: string;
  email: string;
  role: Role;
  emailVerified: boolean;
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
  posterUrl: string | null;
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
  aisles: string;
}

export interface VenueInput {
  name: string;
  city: string;
  address: string;
  rows: number;
  seatsPerRow: number;
  vipRows: number;
  premiumRows: number;
  aisles: number[];
}

export interface Show {
  id: number;
  eventId: number;
  eventTitle: string;
  venueId: number;
  venueName: string;
  venueCity: string;
  venueAisles: string;
  startsAt: string;
  basePrice: number;
  waitingRoom: boolean;
  dynamicPricing: boolean;
  surgePercent: number;
}

export interface ShowInput {
  startsAt: string;
  basePrice: number;
  premiumPrice?: number;
  vipPrice?: number;
  waitingRoom: boolean;
  dynamicPricing: boolean;
}

export type SeatStatus = 'AVAILABLE' | 'HELD' | 'BOOKED';
export type SeatTier = 'VIP' | 'PREMIUM' | 'REGULAR';

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
  subtotal: number;
  discountAmount: number;
  couponCode: string | null;
  totalAmount: number;
  expiresAt: string | null;
  createdAt: string;
  ticketCode: string | null;
  checkedInAt: string | null;
  cancellation: { allowed: boolean; refundPercent: number; refundAmount: number; note: string } | null;
  refund: { amount: number; status: 'REFUND_PENDING' | 'REFUNDED' | 'REFUND_FAILED' } | null;
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

export interface QueueStatus {
  enabled: boolean;
  admitted: boolean;
  position: number;
  admittedUntil: string | null;
}

export interface Coupon {
  id: number;
  code: string;
  percentOff: number;
  maxUses: number;
  usedCount: number;
  expiresAt: string | null;
}

export interface ShowStats {
  showId: number;
  startsAt: string;
  venueName: string;
  totalSeats: number;
  booked: number;
  held: number;
  available: number;
  confirmedBookings: number;
  checkedIn: number;
  revenue: number;
}

export interface EventStats {
  eventId: number;
  totalSeats: number;
  booked: number;
  confirmedBookings: number;
  checkedIn: number;
  revenue: number;
  shows: ShowStats[];
}

export interface Review {
  id: number;
  author: string;
  rating: number;
  comment: string | null;
  createdAt: string;
  mine: boolean;
}

export interface Reviews {
  average: number;
  count: number;
  canReview: boolean;
  myReview: Review | null;
  items: Review[];
}

export interface CheckInResult {
  bookingId: number;
  eventTitle: string;
  startsAt: string;
  guestName: string;
  seats: string[];
  checkedInAt: string;
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
  // FormData sets its own multipart Content-Type (with the boundary); everything else we send is JSON.
  if (options.body && !(options.body instanceof FormData)) headers.set('Content-Type', 'application/json');
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
    if (res.status === 413) message = 'That file is too large. Posters can be at most 2 MB.';
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

const send = <T>(method: string, path: string, body?: unknown, headers?: Record<string, string>) =>
  request<T>(path, { method, body: body === undefined ? undefined : JSON.stringify(body), headers });
const post = <T>(path: string, body?: unknown, headers?: Record<string, string>) => send<T>('POST', path, body, headers);

export const api = {
  // ---- account
  register: (name: string, email: string, password: string, role: Role) =>
    post<AuthResponse>('/auth/register', { name, email, password, role }),
  login: (email: string, password: string) => post<AuthResponse>('/auth/login', { email, password }),
  me: () => request<User>('/auth/me'),
  verifyEmail: (token: string) => post<User>('/auth/verify-email', { token }),
  resendVerification: () => post<{ message: string }>('/auth/resend-verification'),
  forgotPassword: (email: string) => post<{ message: string }>('/auth/forgot-password', { email }),
  resetPassword: (token: string, password: string) =>
    post<{ message: string }>('/auth/reset-password', { token, password }),

  // ---- events
  events: (q: string, category: string, page: number, size = 12) =>
    request<Page<EventItem>>(
      `/events?q=${encodeURIComponent(q)}&category=${encodeURIComponent(category)}&page=${page}&size=${size}`),
  categories: () => request<string[]>('/events/categories'),
  event: (id: number) => request<EventItem>(`/events/${id}`),
  myEvents: () => request<EventItem[]>('/events/mine'),
  createEvent: (input: EventInput) => post<EventItem>('/events', input),
  updateEvent: (id: number, input: EventInput) => send<EventItem>('PUT', `/events/${id}`, input),
  deleteEvent: (id: number) => request<void>(`/events/${id}`, { method: 'DELETE' }),
  uploadPoster: (id: number, file: File) => {
    const form = new FormData();
    form.append('file', file);
    return request<EventItem>(`/events/${id}/poster`, { method: 'PUT', body: form });
  },
  deletePoster: (id: number) => request<void>(`/events/${id}/poster`, { method: 'DELETE' }),
  eventStats: (id: number) => request<EventStats>(`/events/${id}/stats`),

  reviews: (eventId: number) => request<Reviews>(`/events/${eventId}/reviews`),
  saveReview: (eventId: number, rating: number, comment: string) =>
    send<Reviews>('PUT', `/events/${eventId}/reviews`, { rating, comment }),

  coupons: (eventId: number) => request<Coupon[]>(`/events/${eventId}/coupons`),
  createCoupon: (eventId: number, input: { code: string; percentOff: number; maxUses: number }) =>
    post<Coupon>(`/events/${eventId}/coupons`, input),
  deleteCoupon: (id: number) => request<void>(`/coupons/${id}`, { method: 'DELETE' }),

  // ---- venues and shows
  venues: () => request<Venue[]>('/venues'),
  createVenue: (input: VenueInput) => post<Venue>('/venues', input),

  shows: (eventId: number) => request<Show[]>(`/events/${eventId}/shows`),
  show: (id: number) => request<Show>(`/shows/${id}`),
  createShow: (eventId: number, input: ShowInput & { venueId: number }) => post<Show>(`/events/${eventId}/shows`, input),
  updateShow: (id: number, input: ShowInput) => send<Show>('PUT', `/shows/${id}`, input),
  deleteShow: (id: number) => request<void>(`/shows/${id}`, { method: 'DELETE' }),
  seats: (showId: number) => request<Seat[]>(`/shows/${showId}/seats`),
  queue: (showId: number) => post<QueueStatus>(`/shows/${showId}/queue`),

  // ---- bookings
  // The Idempotency-Key makes a double click or a retried request return the same booking.
  hold: (showId: number, showSeatIds: number[], idempotencyKey: string) =>
    post<Booking>(`/shows/${showId}/holds`, { showSeatIds }, { 'Idempotency-Key': idempotencyKey }),
  bookings: (page = 0) => request<Page<Booking>>(`/bookings?page=${page}&size=20`),
  booking: (id: number) => request<Booking>(`/bookings/${id}`),
  cancelBooking: (id: number) => post<Booking>(`/bookings/${id}/cancel`),
  applyCoupon: (id: number, code: string) => post<Booking>(`/bookings/${id}/coupon`, { code }),
  removeCoupon: (id: number) => request<Booking>(`/bookings/${id}/coupon`, { method: 'DELETE' }),
  pay: (bookingId: number) => post<PaymentOrder>(`/bookings/${bookingId}/pay`),
  completeMockPayment: (orderId: string, outcome: 'success' | 'failure') =>
    post<{ outcome: string }>(`/payments/mock/${orderId}/complete?outcome=${outcome}`),
  checkIn: (code: string) => post<CheckInResult>('/checkin', { code }),
};

export const rupees = (amount: number) =>
  `₹${amount.toLocaleString('en-IN', { minimumFractionDigits: 0, maximumFractionDigits: 2 })}`;

export const showTime = (iso: string) =>
  new Date(iso).toLocaleString('en-IN', {
    weekday: 'short', day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit',
  });

/** 'MOVIE' -> 'Movie' */
export const categoryLabel = (c: string) => c.charAt(0).toUpperCase() + c.slice(1).toLowerCase();

export const errorMessage = (e: unknown) => (e instanceof Error ? e.message : 'Something went wrong. Try again.');
