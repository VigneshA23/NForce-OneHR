import { useEffect, useId, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Eye, EyeOff, AlertCircle, Lock, ArrowRight } from 'lucide-react';
import { authApi, LoginLockedError } from '../../api/auth';
import { useAuthStore } from '../../store/authStore';
import { consumeSessionMessage } from '../../lib/authFetch';
import wordmarkUrl from '../../assets/nforce-wordmark.png';
import artUrl from '../../assets/login-art.jpg';
import '../login.css';

// "NForce One" in the surrounding text colour, "HR" in brand red.
function BrandName() {
  return <>NForce One<i className="nf-lg-hr">HR</i></>;
}

// Persists only the lock expiry the server already returned, so a page refresh keeps
// showing the locked state without sending another login request. Not a client-side
// attempt counter — the server remains the sole source of truth for attempt counting.
const LOCK_STORAGE_KEY = 'onehr:accountLock';
// UI convenience only (Remember me): prefills the email field, never the password or a token.
const REMEMBER_EMAIL_KEY = 'onehr:rememberEmail';

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

export default function Login() {
  const navigate   = useNavigate();
  const setAuth    = useAuthStore((s) => s.setAuth);
  const clearAuth  = useAuthStore((s) => s.clearAuth);
  const emailId    = useId();
  const passId     = useId();
  const errorId    = useId();
  const rememberMeId = useId();

  const [email,      setEmail]      = useState('');
  const [password,   setPassword]   = useState('');
  const [showPass,   setShowPass]   = useState(false);
  const [rememberMe, setRememberMe] = useState(false);
  // A password-change/session-invalidation redirect (see lib/authFetch.ts) leaves a one-shot
  // message here for this exact banner to pick up on first render.
  const [error,      setError]      = useState<string | null>(() => consumeSessionMessage());
  const [submitting, setSubmitting] = useState(false);
  // Brief "signed in" confirmation (green tick) shown on the button before moving on to the app.
  const [signedIn, setSignedIn] = useState(false);
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

  useEffect(() => {
    try {
      const saved = localStorage.getItem(REMEMBER_EMAIL_KEY);
      if (saved) { setEmail(saved); setRememberMe(true); }
    } catch { /* storage unavailable */ }
  }, []);

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
      const data = await authApi.login(email, password, rememberMe);
      try {
        if (rememberMe) localStorage.setItem(REMEMBER_EMAIL_KEY, email.trim());
        else localStorage.removeItem(REMEMBER_EMAIL_KEY);
      } catch { /* storage unavailable */ }
      localStorage.removeItem(LOCK_STORAGE_KEY);
      setLock(null);
      setAuth(data.token, {
        email: data.email,
        mustChangePassword: data.mustChangePassword,
        role: data.role,
      }, rememberMe);
      setSignedIn(true);
      await new Promise((resolve) => window.setTimeout(resolve, 950));
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
    <div className="nf-lg">
      {/* Left brand panel — hidden on small screens */}
      <aside className="nf-lg-brand">
        <div className="nf-lg-top">
          <img src={wordmarkUrl} alt="NForce One" className="nf-lg-wordmark" />
          <span className="nf-lg-divider" aria-hidden="true" />
          <span className="nf-lg-product"><BrandName /></span>
        </div>

        <h1 className="nf-lg-headline">
          The modern way to<br />
          manage <em>your workforce.</em>
        </h1>
        <p className="nf-lg-sub">
          Streamline HR processes, empower your people<br />
          and build a stronger tomorrow, together.
        </p>

        <div className="nf-lg-art" aria-hidden="true" style={{ backgroundImage: `url(${artUrl})` }} />
        <p className="nf-lg-copy">© {new Date().getFullYear()} NForce One</p>
      </aside>

      {/* Right form panel */}
      <main className="nf-lg-main">
        <div className="nf-lg-card">
          {/* Small screens: the brand sits at the top of the card */}
          <div className="nf-lg-mobile-brand">
            <img src={wordmarkUrl} alt="NForce One" />
            <span><BrandName /></span>
          </div>

          <h2 className="nf-lg-title">Welcome back</h2>
          <p className="nf-lg-lead">Sign in to <BrandName /></p>

          {/* Lock banner */}
          {locked && (
            <div role="alert" aria-live="assertive" id={errorId} className="nf-lg-alert">
              <Lock size={15} aria-hidden="true" />
              <span>Your account {lock.email} has been locked due to multiple incorrect login attempts. {formatRemainingLockTime(lock.lockedUntil)}</span>
            </div>
          )}

          {/* Error alert */}
          {!locked && hasError && (
            <div role="alert" aria-live="polite" id={errorId} className="nf-lg-alert">
              <AlertCircle size={15} aria-hidden="true" />
              <span>{error}</span>
            </div>
          )}

          {/* Credentials form */}
          <form onSubmit={handleCredentialSubmit} noValidate>
            <div className="nf-lg-field">
              <label htmlFor={emailId} className="nf-lg-label">Email address</label>
              <input
                ref={emailRef}
                id={emailId}
                type="text"
                inputMode="email"
                autoComplete="email"
                placeholder="you@nforceone.com"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                disabled={locked}
                aria-invalid={hasError}
                aria-describedby={(hasError || locked) ? errorId : undefined}
                className={`nf-lg-input${hasError ? ' has-error' : ''}`}
              />
            </div>

            <div className="nf-lg-field">
              <div className="nf-lg-label-row">
                <label htmlFor={passId} className="nf-lg-label">Password</label>
                {!locked && <Link to="/forgot-password" className="nf-lg-forgot">Forgot password?</Link>}
              </div>
              <div className="nf-lg-pw">
                <input
                  id={passId}
                  type={showPass ? 'text' : 'password'}
                  autoComplete="current-password"
                  placeholder="••••••••••"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  disabled={locked}
                  aria-invalid={hasError}
                  aria-describedby={(hasError || locked) ? errorId : undefined}
                  className={`nf-lg-input${hasError ? ' has-error' : ''}`}
                />
                <button
                  type="button"
                  aria-label={showPass ? 'Hide password' : 'Show password'}
                  onClick={() => setShowPass((v) => !v)}
                  disabled={locked}
                  className="nf-lg-eye"
                >
                  {showPass ? <Eye size={17} aria-hidden="true" /> : <EyeOff size={17} aria-hidden="true" />}
                </button>
              </div>
            </div>

            <label htmlFor={rememberMeId} className={`nf-lg-remember${locked ? ' is-disabled' : ''}`}>
              <input
                id={rememberMeId}
                type="checkbox"
                checked={rememberMe}
                onChange={(e) => setRememberMe(e.target.checked)}
                disabled={locked}
              />
              <span>Remember me</span>
            </label>

            <button type="submit" className={`nf-lg-submit${signedIn ? ' is-done' : ''}`} disabled={submitting || locked || signedIn}>
              {signedIn ? (
                <>
                  <svg className="nf-lg-tick" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                    <circle className="nf-lg-tick-ring" cx="12" cy="12" r="10" />
                    <path className="nf-lg-tick-mark" d="M7 12.5l3.2 3.2L17 9" />
                  </svg>
                  Signed in
                </>
              ) : submitting ? (
                <>
                  <span className="nf-lg-spin" aria-hidden="true" />
                  Signing in…
                </>
              ) : (
                <>Sign in <ArrowRight size={17} aria-hidden="true" /></>
              )}
            </button>
          </form>

          <div className="nf-lg-help">
            <b>Need help signing in?</b>
            <span>Contact your HR team for account support.</span>
          </div>
        </div>
      </main>
    </div>
  );
}
