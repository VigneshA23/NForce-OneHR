import { useId, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { motion, useReducedMotion } from 'framer-motion';
import { Eye, EyeOff, AlertCircle } from 'lucide-react';
import { AuthLayout } from './AuthLayout';
import { authApi } from '../../api/auth';
import { useAuthStore } from '../../store/authStore';

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
  const emailRef = useRef<HTMLInputElement>(null);

  async function handleCredentialSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    const trimmedEmail = email.trim();
    if (/\s/.test(trimmedEmail)) {
      setError('Email cannot contain whitespace characters.');
      return;
    }
    if (/\s/.test(password)) {
      setError('Password cannot contain whitespace characters.');
      return;
    }
    setSubmitting(true);
    try {
      const data = await authApi.login(trimmedEmail, password);
      setAuth(data.token, {
        email: data.email,
        mustChangePassword: data.mustChangePassword,
        role: data.role,
      });
      if (data.mustChangePassword) {
        navigate('/change-password', { replace: true });
      } else {
        navigate('/dashboard', { replace: true });
      }
    } catch (err) {
      clearAuth();
      setError(err instanceof Error ? err.message : 'Invalid email or password.');
      emailRef.current?.focus();
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
        <motion.div variants={reduced ? undefined : itemVariants} style={{ marginBottom: 28 }}>
          <h1 style={{ fontFamily: '"Space Grotesk", sans-serif', fontSize: 26, fontWeight: 700, letterSpacing: '-0.01em', color: 'var(--txt)', marginBottom: 6 }}>
            Welcome back
          </h1>
          <p style={{ fontSize: 13, color: 'var(--txt-mut)', lineHeight: 1.5 }}>
            Use your company email and password.
          </p>
        </motion.div>

        <motion.div variants={reduced ? undefined : itemVariants}>
          <button
            type="button"
            disabled
            title="Microsoft SSO arrives in a later phase, once Azure AD coordination is ready"
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
          <div style={{ display: 'flex', alignItems: 'center', gap: 12, color: 'var(--txt-dim)', fontSize: 12, margin: '20px 0' }}>
            <span style={{ flex: 1, height: 1, background: 'var(--line)', display: 'block' }} />
            or use company credentials
            <span style={{ flex: 1, height: 1, background: 'var(--line)', display: 'block' }} />
          </div>
        </motion.div>

        {hasError && (
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
          <motion.div variants={reduced ? undefined : itemVariants} style={{ marginBottom: 14 }}>
            <label htmlFor={emailId} style={{ display: 'block', fontSize: 12, fontWeight: 550, color: 'var(--txt-mut)', marginBottom: 6 }}>Email</label>
            <input
              ref={emailRef} id={emailId} type="email" autoComplete="email" placeholder="you@nforceone.com"
              value={email} onChange={(e) => setEmail(e.target.value)}
              aria-invalid={hasError} aria-describedby={hasError ? errorId : undefined}
              style={{ width: '100%', background: 'var(--shell)', border: '1px solid var(--line2)', borderRadius: 6, padding: '10px 12px', color: 'var(--txt)', fontSize: 14, outline: 'none', fontFamily: 'Inter, sans-serif', boxSizing: 'border-box' }}
            />
          </motion.div>

          <motion.div variants={reduced ? undefined : itemVariants} style={{ marginBottom: 14 }}>
            <label htmlFor={passId} style={{ display: 'block', fontSize: 12, fontWeight: 550, color: 'var(--txt-mut)', marginBottom: 6 }}>Password</label>
            <div style={{ position: 'relative' }}>
              <input
                id={passId} type={showPass ? 'text' : 'password'} autoComplete="current-password" placeholder="••••••••"
                value={password} onChange={(e) => setPassword(e.target.value)}
                aria-invalid={hasError} aria-describedby={hasError ? errorId : undefined}
                style={{ width: '100%', background: 'var(--shell)', border: '1px solid var(--line2)', borderRadius: 6, padding: '10px 44px 10px 12px', color: 'var(--txt)', fontSize: 14, outline: 'none', fontFamily: 'Inter, sans-serif', boxSizing: 'border-box' }}
              />
              <button
                type="button" aria-label={showPass ? 'Hide password' : 'Show password'} onClick={() => setShowPass((v) => !v)}
                style={{ position: 'absolute', right: 10, top: '50%', transform: 'translateY(-50%)', background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt-dim)', display: 'flex', alignItems: 'center', padding: 4, borderRadius: 4 }}
              >
                {showPass ? <EyeOff size={15} aria-hidden="true" /> : <Eye size={15} aria-hidden="true" />}
              </button>
            </div>
          </motion.div>

          <motion.div variants={reduced ? undefined : itemVariants} style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: 18, marginTop: -4 }}>
            <Link to="/forgot-password" style={{ fontSize: 12, color: 'var(--txt-mut)', textDecoration: 'none', cursor: 'pointer' }}>
              Forgot password?
            </Link>
          </motion.div>

          <motion.div variants={reduced ? undefined : itemVariants}>
            <button
              type="submit" disabled={submitting}
              style={{ width: '100%', display: 'flex', alignItems: 'center', justifyContent: 'center', padding: '11px 16px',
                background: 'var(--brand)', border: 'none', borderRadius: 8, color: '#fff', fontSize: 14, fontWeight: 600,
                cursor: submitting ? 'not-allowed' : 'pointer', opacity: submitting ? 0.75 : 1 }}
            >
              {submitting ? 'Signing in…' : 'Sign in'}
            </button>
          </motion.div>
        </form>
      </motion.div>
    </AuthLayout>
  );
}
