import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Link, Navigate } from 'react-router-dom';
import { api, errorMessage, showTime, type CheckInResult } from '../api';
import { useAuth } from '../auth';

// Built into Chromium-based browsers; not in the TypeScript DOM library yet.
interface BarcodeDetectorLike {
  detect(source: HTMLVideoElement): Promise<{ rawValue: string }[]>;
}
declare global {
  interface Window {
    BarcodeDetector?: new (options: { formats: string[] }) => BarcodeDetectorLike;
  }
}

type Outcome = { ok: true; result: CheckInResult } | { ok: false; code: string; message: string };

/** Door check-in for organizers: scan the QR code on a guest's ticket, or type the code under it. */
export default function CheckIn() {
  const { user } = useAuth();
  const [code, setCode] = useState('');
  const [outcome, setOutcome] = useState<Outcome | null>(null);
  const [busy, setBusy] = useState(false);
  const [scanning, setScanning] = useState(false);
  const [cameraError, setCameraError] = useState<string | null>(null);
  const video = useRef<HTMLVideoElement>(null);
  const lastScanned = useRef('');
  const canScan = typeof window !== 'undefined' && !!window.BarcodeDetector && !!navigator.mediaDevices?.getUserMedia;

  async function admit(raw: string) {
    const value = raw.trim();
    if (!value) return;
    setBusy(true);
    try {
      setOutcome({ ok: true, result: await api.checkIn(value) });
      setCode('');
    } catch (e) {
      setOutcome({ ok: false, code: value, message: errorMessage(e) });
    } finally {
      setBusy(false);
    }
  }

  // Camera scanning: look for a QR code a few times a second while the camera is on.
  useEffect(() => {
    if (!scanning) return;
    let stream: MediaStream | undefined;
    let timer: ReturnType<typeof setInterval> | undefined;
    let stopped = false;
    const detector = new window.BarcodeDetector!({ formats: ['qr_code'] });

    navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' } })
      .then((s) => {
        if (stopped) { s.getTracks().forEach((t) => t.stop()); return; }
        stream = s;
        if (video.current) {
          video.current.srcObject = s;
          video.current.play().catch(() => undefined);
        }
        timer = setInterval(async () => {
          if (!video.current || video.current.readyState < 2) return;
          try {
            const found = await detector.detect(video.current);
            const value = found[0]?.rawValue;
            // The same ticket stays in view for a while: only act on it once.
            if (value && value !== lastScanned.current) {
              lastScanned.current = value;
              admit(value);
            }
          } catch {
            /* a frame that could not be read: try the next one */
          }
        }, 400);
      })
      .catch(() => {
        setCameraError('Could not open the camera. Allow camera access for this site, or type the code instead.');
        setScanning(false);
      });

    return () => {
      stopped = true;
      if (timer) clearInterval(timer);
      stream?.getTracks().forEach((t) => t.stop());
    };
  }, [scanning]);

  if (user?.role === 'USER') return <Navigate to="/" replace />;

  const submit = (e: FormEvent) => { e.preventDefault(); admit(code); };

  return (
    <div className="checkin">
      <Link to="/host" className="back">← Host console</Link>
      <h1>Check guests in</h1>
      <p className="muted lead">Scan the QR code on the guest's ticket, or type the code printed under it. Each ticket admits its party once.</p>

      {outcome && (
        outcome.ok ? (
          <div className="checkin-result ok" role="status">
            <strong>Admit {outcome.result.seats.length} · {outcome.result.guestName}</strong>
            <span>Seats {outcome.result.seats.join(', ')}</span>
            <span className="muted">{outcome.result.eventTitle} · {showTime(outcome.result.startsAt)} · booking #{outcome.result.bookingId}</span>
          </div>
        ) : (
          <div className="checkin-result bad" role="alert">
            <strong>Do not admit</strong>
            <span>{outcome.message}</span>
            <span className="muted">{outcome.code}</span>
          </div>
        )
      )}

      <form className="panel checkin-form" onSubmit={submit}>
        <label className="field"><span>Ticket code</span>
          <input type="text" value={code} onChange={(e) => setCode(e.target.value)} placeholder="SR-123-ABCDEF0123456789"
                 autoFocus autoCapitalize="characters" autoComplete="off" spellCheck={false} />
        </label>
        <div className="actions">
          <button className="btn" type="submit" disabled={busy || code.trim() === ''}>{busy ? 'Checking...' : 'Check in'}</button>
          {canScan && (
            <button className="btn ghost" type="button" onClick={() => { setCameraError(null); lastScanned.current = ''; setScanning((v) => !v); }}>
              {scanning ? 'Stop camera' : 'Scan with camera'}
            </button>
          )}
        </div>
        {!canScan && <p className="hint">This browser cannot scan QR codes with the camera (Chrome on Android or macOS can). Typing the code works everywhere.</p>}
        {cameraError && <p className="form-error" role="alert">{cameraError}</p>}
        {scanning && <video ref={video} className="scanner" muted playsInline aria-label="Camera view for scanning tickets" />}
      </form>
    </div>
  );
}
