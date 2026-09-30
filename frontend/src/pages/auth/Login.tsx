import { useEffect, useId, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { motion, useReducedMotion } from 'framer-motion';
import { Eye, EyeOff, AlertCircle, Lock } from 'lucide-react';
import { authApi, LoginLockedError } from '../../api/auth';
import { useAuthStore } from '../../store/authStore';
import { consumeSessionMessage } from '../../lib/authFetch';
import { SyncLoginPanel } from './AuthLayoutSync';
import { LoginArtwork } from './LoginArtwork';
import nf1Logo from '../../assets/nforce-logo.png';

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

function MicrosoftIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 21 21" aria-hidden="true" focusable="false" style={{ width: 'calc(16 * var(--u))', height: 'calc(16 * var(--u))' }}>
      <rect x="0"  y="0"  width="10" height="10" fill="#F25022" />
      <rect x="11" y="0"  width="10" height="10" fill="#7FBA00" />
      <rect x="0"  y="11" width="10" height="10" fill="#00A4EF" />
      <rect x="11" y="11" width="10" height="10" fill="#FFB900" />
    </svg>
  );
}


export default function Login() {
  const navigate   = useNavigate();
  const setAuth    = useAuthStore((s) => s.setAuth);
  const clearAuth  = useAuthStore((s) => s.clearAuth);
  const reduced    = useReducedMotion();
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
      const data = await authApi.login(email, password);
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
    <div
      data-theme="dark"
      className="nf-login-root"
      style={{ position: 'relative', minHeight: '100dvh', background: '#060608', overflow: 'hidden' }}
    >
      {/* Deployed OneHR visual (left ~65%): decorative artwork + real DOM text. Only its right
          edge eases into the page background so both areas read as one continuous screen. */}
      <LoginArtwork />

      <div
        className="nf-login-grid"
        style={{ position: 'relative', zIndex: 1, display: 'grid', gridTemplateColumns: '65fr 35fr', minHeight: '100dvh' }}
      >
        {/* Empty grid cell; the artwork above is decorative and non-interactive. */}
        <div className="nf-login-spacer" style={{ pointerEvents: 'none' }} />


      {/* RIGHT PANEL — Sync-style login container (presentation only) */}
      <SyncLoginPanel>
      <motion.div
        variants={reduced ? undefined : containerVariants}
        initial={reduced ? undefined : 'hidden'}
        animate={reduced ? undefined : 'show'}
      >
        {/* Header */}
        <motion.div variants={reduced ? undefined : itemVariants} style={{ marginBottom: u(32), position: 'relative' }}>
          {/* NF1 logo (existing nforce-logo.png asset) replaces the old red accent line. It is absolutely
              positioned, so it takes no space in the card's flow: the 22px spacer below is exactly the old
              line's height (2px line + 20px gap), which keeps the card height and all spacing unchanged. */}
          <span role="img" aria-label="NForce One logo" style={logoBadgeStyle}>
            <img src={nf1Logo} alt="" aria-hidden="true" width={60} height={60} style={{ display: 'block', width: '100%', height: '100%', objectFit: 'cover' }} />
          </span>
          <div aria-hidden="true" style={{ height: u(22) }} />
          <h1 style={headingStyle}>Welcome back</h1>
          <p style={{ fontSize: u(13), color: 'rgba(255,255,255,0.42)', lineHeight: 1.58, margin: 0 }}>
            Sign in to manage your workforce
          </p>
        </motion.div>


        {/* Microsoft SSO */}
        <motion.div variants={reduced ? undefined : itemVariants}>
          <button
            type="button"
            disabled
            title="Microsoft SSO arrives in a later phase, once Azure AD coordination is ready"
            aria-label="Microsoft SSO — coming soon"
            style={{
              ...ssoButtonStyle,
              opacity: 0.75,
              cursor: 'not-allowed',
            }}
          >
            <MicrosoftIcon />
            <span>Continue with Microsoft SSO</span>
          </button>
        </motion.div>

        {/* OR divider */}
        <motion.div variants={reduced ? undefined : itemVariants}>
          <div style={dividerStyle}>
            <span style={dividerLineStyle} />
            <span style={dividerTextStyle}>or use company credentials</span>
            <span style={dividerLineStyle} />
          </div>
        </motion.div>


        {/* Lock banner */}
        {locked && (
          <motion.div
            initial={reduced ? undefined : { opacity: 0, y: -6 }}
            animate={reduced ? undefined : { opacity: 1, y: 0 }}
            transition={{ duration: 0.2 }}
            role="alert"
            aria-live="assertive"
            id={errorId}
            style={errorBannerStyle}
          >
            <Lock size={14} style={{ flexShrink: 0, width: u(14), height: u(14), marginTop: 1, color: 'var(--risk)' }} aria-hidden="true" />
            <span>Your account {lock.email} has been locked due to multiple incorrect login attempts. {formatRemainingLockTime(lock.lockedUntil)}</span>
          </motion.div>
        )}

        {/* Error alert */}
        {!locked && hasError && (
          <motion.div
            initial={reduced ? undefined : { opacity: 0, y: -6 }}
            animate={reduced ? undefined : { opacity: 1, y: 0 }}
            transition={{ duration: 0.2 }}
            role="alert"
            aria-live="polite"
            id={errorId}
            style={errorBannerStyle}
          >
            <AlertCircle size={14} style={{ flexShrink: 0, width: u(14), height: u(14), marginTop: 1, color: 'var(--risk)' }} aria-hidden="true" />
            <span>{error}</span>
          </motion.div>
        )}


        {/* Credentials form */}
        <form onSubmit={handleCredentialSubmit} noValidate>
          <motion.div variants={reduced ? undefined : itemVariants}>
            <div className="nfs-field" style={{ marginBottom: u(16) }}>
              <label htmlFor={emailId} style={labelStyle} className="nfs-label">Email</label>
              <div className="nfs-input-wrap">
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
                  className="nfs-input-inner"
                />
              </div>
            </div>
          </motion.div>

          <motion.div variants={reduced ? undefined : itemVariants}>
            <div className="nfs-field" style={{ marginBottom: u(16) }}>
              <label htmlFor={passId} style={labelStyle} className="nfs-label">Password</label>
              <div className="nfs-input-wrap">
                <div style={{ position: 'relative' }}>
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
                    className="nfs-input-inner"
                    style={{ paddingRight: u(46) }}
                  />
                  <button
                    type="button"
                    aria-label={showPass ? 'Hide password' : 'Show password'}
                    onClick={() => setShowPass((v) => !v)}
                    disabled={locked}
                    className="nfs-eye"
                    style={{ ...eyeButtonStyle, cursor: locked ? 'not-allowed' : 'pointer', zIndex: 2 }}
                  >
                    {showPass
                      ? <Eye    size={15} style={{ width: u(15), height: u(15) }} aria-hidden="true" />
                      : <EyeOff size={15} style={{ width: u(15), height: u(15) }} aria-hidden="true" />
                    }
                  </button>
                </div>
              </div>
            </div>
          </motion.div>

          {/* Remember me + Forgot link row */}
          <motion.div
            variants={reduced ? undefined : itemVariants}
            style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: u(22), marginTop: u(-4) }}
          >
            <label htmlFor={rememberMeId} style={{ display: 'flex', alignItems: 'center', gap: u(8), cursor: locked ? 'not-allowed' : 'pointer' }}>
              <input
                id={rememberMeId}
                type="checkbox"
                checked={rememberMe}
                onChange={(e) => setRememberMe(e.target.checked)}
                disabled={locked}
                style={{ accentColor: '#E4373D', width: u(14), height: u(14), cursor: 'inherit' }}
              />
              <span style={{ fontSize: u(12), color: 'rgba(255,255,255,0.42)', userSelect: 'none' }}>Remember me</span>
            </label>
            {!locked && (
              <Link
                to="/forgot-password"
                style={mutedLinkStyle}
                onMouseEnter={(e) => (e.currentTarget.style.color = 'rgba(255,255,255,0.72)')}
                onMouseLeave={(e) => (e.currentTarget.style.color = 'rgba(255,255,255,0.38)')}
              >
                Forgot password?
              </Link>
            )}
          </motion.div>

          {/* Sign in button */}
          <motion.div variants={reduced ? undefined : itemVariants}>
            <button
              type="submit"
              className="nfs-submit-btn"
              disabled={submitting || locked}
              style={{
                ...submitButtonStyle,
                opacity: locked ? 0.45 : 1,
                cursor: submitting || locked ? 'not-allowed' : 'pointer',
              }}
              onMouseEnter={(e) => {
                if (!submitting && !locked) Object.assign(e.currentTarget.style, submitButtonHoverStyle);
              }}
              onMouseLeave={(e) => Object.assign(e.currentTarget.style, {
                ...submitButtonStyle,
                opacity: locked ? 0.45 : 1,
                cursor: submitting || locked ? 'not-allowed' : 'pointer',
              })}
            >
              {submitting ? (
                <>
                  <motion.div
                    animate={reduced ? undefined : { rotate: 360 }}
                    transition={{ duration: 0.8, repeat: Infinity, ease: 'linear' }}
                    style={{ display: 'inline-flex' }}
                  >
                    <div style={{ width: u(14), height: u(14), borderRadius: '50%', border: '2px solid rgba(255,255,255,.3)', borderTopColor: '#fff' }} />
                  </motion.div>
                  Signing in…
                </>
              ) : (
                'Sign in'
              )}
            </button>
          </motion.div>
        </form>

      </motion.div>
      </SyncLoginPanel>

      </div>

      <style>{`
        @media (max-width: 1024px) {
          .nf-login-grid { grid-template-columns: 1fr !important; }
          .nf-login-spacer { display: none !important; }
          .nf-login-grid { min-height: auto !important; }
          .nfs-auth-panel { min-height: auto !important; }
        }
      `}</style>
    </div>
  );
}


// ── Styles (ported from the Sync login) ─────────────────────────────

// The card scales as one unit with the window: --u (set on the panel) is 1px on roomy windows and
// shrinks smoothly on small / short ones. Every card metric below is expressed in that unit.
const u = (n: number) => `calc(${n} * var(--u))`;

const containerVariants = {
  hidden: {},
  show: { transition: { staggerChildren: 0.045 } },
};

const itemVariants = {
  hidden: { opacity: 0, y: 10 },
  show:   { opacity: 1, y: 0, transition: { duration: 0.28, ease: [0.23, 1, 0.32, 1] as const } },
};

const logoBadgeStyle: React.CSSProperties = {
  // Absolutely positioned in the header slot (takes no flow space), horizontally centred above the heading.
  position: 'absolute',
  top: u(-36),
  left: '50%',
  marginLeft: u(-30),
  display: 'block',
  width: u(60),
  height: u(60),
  borderRadius: '50%',
  overflow: 'hidden',
  flexShrink: 0,
  border: '1px solid rgba(255,255,255,0.16)',
  boxShadow: '0 0 16px rgba(228,55,61,0.30)',
};

const headingStyle: React.CSSProperties = {
  fontFamily: '"Inter", "Segoe UI", "Roboto", "Helvetica Neue", Arial, sans-serif',
  fontSize: u(26),
  fontWeight: 700,
  letterSpacing: '-0.03em',
  color: '#fff',
  margin: `0 0 ${u(6)}`,
};

const labelStyle: React.CSSProperties = {
  display: 'block',
  fontSize: u(11),
  fontWeight: 600,
  color: 'rgba(255,255,255,0.45)',
  marginBottom: u(8),
  letterSpacing: '0.07em',
  textTransform: 'uppercase',
};

const eyeButtonStyle: React.CSSProperties = {
  position: 'absolute',
  right: u(11),
  top: '50%',
  transform: 'translateY(-50%)',
  background: 'none',
  border: 'none',
  cursor: 'pointer',
  color: 'rgba(255,255,255,0.35)',
  display: 'flex',
  alignItems: 'center',
  padding: u(4),
  borderRadius: u(4),
};

const mutedLinkStyle: React.CSSProperties = {
  fontSize: u(12),
  color: 'rgba(255,255,255,0.38)',
  textDecoration: 'none',
  cursor: 'pointer',
  transition: 'color 0.14s',
};

const submitButtonStyle: React.CSSProperties = {
  width: '100%',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  gap: u(8),
  padding: `${u(13)} ${u(16)}`,
  background: '#E4373D',
  border: 'none',
  borderRadius: u(8),
  color: '#fff',
  fontSize: u(14),
  fontWeight: 600,
  cursor: 'pointer',
  transition: 'background 0.14s, transform 0.14s',
  fontFamily: 'Inter, sans-serif',
  letterSpacing: '0.01em',
};

const submitButtonHoverStyle: React.CSSProperties = {
  ...submitButtonStyle,
  background: '#C82026',
  transform: 'translateY(-1px)',
};

const ssoButtonStyle: React.CSSProperties = {
  width: '100%',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  gap: u(10),
  padding: `${u(13)} ${u(16)}`,
  background: '#8B1A1A',
  color: 'rgba(255,255,255,0.65)',
  border: '1px solid rgba(228,55,61,0.3)',
  borderRadius: u(8),
  fontSize: u(14),
  fontWeight: 600,
  marginBottom: u(4),
  fontFamily: 'Inter, sans-serif',
  letterSpacing: '0.01em',
};

const dividerStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'center',
  gap: u(14),
  margin: `${u(20)} 0`,
};

const dividerLineStyle: React.CSSProperties = {
  flex: 1,
  height: 1,
  background: 'rgba(255,255,255,0.07)',
  display: 'block',
};

const dividerTextStyle: React.CSSProperties = {
  fontSize: u(11),
  color: 'rgba(255,255,255,0.22)',
  letterSpacing: '0.1em',
  textTransform: 'uppercase',
};

const errorBannerStyle: React.CSSProperties = {
  display: 'flex',
  alignItems: 'flex-start',
  gap: u(9),
  padding: `${u(11)} ${u(13)}`,
  borderRadius: u(7),
  background: 'rgba(228,55,61,0.08)',
  border: '1px solid rgba(228,55,61,0.2)',
  color: 'var(--risk)',
  fontSize: u(13),
  marginBottom: u(18),
  lineHeight: 1.45,
};
