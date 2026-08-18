import { useId, useState } from 'react';
import { Link } from 'react-router-dom';
import { motion, useReducedMotion } from 'framer-motion';
import { AuthLayout } from './AuthLayout';
import { authApi } from '../../api/auth';

const containerVariants = { hidden: {}, show: { transition: { staggerChildren: 0.04 } } };
const itemVariants = {
  hidden: { opacity: 0, y: 10 },
  show:   { opacity: 1, y: 0, transition: { duration: 0.28, ease: [0.23, 1, 0.32, 1] as const } },
};

export default function ForgotPasswordPage() {
  const reduced  = useReducedMotion();
  const emailId  = useId();
  const [email,      setEmail]      = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [submitted,  setSubmitted]  = useState(false);
  const [successMessage, setSuccessMessage] = useState('');
  const [error,      setError]      = useState<string | null>(null);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!email.trim()) { setError('Enter your email address.'); return; }
    setError(null);
    setSubmitting(true);
    try {
      const res = await authApi.forgotPassword(email.trim());
      setSuccessMessage(res.message);
      setSubmitted(true);
    } catch (err) {
      // Account status (not found / deactivated / deleted) is reported back explicitly —
      // see AuthService.forgotPassword.
      setError(err instanceof Error ? err.message : 'Something went wrong. Please try again.');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <AuthLayout
      leftHeadline="Forgot your password?"
      leftSubtext="Enter your email and we'll send reset instructions if an account exists."
    >
      <motion.div
        variants={reduced ? undefined : containerVariants}
        initial={reduced ? undefined : 'hidden'}
        animate={reduced ? undefined : 'show'}
      >
        <motion.div variants={reduced ? undefined : itemVariants} style={{ marginBottom: 28 }}>
          <h1 style={{ fontFamily: '"Space Grotesk", sans-serif', fontSize: 26, fontWeight: 700, letterSpacing: '-0.01em', color: 'var(--txt)', marginBottom: 6 }}>
            Reset password
          </h1>
          <p style={{ fontSize: 13, color: 'var(--txt-mut)', lineHeight: 1.5 }}>
            Enter your company email. If it's registered, you'll receive a temporary password.
          </p>
        </motion.div>

        {submitted ? (
          <motion.div
            initial={reduced ? undefined : { opacity: 0, y: 8 }}
            animate={reduced ? undefined : { opacity: 1, y: 0 }}
            transition={{ duration: 0.28 }}
          >
            <div style={{
              background: 'rgba(47,182,124,.1)',
              border: '1px solid rgba(47,182,124,.3)',
              borderRadius: 10,
              padding: '20px 22px',
              marginBottom: 24,
            }}>
              <div style={{ fontWeight: 700, color: 'var(--ok)', marginBottom: 6, fontSize: 14 }}>Check your inbox</div>
              <p style={{ fontSize: 13, color: 'var(--txt-mut)', lineHeight: 1.6, margin: 0 }}>
                {successMessage} Check your spam folder if it doesn't arrive within a minute.
              </p>
            </div>
            <Link
              to="/login"
              style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 13, color: 'var(--txt-mut)', textDecoration: 'none', fontWeight: 500 }}
            >
              ← Back to sign in
            </Link>
          </motion.div>
        ) : (
          <form onSubmit={handleSubmit} noValidate>
            {error && (
              <motion.div
                initial={reduced ? undefined : { opacity: 0, y: -6 }}
                animate={reduced ? undefined : { opacity: 1, y: 0 }}
                transition={{ duration: 0.2 }}
                role="alert"
                style={{
                  background: 'rgba(228,55,61,.08)',
                  border: '1px solid rgba(228,55,61,.3)',
                  borderRadius: 10,
                  padding: '16px 18px',
                  marginBottom: 20,
                }}
              >
                <div style={{ fontWeight: 700, color: '#E4373D', marginBottom: 4, fontSize: 14 }}>Unable to reset password</div>
                <p style={{ fontSize: 13, color: 'var(--txt-mut)', lineHeight: 1.6, margin: 0 }}>{error}</p>
              </motion.div>
            )}
            <motion.div variants={reduced ? undefined : itemVariants} style={{ marginBottom: 18 }}>
              <label htmlFor={emailId} style={{ display: 'block', fontSize: 12, fontWeight: 550, color: 'var(--txt-mut)', marginBottom: 6 }}>
                Work email
              </label>
              <input
                id={emailId}
                type="email"
                autoComplete="email"
                placeholder="you@nforceone.com"
                value={email}
                onChange={e => { setEmail(e.target.value); setError(null); }}
                style={{
                  width: '100%',
                  background: 'var(--shell)',
                  border: `1px solid ${error ? 'rgba(228,55,61,.5)' : 'var(--line2)'}`,
                  borderRadius: 6,
                  padding: '10px 12px',
                  color: 'var(--txt)',
                  fontSize: 14,
                  outline: 'none',
                  fontFamily: 'Inter, sans-serif',
                  boxSizing: 'border-box',
                }}
              />
            </motion.div>

            <motion.div variants={reduced ? undefined : itemVariants} style={{ marginBottom: 18 }}>
              <button
                type="submit"
                disabled={submitting}
                style={{
                  width: '100%',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  padding: '11px 16px',
                  background: 'var(--brand)',
                  border: 'none',
                  borderRadius: 8,
                  color: '#fff',
                  fontSize: 14,
                  fontWeight: 600,
                  cursor: submitting ? 'not-allowed' : 'pointer',
                  opacity: submitting ? 0.75 : 1,
                }}
              >
                {submitting ? 'Sending…' : 'Reset'}
              </button>
            </motion.div>

            <motion.div variants={reduced ? undefined : itemVariants}>
              <Link
                to="/login"
                style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 13, color: 'var(--txt-mut)', textDecoration: 'none', fontWeight: 500 }}
              >
                ← Back to sign in
              </Link>
            </motion.div>
          </form>
        )}
      </motion.div>
    </AuthLayout>
  );
}
