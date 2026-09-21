import { useEffect, useId, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { motion, useReducedMotion } from 'framer-motion';
import { Eye, EyeOff, AlertCircle, Lock } from 'lucide-react';
import { AuthLayout } from './AuthLayout';
import { authApi, LoginLockedError } from '../../api/auth';
import { useAuthStore } from '../../store/authStore';
import { consumeSessionMessage } from '../../lib/authFetch';
import loginReference from '../../assets/login-reference.png';

// Persists only the lock expiry the server already returned, so a page refresh keeps
// showing the locked state without sending another login request. Not a client-side
// attempt counter — the server remains the sole source of truth for attempt counting.
const LOCK_STORAGE_KEY = 'onehr:accountLock';

interface StoredLock {
  email: string;
  lockedUntil: string;
}

function readStoredLock(): StoredLock | null {
  try {
    const raw = localStorage.getItem(LOCK_STORAGE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as StoredLock;
    if (!parsed.lockedUntil || !parsed.email) {
      localStorage.removeItem(LOCK_STORAGE_KEY);
      return null;
    }
    return parsed;
  } catch {
    localStorage.removeItem(LOCK_STORAGE_KEY);
    return null;
  }
}

function isLockActive(lock: StoredLock | null): lock is StoredLock {
  return !!lock && new Date(lock.lockedUntil).getTime() > Date.now();
}

// Whole hours only, rounded up so the count only ever drops on an hour boundary and never
// reads "0 hours" while still locked (e.g. 4h0m0s -> "4 hours", 59s left -> still "1 hour").
function formatRemainingLockTime(lockedUntilIso: string): string {
  const msRemaining = new Date(lockedUntilIso).getTime() - Date.now();
  const hours = Math.max(1, Math.ceil(msRemaining / (1000 * 60 * 60)));
  return `Please try again after ${hours} ${hours === 1 ? 'hour' : 'hours'}.`;
}

function MicrosoftIcon() {
  return (
    <svg width="18" height="18" viewBox="0 0 21 21" aria-hidden="true" focusable="false">
      <rect x="0"  y="0"  width="10" height="10" fill="#F25022" />
      <rect x="11" y="0"  width="10" height="10" fill="#7FBA00" />
      <rect x="0"  y="11" width="10" height="10" fill="#00A4EF" />
      <rect x="11" y="11" width="10" height="10" fill="#FFB900" />
    </svg>
  );
}

const containerVariants = { hidden: {}, show: { transition: { staggerChildren: 0.04 } } };
const itemVariants = {
  hidden: { opacity: 0, y: 10 },
  show:   { opacity: 1, y: 0, transition: { duration: 0.28, ease: [0.23, 1, 0.32, 1] as const } },
};

// Field rectangles measured directly from login-reference.png (1671x941),
// expressed as percentages of the full image. The frame these map onto
// always preserves that exact aspect ratio (see .nf-login-frame below), so
// plain percentage-of-container positioning keeps these overlays aligned to
// the artwork's fields at any viewport size.
const FIELD_RECT = {
  email:    { left: 67.32, top: 39.53, width: 25.91, height: 5.21 },
  password: { left: 67.32, top: 47.50, width: 25.91, height: 5.31 },
  toggle:   { left: 89.73, top: 47.50, width: 3.5,   height: 5.31 },
  forgot:   { left: 86.53, top: 53.8,  width: 6.9,   height: 3.0  },
  signIn:   { left: 67.32, top: 59.40, width: 25.91, height: 5.21 },
  sso:      { left: 67.32, top: 73.01, width: 25.91, height: 5.21 },
  // Taller than the other rows and positioned over the card's own logo lockup
  // (rather than squeezed into the short gap above Email) because the
  // account-lockout message is long enough to wrap 3+ lines — a single
  // short-error-sized box would overflow into "Welcome back" beneath it.
  error:    { left: 67.32, top: 16.5,  width: 25.91, height: 11.5 },
} as const;

function rectStyle(r: { left: number; top: number; width: number; height: number }): React.CSSProperties {
  return { position: 'absolute', left: `${r.left}%`, top: `${r.top}%`, width: `${r.width}%`, height: `${r.height}%` };
}

// Below this width, login-reference.png's fixed 1671x941 composition would
// scale its login card down to an unreadable/untappable size, so we fall
// back to the existing plain (non-image) mobile-optimized card below —
// with its own lockout/session-message handling and fluid clamp() sizing —
// instead of distorting or shrinking the artwork past the point of usability.
const IMAGE_LAYOUT_MIN_WIDTH = 701;

function useIsImageLayout() {
  const [isImageLayout, setIsImageLayout] = useState(() =>
    typeof window !== 'undefined' ? window.matchMedia(`(min-width: ${IMAGE_LAYOUT_MIN_WIDTH}px)`).matches : true
  );
  useEffect(() => {
    const mq = window.matchMedia(`(min-width: ${IMAGE_LAYOUT_MIN_WIDTH}px)`);
    const handler = () => setIsImageLayout(mq.matches);
    mq.addEventListener('change', handler);
    return () => mq.removeEventListener('change', handler);
  }, []);
  return isImageLayout;
}

interface ImageLoginProps {
  lockedMessage: string | null;
  hasError: boolean; error: string | null; errorId: string;
  email: string; setEmail: (v: string) => void; emailId: string; emailRef: React.RefObject<HTMLInputElement | null>;
  password: string; setPassword: (v: string) => void; passId: string;
  showPass: boolean; setShowPass: (updater: (v: boolean) => boolean) => void;
  submitting: boolean; locked: boolean; onSubmit: (e: React.FormEvent) => void;
}

// Full-viewport (100vw x 100dvh, no scrollbar) rendering of login-reference.png. The frame is sized
// with CSS max() rather than min()/max-width — i.e. "cover" instead of "contain" — so it always
// scales up to fully cover BOTH viewport dimensions at once. Because aspectRatio stays fixed at
// 1671/941, that scale-up is perfectly uniform (no stretching, no distortion): on a viewport
// proportionally wider than the artwork, the frame overflows vertically instead of leaving empty
// space on the sides, and the outer wrapper's overflow:hidden trims that overflow symmetrically
// (top and bottom, centered) — the same amount off each edge, so it only ever eats into the
// artwork's own outer/background margin, never shifting the vertically-centered card/hero/globe.
// FIELD_RECT's percentages stay exactly correct because they're relative to this same box, which
// is still the artwork's true, undistorted aspect ratio — just larger than the viewport, not
// letterboxed to fit inside it.
// Interactive overlays are positioned as percentages of that box only, so they scale and move
// together with it, never with the viewport directly. The account-lockout state from the parent is
// wired in here (disabled fields, a locked banner in place of the generic error, the Forgot
// Password link hidden) so the security behavior survives this visual layer exactly as it works in
// the fallback card below.
function ImageLogin({
  lockedMessage, hasError, error, errorId, email, setEmail, emailId, emailRef,
  password, setPassword, passId, showPass, setShowPass, submitting, locked, onSubmit,
}: ImageLoginProps) {
  const bannerMessage = lockedMessage ?? (hasError ? error : null);
  // Shared by the password input and its show/hide toggle button so the two sit on one
  // continuous background instead of the toggle's own fixed shade showing as a seam.
  const passwordFieldBg = password ? 'rgb(16,28,38)' : 'transparent';
  return (
    <div style={{ position: 'relative', width: '100vw', height: '100dvh', overflow: 'hidden', background: '#060608' }}>
      <div style={{ position: 'absolute', inset: 0, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        <div
          className="nf-login-frame"
          style={{
            position: 'relative', width: 'max(100%, calc(100dvh * 1671 / 941))', aspectRatio: '1671 / 941',
            boxShadow: '0 40px 120px rgba(0,0,0,.55)',
          }}
        >
          <img
            src={loginReference}
            alt="NForce OneHR — Welcome back. Access your OneHR account."
            draggable={false}
            style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', display: 'block', userSelect: 'none', pointerEvents: 'none' }}
          />

          {bannerMessage && (
            <div
              role="alert" aria-live={lockedMessage ? 'assertive' : 'polite'} id={errorId}
              style={{
                ...rectStyle(FIELD_RECT.error),
                display: 'flex', alignItems: 'flex-start', gap: 8, padding: '3% 3%', borderRadius: 8,
                background: 'rgba(10,11,14,.92)', border: '1px solid rgba(228,55,61,.4)', color: '#f4a5a8',
                boxSizing: 'border-box', overflow: 'auto',
              }}
            >
              {lockedMessage
                ? <Lock size={16} style={{ flexShrink: 0, marginTop: 1, color: 'var(--risk)' }} aria-hidden="true" />
                : <AlertCircle size={16} style={{ flexShrink: 0, marginTop: 1, color: 'var(--risk)' }} aria-hidden="true" />}
              <span style={{ fontSize: 13, lineHeight: 1.4 }}>{bannerMessage}</span>
            </div>
          )}

          <form onSubmit={onSubmit} noValidate>
            <label htmlFor={emailId} style={{ position: 'absolute', width: 1, height: 1, overflow: 'hidden', clip: 'rect(0 0 0 0)' }}>Email</label>
            <input
              ref={emailRef} id={emailId} type="text" inputMode="email" autoComplete="email" placeholder=""
              value={email} onChange={(e) => setEmail(e.target.value)}
              disabled={locked}
              aria-invalid={hasError} aria-describedby={bannerMessage ? errorId : undefined}
              style={{
                ...rectStyle(FIELD_RECT.email),
                // The field's placeholder icon/text are baked into the reference art and show
                // through this transparent input while empty; once real text is entered, an
                // opaque fill (colour-matched to the art's own field interior) masks that
                // baked-in placeholder so the two don't overlap/garble.
                background: email ? 'rgb(16,28,38)' : 'transparent',
                border: 'none', outline: 'none', boxSizing: 'border-box',
                // Percentage padding always resolves against the containing block's width (this
                // element's positioning ancestor, i.e. roughly the full frame), never against the
                // element's own computed width, so it has to be re-based as
                // <fraction-of-FIELD_RECT.email.width> * FIELD_RECT.email.width to land at the
                // right visual inset. That fraction (13.63%) is measured directly from the baked
                // artwork itself: the mail icon plus its gap to the "Email address" placeholder's
                // left edge spans pixels 0-59 of the field's 433px-wide row in login-reference.png
                // (67.32%..93.23% of the 1671px-wide source at its placeholder's own vertical
                // center) — 59/433 ≈ 13.63%. This still scales with the frame like every other
                // FIELD_RECT-driven value.
                color: '#fff', fontSize: 15, fontFamily: 'Inter, sans-serif', padding: '0 0.52% 0 3.53%',
                opacity: locked ? 0.55 : 1, cursor: locked ? 'not-allowed' : 'text',
              }}
            />

            <label htmlFor={passId} style={{ position: 'absolute', width: 1, height: 1, overflow: 'hidden', clip: 'rect(0 0 0 0)' }}>Password</label>
            <input
              id={passId} type={showPass ? 'text' : 'password'} autoComplete="current-password" placeholder=""
              value={password} onChange={(e) => setPassword(e.target.value)}
              disabled={locked}
              aria-invalid={hasError} aria-describedby={bannerMessage ? errorId : undefined}
              style={{
                ...rectStyle(FIELD_RECT.password),
                background: passwordFieldBg,
                border: 'none', outline: 'none', boxSizing: 'border-box',
                // Same containing-block-vs-own-width padding fix as the email input above — the
                // lock icon measures to the same 13.63% inset in the artwork.
                color: '#fff', fontSize: 15, fontFamily: 'Inter, sans-serif', padding: '0 1.04% 0 3.53%',
                opacity: locked ? 0.55 : 1, cursor: locked ? 'not-allowed' : 'text',
              }}
            />
            <button
              type="button" aria-label={showPass ? 'Hide password' : 'Show password'} onClick={() => setShowPass((v) => !v)}
              disabled={locked}
              style={{
                ...rectStyle(FIELD_RECT.toggle),
                // Matches the password input's own background exactly (rather than a fixed shade)
                // so the two sit flush with no visible seam, in every state.
                background: passwordFieldBg,
                border: 'none', cursor: locked ? 'not-allowed' : 'pointer', color: 'var(--txt-dim)',
                display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 0,
                opacity: locked ? 0.55 : 1,
              }}
            >
              {/* The reference artwork already bakes a static eye-off glyph into this exact spot,
                  visible through this button while its background is transparent (empty field) —
                  rendering our own icon there too is what doubled it up. Once the field has a
                  value, this button's background turns opaque (see passwordFieldBg) and covers
                  that baked glyph entirely, so only then do we render our own icon — keeping
                  exactly one eye visible in both states, not two. */}
              {password && (showPass ? <EyeOff size={16} aria-hidden="true" /> : <Eye size={16} aria-hidden="true" />)}
            </button>

            {!locked && (
              <Link
                to="/forgot-password"
                aria-label="Forgot password?"
                style={{ ...rectStyle(FIELD_RECT.forgot), display: 'block' }}
              />
            )}

            <button
              type="submit" disabled={submitting || locked} aria-label={submitting ? 'Signing in…' : 'Sign In'}
              style={{
                ...rectStyle(FIELD_RECT.signIn),
                background: submitting ? 'rgba(122,12,16,.94)' : 'transparent',
                border: 'none', borderRadius: 8, cursor: (submitting || locked) ? 'not-allowed' : 'pointer',
                display: 'flex', alignItems: 'center', justifyContent: 'center',
                color: '#fff', fontSize: 15, fontWeight: 600, fontFamily: 'Inter, sans-serif',
                opacity: locked ? 0.55 : 1,
              }}
            >
              {submitting ? 'Signing in…' : ''}
            </button>
          </form>

          <button
            type="button"
            disabled
            title="Microsoft SSO arrives in a later phase, once Azure AD coordination is ready"
            aria-label="Microsoft SSO — coming soon"
            style={{ ...rectStyle(FIELD_RECT.sso), background: 'transparent', border: 'none', cursor: 'not-allowed', padding: 0 }}
          />
        </div>
      </div>
    </div>
  );
}

export default function Login() {
  const navigate   = useNavigate();
  const setAuth    = useAuthStore((s) => s.setAuth);
  const clearAuth  = useAuthStore((s) => s.clearAuth);
  const reduced    = useReducedMotion();
  const isImageLayout = useIsImageLayout();
  const emailId    = useId();
  const passId     = useId();
  const errorId    = useId();

  const [email,      setEmail]      = useState('');
  const [password,   setPassword]   = useState('');
  const [showPass,   setShowPass]   = useState(false);
  // A password-change/session-invalidation redirect (see lib/authFetch.ts) leaves a one-shot
  // message here for this exact banner to pick up on first render.
  const [error,      setError]      = useState<string | null>(() => consumeSessionMessage());
  const [submitting, setSubmitting] = useState(false);
  const [lock,       setLock]       = useState<StoredLock | null>(() => readStoredLock());
  const emailRef = useRef<HTMLInputElement>(null);

  const locked = isLockActive(lock);
  // Forces a re-render each tick so the displayed "N hours remaining" count stays current —
  // the value itself is always recomputed fresh from lock.lockedUntil vs Date.now(), never stored.
  const [, setTick] = useState(0);

  // Re-render once the lock naturally expires so the fields re-enable without requiring a
  // refresh (e.g. after a refresh that lands mid-lock, or while the tab is just left open).
  useEffect(() => {
    if (!lock) return;
    const msRemaining = new Date(lock.lockedUntil).getTime() - Date.now();
    if (msRemaining <= 0) {
      localStorage.removeItem(LOCK_STORAGE_KEY);
      setLock(null);
      return;
    }
    const expiryTimer = window.setTimeout(() => {
      localStorage.removeItem(LOCK_STORAGE_KEY);
      setLock(null);
    }, msRemaining);
    const displayTicker = window.setInterval(() => setTick((t) => t + 1), 30_000);
    return () => {
      window.clearTimeout(expiryTimer);
      window.clearInterval(displayTicker);
    };
  }, [lock]);

  async function handleCredentialSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (isLockActive(readStoredLock())) {
      // Defense in depth: fields/button are already disabled while locked, but guard
      // the handler itself so no login request can be sent while locked.
      return;
    }
    setError(null);
    if (/\s/.test(email)) {
      setError('Whitespace is not allowed.');
      return;
    }
    if (/\s/.test(password)) {
      setError('Password cannot contain whitespace characters.');
      return;
    }
    setSubmitting(true);
    try {
      const data = await authApi.login(email, password);
      localStorage.removeItem(LOCK_STORAGE_KEY);
      setLock(null);
      setAuth(data.token, {
        email: data.email,
        mustChangePassword: data.mustChangePassword,
        role: data.role,
      });
      if (data.mustChangePassword) {
        // Not `replace: true` — keeps /login in history so the browser back
        // button can return the user to sign-in from the password-setup page.
        navigate('/change-password');
      } else {
        navigate('/dashboard', { replace: true });
      }
    } catch (err) {
      clearAuth();
      if (err instanceof LoginLockedError) {
        const newLock: StoredLock = { email, lockedUntil: err.lockedUntil };
        localStorage.setItem(LOCK_STORAGE_KEY, JSON.stringify(newLock));
        setLock(newLock);
        setError(null);
      } else {
        setError(err instanceof Error ? err.message : 'Invalid email or password.');
        emailRef.current?.focus();
      }
    } finally {
      setSubmitting(false);
    }
  }

  const hasError = Boolean(error);

  if (isImageLayout) {
    const lockedMessage = locked
      ? `Your account ${lock!.email} has been locked due to multiple incorrect login attempts. ${formatRemainingLockTime(lock!.lockedUntil)}`
      : null;
    return (
      <ImageLogin
        lockedMessage={lockedMessage}
        hasError={hasError} error={error} errorId={errorId}
        email={email} setEmail={setEmail} emailId={emailId} emailRef={emailRef}
        password={password} setPassword={setPassword} passId={passId}
        showPass={showPass} setShowPass={setShowPass}
        submitting={submitting} locked={locked} onSubmit={handleCredentialSubmit}
      />
    );
  }

  return (
    <AuthLayout
      leftHeadline="Your people. One place."
      leftSubtext="Manage leave, approvals, attendance, and everyday HR tasks in one place — without spreadsheets or manual follow-ups."
      showStats
    >
      <motion.div variants={reduced ? undefined : containerVariants} initial={reduced ? undefined : 'hidden'} animate={reduced ? undefined : 'show'}>
        <motion.div className="nf-login-heading-block" variants={reduced ? undefined : itemVariants} style={{ marginBottom: 28 }}>
          <h1 className="nf-login-heading" style={{ fontFamily: 'Inter, sans-serif', fontSize: 26, fontWeight: 700, letterSpacing: '-0.01em', color: 'var(--txt)', marginBottom: 6 }}>
            Welcome back
          </h1>
        </motion.div>

        <motion.div variants={reduced ? undefined : itemVariants}>
          <button
            type="button"
            disabled
            title="Microsoft SSO arrives in a later phase, once Azure AD coordination is ready"
            className="nf-login-sso-btn"
            style={{
              width: '100%', display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 10,
              padding: '12px 16px', background: 'var(--raised2)', color: 'var(--txt-dim)',
              border: '1px solid var(--line2)', borderRadius: 8, fontSize: 14, fontWeight: 600,
              cursor: 'not-allowed', marginBottom: 4, opacity: 0.7,
            }}
          >
            <MicrosoftIcon />
            Continue with Microsoft SSO
            <span style={{ fontSize: 9, letterSpacing: '.06em', textTransform: 'uppercase', background: 'var(--panel)', border: '1px solid var(--line2)', borderRadius: 20, padding: '2px 8px', marginLeft: 6 }}>
              Coming soon
            </span>
          </button>
        </motion.div>

        <motion.div variants={reduced ? undefined : itemVariants}>
          <div className="nf-login-divider" style={{ display: 'flex', alignItems: 'center', gap: 12, color: 'var(--txt-dim)', fontSize: 12, margin: '20px 0' }}>
            <span style={{ flex: 1, height: 1, background: 'var(--line)', display: 'block' }} />
            or use organizational credentials
            <span style={{ flex: 1, height: 1, background: 'var(--line)', display: 'block' }} />
          </div>
        </motion.div>

        {locked && (
          <motion.div
            initial={reduced ? undefined : { opacity: 0, y: -6 }}
            animate={reduced ? undefined : { opacity: 1, y: 0 }}
            transition={{ duration: 0.2 }}
            role="alert" aria-live="assertive" id={errorId}
            style={{ display: 'flex', alignItems: 'flex-start', gap: 10, padding: '12px 14px', borderRadius: 8,
              background: 'rgba(228,55,61,.10)', border: '1px solid rgba(228,55,61,.25)', color: '#f4a5a8', fontSize: 13, marginBottom: 18 }}
          >
            <Lock size={15} style={{ flexShrink: 0, marginTop: 1, color: 'var(--risk)' }} aria-hidden="true" />
            <span>
              Your account <strong>{lock.email}</strong> has been locked due to multiple incorrect login attempts. {formatRemainingLockTime(lock.lockedUntil)}
            </span>
          </motion.div>
        )}

        {!locked && hasError && (
          <motion.div
            initial={reduced ? undefined : { opacity: 0, y: -6 }}
            animate={reduced ? undefined : { opacity: 1, y: 0 }}
            transition={{ duration: 0.2 }}
            role="alert" aria-live="polite" id={errorId}
            style={{ display: 'flex', alignItems: 'flex-start', gap: 10, padding: '12px 14px', borderRadius: 8,
              background: 'rgba(228,55,61,.10)', border: '1px solid rgba(228,55,61,.25)', color: '#f4a5a8', fontSize: 13, marginBottom: 18 }}
          >
            <AlertCircle size={15} style={{ flexShrink: 0, marginTop: 1, color: 'var(--risk)' }} aria-hidden="true" />
            <span>{error}</span>
          </motion.div>
        )}

        <form onSubmit={handleCredentialSubmit} noValidate>
          <motion.div className="nf-login-field" variants={reduced ? undefined : itemVariants} style={{ marginBottom: 14 }}>
            <label htmlFor={emailId} className="nf-login-label" style={{ display: 'block', fontSize: 12, fontWeight: 550, color: 'var(--txt-mut)', marginBottom: 6 }}>Email</label>
            <input
              ref={emailRef} id={emailId} type="text" inputMode="email" autoComplete="email" placeholder="you@nforceone.com"
              value={email} onChange={(e) => setEmail(e.target.value)}
              disabled={locked}
              aria-invalid={hasError} aria-describedby={(hasError || locked) ? errorId : undefined}
              className="nf-login-input"
              style={{ width: '100%', background: 'var(--shell)', border: '1px solid var(--line2)', borderRadius: 6, padding: '10px 12px', color: 'var(--txt)', fontSize: 14, outline: 'none', fontFamily: 'Inter, sans-serif', boxSizing: 'border-box', opacity: locked ? 0.6 : 1, cursor: locked ? 'not-allowed' : 'text' }}
            />
          </motion.div>

          <motion.div className="nf-login-field" variants={reduced ? undefined : itemVariants} style={{ marginBottom: 14 }}>
            <label htmlFor={passId} className="nf-login-label" style={{ display: 'block', fontSize: 12, fontWeight: 550, color: 'var(--txt-mut)', marginBottom: 6 }}>Password</label>
            <div style={{ position: 'relative' }}>
              <input
                id={passId} type={showPass ? 'text' : 'password'} autoComplete="current-password" placeholder="••••••••"
                value={password} onChange={(e) => setPassword(e.target.value)}
                disabled={locked}
                aria-invalid={hasError} aria-describedby={(hasError || locked) ? errorId : undefined}
                className="nf-login-input"
                style={{ width: '100%', background: 'var(--shell)', border: '1px solid var(--line2)', borderRadius: 6, padding: '10px 44px 10px 12px', color: 'var(--txt)', fontSize: 14, outline: 'none', fontFamily: 'Inter, sans-serif', boxSizing: 'border-box', opacity: locked ? 0.6 : 1, cursor: locked ? 'not-allowed' : 'text' }}
              />
              <button
                type="button" aria-label={showPass ? 'Hide password' : 'Show password'} onClick={() => setShowPass((v) => !v)}
                disabled={locked}
                style={{ position: 'absolute', right: 10, top: '50%', transform: 'translateY(-50%)', background: 'none', border: 'none', cursor: locked ? 'not-allowed' : 'pointer', color: 'var(--txt-dim)', display: 'flex', alignItems: 'center', padding: 4, borderRadius: 4 }}
              >
                {showPass ? <EyeOff size={15} aria-hidden="true" /> : <Eye size={15} aria-hidden="true" />}
              </button>
            </div>
          </motion.div>

          {!locked && (
            <motion.div className="nf-login-forgot-row" variants={reduced ? undefined : itemVariants} style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: 18, marginTop: -4 }}>
              <Link to="/forgot-password" style={{ fontSize: 12, color: 'var(--txt-mut)', textDecoration: 'none', cursor: 'pointer' }}>
                Forgot password?
              </Link>
            </motion.div>
          )}

          <motion.div variants={reduced ? undefined : itemVariants}>
            <button
              type="submit" disabled={submitting || locked}
              className="nf-login-submit"
              style={{ width: '100%', display: 'flex', alignItems: 'center', justifyContent: 'center', padding: '11px 16px',
                background: 'var(--brand)', border: 'none', borderRadius: 8, color: '#fff', fontSize: 14, fontWeight: 600,
                cursor: (submitting || locked) ? 'not-allowed' : 'pointer', opacity: (submitting || locked) ? 0.75 : 1 }}
            >
              {submitting ? 'Signing in…' : 'Sign in'}
            </button>
          </motion.div>
        </form>
      </motion.div>
    </AuthLayout>
  );
}
