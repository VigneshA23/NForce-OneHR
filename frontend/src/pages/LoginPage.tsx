import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { useNavigate } from 'react-router-dom';
import { authApi } from '../api/auth';
import { useAuthStore } from '../store/authStore';

const schema = z.object({
  email: z.string().min(1, 'Email is required').email('Enter a valid email address'),
  password: z.string().min(1, 'Password is required'),
});

type FormValues = z.infer<typeof schema>;

function EyeIcon({ open }: { open: boolean }) {
  return open ? (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z" />
      <circle cx="12" cy="12" r="3" />
    </svg>
  ) : (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94" />
      <path d="M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19" />
      <line x1="1" y1="1" x2="23" y2="23" />
    </svg>
  );
}

export default function LoginPage() {
  const navigate = useNavigate();
  const { setAuth } = useAuthStore();
  const [showPassword, setShowPassword] = useState(false);
  const [serverError, setServerError] = useState<string | null>(null);

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<FormValues>({ resolver: zodResolver(schema) });

  async function onSubmit(data: FormValues) {
    setServerError(null);
    try {
      const res = await authApi.login(data.email, data.password);
      setAuth(res.token, {
        email: res.email,
        firstName: res.firstName,
        lastName: res.lastName,
        mustChangePassword: res.mustChangePassword,
      });
      navigate(res.mustChangePassword ? '/change-password' : '/dashboard', { replace: true });
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'Something went wrong';
      if (msg.toLowerCase().includes('deactivated')) {
        setServerError('Your account has been deactivated. Contact HR for assistance.');
      } else if (msg.toLowerCase().includes('invalid') || msg.toLowerCase().includes('credentials')) {
        setServerError('Invalid email or password.');
      } else if (msg.toLowerCase().includes('network') || msg.toLowerCase().includes('failed to fetch')) {
        setServerError('Unable to connect. Please check your network and try again.');
      } else {
        setServerError('Something went wrong. Please try again.');
      }
    }
  }

  return (
    <div className="min-h-dvh flex" style={{ background: '#080808' }}>
      {/* Left brand panel — hidden on mobile */}
      <div
        className="hidden lg:flex flex-col justify-between w-[420px] shrink-0 p-10"
        style={{
          background: 'linear-gradient(160deg, #080808 0%, #111111 100%)',
          borderRight: '1px solid #1a1a1a',
        }}
      >
        <div>
          {/* Logo mark */}
          <div className="flex items-center gap-3 mb-16">
            <div
              className="w-8 h-8 rounded flex items-center justify-center text-white text-sm font-bold"
              style={{ background: '#b11116', fontFamily: 'Space Grotesk, system-ui' }}
            >
              N
            </div>
            <span
              className="text-lg font-semibold tracking-tight"
              style={{ fontFamily: 'Space Grotesk, system-ui', color: '#f0f0f0' }}
            >
              NForce
            </span>
          </div>

          <h1
            className="text-4xl font-bold leading-tight mb-4"
            style={{ fontFamily: 'Space Grotesk, system-ui', color: '#f0f0f0' }}
          >
            OneHR
          </h1>
          <p className="text-base leading-relaxed" style={{ color: '#5a5a5a' }}>
            People operations,<br />built right.
          </p>
        </div>

        <div>
          <div className="flex gap-1 mb-3">
            {[...Array(4)].map((_, i) => (
              <div
                key={i}
                className="h-0.5 rounded-full"
                style={{ width: i === 0 ? 24 : 8, background: i === 0 ? '#b11116' : '#2a2a2a' }}
              />
            ))}
          </div>
          <p className="text-xs" style={{ color: '#3a3a3a', fontFamily: 'JetBrains Mono, monospace' }}>
            Confidential — internal use only
          </p>
        </div>
      </div>

      {/* Right form panel */}
      <div className="flex-1 flex items-center justify-center p-6">
        <div className="w-full max-w-[400px]">
          {/* Mobile logo */}
          <div className="flex items-center gap-2 mb-10 lg:hidden">
            <div
              className="w-7 h-7 rounded flex items-center justify-center text-white text-xs font-bold"
              style={{ background: '#b11116', fontFamily: 'Space Grotesk, system-ui' }}
            >
              N
            </div>
            <span
              className="text-base font-semibold"
              style={{ fontFamily: 'Space Grotesk, system-ui', color: '#f0f0f0' }}
            >
              NForce OneHR
            </span>
          </div>

          <div className="mb-8">
            <h2
              className="text-2xl font-semibold mb-1"
              style={{ fontFamily: 'Space Grotesk, system-ui', color: '#f0f0f0' }}
            >
              Sign in
            </h2>
            <p className="text-sm" style={{ color: '#5a5a5a' }}>
              Use your NForce email address
            </p>
          </div>

          {/* Server-level error */}
          {serverError && (
            <div
              className="mb-5 px-4 py-3 rounded-lg text-sm"
              style={{
                background: 'rgba(239,68,68,0.08)',
                border: '1px solid rgba(239,68,68,0.25)',
                color: '#ef4444',
              }}
              role="alert"
            >
              {serverError}
            </div>
          )}

          <form onSubmit={handleSubmit(onSubmit)} noValidate>
            {/* Email */}
            <div className="mb-4">
              <label
                htmlFor="email"
                className="block text-sm font-medium mb-1.5"
                style={{ color: '#a0a0a0' }}
              >
                Email address <span style={{ color: '#b11116' }}>*</span>
              </label>
              <input
                id="email"
                type="email"
                autoComplete="email"
                placeholder="you@nforce.in"
                aria-describedby={errors.email ? 'email-error' : undefined}
                aria-invalid={!!errors.email}
                {...register('email')}
                className="w-full px-3.5 py-2.5 rounded-lg text-sm outline-none transition-all"
                style={{
                  background: '#141414',
                  border: `1px solid ${errors.email ? '#ef4444' : '#2a2a2a'}`,
                  color: '#f0f0f0',
                  fontFamily: 'Inter, system-ui',
                }}
                onFocus={(e) => {
                  if (!errors.email) e.currentTarget.style.borderColor = '#b11116';
                }}
                onBlur={(e) => {
                  if (!errors.email) e.currentTarget.style.borderColor = '#2a2a2a';
                }}
              />
              {errors.email && (
                <p id="email-error" className="mt-1.5 text-xs" style={{ color: '#ef4444' }}>
                  {errors.email.message}
                </p>
              )}
            </div>

            {/* Password */}
            <div className="mb-5">
              <div className="flex items-center justify-between mb-1.5">
                <label
                  htmlFor="password"
                  className="text-sm font-medium"
                  style={{ color: '#a0a0a0' }}
                >
                  Password <span style={{ color: '#b11116' }}>*</span>
                </label>
                <a
                  href="/forgot-password"
                  onClick={(e) => e.preventDefault()}
                  className="text-xs transition-colors"
                  style={{ color: '#5a5a5a' }}
                  onMouseEnter={(e) => (e.currentTarget.style.color = '#b11116')}
                  onMouseLeave={(e) => (e.currentTarget.style.color = '#5a5a5a')}
                >
                  Forgot password?
                </a>
              </div>
              <div className="relative">
                <input
                  id="password"
                  type={showPassword ? 'text' : 'password'}
                  autoComplete="current-password"
                  aria-describedby={errors.password ? 'password-error' : undefined}
                  aria-invalid={!!errors.password}
                  {...register('password')}
                  className="w-full px-3.5 py-2.5 pr-10 rounded-lg text-sm outline-none transition-all"
                  style={{
                    background: '#141414',
                    border: `1px solid ${errors.password ? '#ef4444' : '#2a2a2a'}`,
                    color: '#f0f0f0',
                    fontFamily: 'Inter, system-ui',
                  }}
                  onFocus={(e) => {
                    if (!errors.password) e.currentTarget.style.borderColor = '#b11116';
                  }}
                  onBlur={(e) => {
                    if (!errors.password) e.currentTarget.style.borderColor = '#2a2a2a';
                  }}
                />
                <button
                  type="button"
                  onClick={() => setShowPassword((v) => !v)}
                  className="absolute right-3 top-1/2 -translate-y-1/2 cursor-pointer transition-colors"
                  style={{ color: '#5a5a5a', background: 'none', border: 'none', padding: 0 }}
                  onMouseEnter={(e) => (e.currentTarget.style.color = '#a0a0a0')}
                  onMouseLeave={(e) => (e.currentTarget.style.color = '#5a5a5a')}
                  aria-label={showPassword ? 'Hide password' : 'Show password'}
                >
                  <EyeIcon open={showPassword} />
                </button>
              </div>
              {errors.password && (
                <p id="password-error" className="mt-1.5 text-xs" style={{ color: '#ef4444' }}>
                  {errors.password.message}
                </p>
              )}
            </div>

            {/* Sign in button */}
            <button
              type="submit"
              disabled={isSubmitting}
              className="w-full py-2.5 rounded-lg text-sm font-semibold cursor-pointer transition-all"
              style={{
                background: isSubmitting ? '#7a0b0f' : '#b11116',
                color: '#ffffff',
                border: 'none',
                fontFamily: 'Inter, system-ui',
                opacity: isSubmitting ? 0.8 : 1,
              }}
              onMouseEnter={(e) => {
                if (!isSubmitting) e.currentTarget.style.background = '#e4373d';
              }}
              onMouseLeave={(e) => {
                if (!isSubmitting) e.currentTarget.style.background = '#b11116';
              }}
            >
              {isSubmitting ? (
                <span className="flex items-center justify-center gap-2">
                  <svg
                    className="animate-spin"
                    width="16"
                    height="16"
                    viewBox="0 0 24 24"
                    fill="none"
                    stroke="currentColor"
                    strokeWidth="2"
                  >
                    <path d="M21 12a9 9 0 1 1-6.219-8.56" />
                  </svg>
                  Signing in…
                </span>
              ) : (
                'Sign in'
              )}
            </button>
          </form>

          {/* Divider */}
          <div className="flex items-center gap-3 my-6">
            <div className="flex-1 h-px" style={{ background: '#1e1e1e' }} />
            <span className="text-xs" style={{ color: '#3a3a3a' }}>
              or
            </span>
            <div className="flex-1 h-px" style={{ background: '#1e1e1e' }} />
          </div>

          {/* Disabled Microsoft SSO */}
          <div className="relative">
            <button
              type="button"
              disabled
              aria-disabled="true"
              className="w-full py-2.5 rounded-lg text-sm flex items-center justify-center gap-3"
              style={{
                background: '#0f0f0f',
                border: '1px solid #1e1e1e',
                color: '#3a3a3a',
                cursor: 'not-allowed',
                fontFamily: 'Inter, system-ui',
              }}
            >
              {/* Microsoft logo SVG */}
              <svg width="16" height="16" viewBox="0 0 21 21" style={{ opacity: 0.3 }}>
                <rect x="1" y="1" width="9" height="9" fill="#f25022" />
                <rect x="11" y="1" width="9" height="9" fill="#7fba00" />
                <rect x="1" y="11" width="9" height="9" fill="#00a4ef" />
                <rect x="11" y="11" width="9" height="9" fill="#ffb900" />
              </svg>
              Continue with Microsoft SSO
            </button>
            <span
              className="absolute -top-2 right-3 text-[10px] font-medium px-1.5 py-0.5 rounded"
              style={{
                background: '#1a1a1a',
                color: '#5a5a5a',
                border: '1px solid #2a2a2a',
                fontFamily: 'Inter, system-ui',
              }}
            >
              Coming soon
            </span>
          </div>
        </div>
      </div>
    </div>
  );
}
