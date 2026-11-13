import { Navigate, Route, Routes } from 'react-router-dom';
import { GuestOnly, RequireAuth } from './auth';
import Layout from './Layout';
import { ForgotPassword, ResetPassword, VerifyEmail } from './pages/Account';
import BookingPage from './pages/BookingPage';
import CheckIn from './pages/CheckIn';
import EventDetail from './pages/EventDetail';
import Home from './pages/Home';
import Host from './pages/Host';
import Login from './pages/Login';
import MyBookings from './pages/MyBookings';
import Register from './pages/Register';
import ShowSeats from './pages/ShowSeats';

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<GuestOnly><Login /></GuestOnly>} />
      <Route path="/register" element={<GuestOnly><Register /></GuestOnly>} />
      <Route path="/forgot-password" element={<GuestOnly><ForgotPassword /></GuestOnly>} />
      {/* Links from emails: they must work whether or not this browser is logged in. */}
      <Route path="/reset-password" element={<ResetPassword />} />
      <Route path="/verify-email" element={<VerifyEmail />} />
      <Route element={<RequireAuth><Layout /></RequireAuth>}>
        <Route path="/" element={<Home />} />
        <Route path="/events/:id" element={<EventDetail />} />
        <Route path="/shows/:id" element={<ShowSeats />} />
        <Route path="/bookings" element={<MyBookings />} />
        <Route path="/bookings/:id" element={<BookingPage />} />
        <Route path="/host" element={<Host />} />
        <Route path="/host/checkin" element={<CheckIn />} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
