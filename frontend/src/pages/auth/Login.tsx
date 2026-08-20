import { useEffect, useId, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { motion, useReducedMotion } from 'framer-motion';
import { Eye, EyeOff, AlertCircle, Lock } from 'lucide-react';
import { AuthLayout } from './AuthLayout';
import { authApi, LoginLockedError } from '../../api/auth';
import { useAuthStore } from '../../store/authStore';

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

export default function Login() {
  const navigate   = useNavigate();
  const setAuth    = useAuthStore((s) => s.setAuth);
  const clearAuth  = useAuthStore((s) => s.clearAuth);
  const reduced    = useReducedMotion();
  const emailId    = useId();
  const passId     = useId();
  const errorId    = useId();

  const [email,      setEmail]      = useState('');
  const [password,   setPassword]   = useState('');
  const [showPass,   setShowPass]   = useState(false);
  const [error,      setError]      = useState<string | null>(null);
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

  return (
    <AuthLayout
      leftHeadline="Your people. One place."
      leftSubtext="Manage leave, approvals, attendance, and everyday HR tasks in one place — without spreadsheets or manual follow-ups."
      showStats
    >
      <motion.div variants={reduced ? undefined : containerVariants} initial={reduced ? undefined : 'hidden'} animate={reduced ? undefined : 'show'}>
        <motion.div className="nf-login-heading-block" variants={reduced ? undefined : itemVariants} style={{ marginBottom: 28 }}>
          <h1 className="nf-login-heading" style={{ fontFamily: '"Space Grotesk", sans-serif', fontSize: 26, fontWeight: 700, letterSpacing: '-0.01em', color: 'var(--txt)', marginBottom: 6 }}>
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
