import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { useNavigate } from 'react-router-dom';
import { authApi } from '../api/auth';
import { useAuthStore } from '../store/authStore';
import { BrandMark } from '../components/BrandMark';
import {
  PASSWORD_CRITERIA as CRITERIA,
  PASSWORD_MIN_LENGTH,
  PASSWORD_SPACE_MESSAGE,
  containsSpace,
  passwordStrengthScore as strengthScore,
  validateNewPassword,
} from '../utils/passwordPolicy';

type FormValues = {
  currentPassword?: string;
  newPassword: string;
  confirmPassword: string;
};

function EyeIcon({ open }: { open: boolean }) {
  return open ? (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z" />
      <circle cx="12" cy="12" r="3" />
    </svg>
  ) : (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94" />
      <path d="M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19" />
      <line x1="1" y1="1" x2="23" y2="23" />
    </svg>
  );
}

function PasswordField({
  id,
  label,
  required,
  registration,
  error,
  autoComplete,
}: {
  id: string;
  label: string;
  required?: boolean;
  registration: ReturnType<ReturnType<typeof useForm<FormValues>>['register']>;
  error?: string;
  autoComplete?: string;
}) {
  const [show, setShow] = useState(false);

  return (
    <div className="mb-4">
      <label htmlFor={id} className="block text-sm font-medium mb-1.5" style={{ color: '#a0a0a0' }}>
        {label} {required && <span style={{ color: '#b11116' }}>*</span>}
      </label>
      <div className="relative">
        <input
          id={id}
          type={show ? 'text' : 'password'}
          autoComplete={autoComplete}
          aria-describedby={error ? `${id}-error` : undefined}
          aria-invalid={!!error}
          {...registration}
          className="w-full px-3.5 py-2.5 pr-10 rounded-lg text-sm outline-none transition-all"
          style={{
            background: '#141414',
            border: `1px solid ${error ? '#ef4444' : '#2a2a2a'}`,
            color: '#f0f0f0',
            fontFamily: 'Inter, system-ui',
          }}
          onFocus={(e) => {
            if (!error) e.currentTarget.style.borderColor = '#b11116';
          }}
          onBlur={(e) => {
            registration.onBlur(e);
            if (!error) e.currentTarget.style.borderColor = '#2a2a2a';
          }}
        />
        <button
          type="button"
          onClick={() => setShow((v) => !v)}
          className="absolute right-3 top-1/2 -translate-y-1/2 cursor-pointer transition-colors"
          style={{ color: '#5a5a5a', background: 'none', border: 'none', padding: 0 }}
          onMouseEnter={(e) => (e.currentTarget.style.color = '#a0a0a0')}
          onMouseLeave={(e) => (e.currentTarget.style.color = '#5a5a5a')}
          aria-label={show ? 'Hide password' : 'Show password'}
        >
          <EyeIcon open={show} />
        </button>
      </div>
      {error && (
        <p id={`${id}-error`} className="mt-1.5 text-xs" style={{ color: '#ef4444' }}>
          {error}
        </p>
      )}
    </div>
  );
}

export default function ChangePasswordPage() {
  const navigate = useNavigate();
  const { token, user, setAuth, clearAuth } = useAuthStore();
  const [serverError, setServerError] = useState<string | null>(null);
  const [showNew, setShowNew] = useState(false);

  const [loading, setLoading] = useState(false);

  // Forced flow: user just signed in with a temp/reset password and must set a new one before
  // continuing (see Login.tsx and App.tsx's RequirePasswordChanged guard). They already proved
  // they know the current password by using it to log in, so it isn't collected again here.
  // Voluntary flow (My Profile → Change Password): mustChangePassword is false, so all three
  // fields render as before.
  const isForcedFlow = !!user?.mustChangePassword;

  const {
    register,
    handleSubmit,
    watch,
    setError,
    clearErrors,
    formState: { errors },
  } = useForm<FormValues>({ mode: 'onSubmit' });

  const newPasswordValue = watch('newPassword', '');
  const score = strengthScore(newPasswordValue);

  async function onSubmit(data: FormValues) {
    clearErrors();
    setServerError(null);

    if (!token) {
      navigate('/login', { replace: true });
      return;
    }

    let invalid = false;

    if (!isForcedFlow && !data.currentPassword) {
      setError('currentPassword', { message: 'Current password is required' });
      invalid = true;
    }
    const newPasswordError = validateNewPassword(data.newPassword);
    if (newPasswordError) {
      setError('newPassword', { message: newPasswordError });
      invalid = true;
    }
    if (!data.confirmPassword) {
      setError('confirmPassword', { message: 'Please confirm your new password' });
      invalid = true;
    } else if (containsSpace(data.confirmPassword)) {
      setError('confirmPassword', { message: PASSWORD_SPACE_MESSAGE });
      invalid = true;
    } else if (data.newPassword !== data.confirmPassword) {
      setError('confirmPassword', { message: 'Passwords do not match' });
      invalid = true;
    }

    if (invalid) return;

    setLoading(true);
    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), 13000);
    try {
      const res = await authApi.changePassword(
        isForcedFlow ? undefined : data.currentPassword,
        data.newPassword,
        data.confirmPassword,
        token,
        controller.signal
      );
      clearTimeout(timeoutId);
      setAuth(res.token, {
        email: user!.email,
        fullName: user?.fullName,
        mustChangePassword: false,
        role: user?.role,
      });
      navigate('/dashboard', { replace: true });
    } catch (err: unknown) {
      clearTimeout(timeoutId);
      const isAbort = err instanceof Error && err.name === 'AbortError';
      const msg = isAbort
        ? 'Request timed out. Check your connection and try again.'
        : err instanceof Error
        ? err.message
        : 'Something went wrong';
      setServerError(msg);
    } finally {
      setLoading(false);
    }
  }

  const strengthColors = ['#2a2a2a', '#ef4444', '#f59e0b', '#22c55e', '#22c55e'];
  const strengthLabels = ['', 'Weak', 'Fair', 'Good', 'Strong'];

  return (
    <div className="min-h-dvh flex items-center justify-center p-6" style={{ background: '#080808' }}>
      <div className="w-full max-w-[420px]">
        {/* Logo */}
        <div className="flex items-center gap-2 mb-10">
          <BrandMark size="sm" />
          <span
            className="text-base font-semibold"
            style={{ fontFamily: 'Space Grotesk, system-ui', color: '#f0f0f0' }}
          >
            NForce OneHR
          </span>
        </div>

        {/* Heading */}
        <div className="mb-6">
          <h2
            className="text-2xl font-semibold mb-2"
            style={{ fontFamily: 'Space Grotesk, system-ui', color: '#f0f0f0' }}
          >
            {isForcedFlow ? 'Set your password' : 'Change your password'}
          </h2>
          <p className="text-sm leading-relaxed" style={{ color: '#5a5a5a' }}>
            {isForcedFlow
              ? 'For security, you must set a new password before you can access the platform. This step cannot be skipped.'
              : 'Enter your current password and choose a new one.'}
          </p>
        </div>

        {/* Server error */}
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
          {!isForcedFlow && (
            <PasswordField
              id="currentPassword"
              label="Current password"
              required
              registration={register('currentPassword')}
              error={errors.currentPassword?.message}
              autoComplete="current-password"
            />
          )}

          {/* New password with strength meter */}
          <div className="mb-4">
            <label htmlFor="newPassword" className="block text-sm font-medium mb-1.5" style={{ color: '#a0a0a0' }}>
              New password <span style={{ color: '#b11116' }}>*</span>
            </label>
            <div className="relative">
              <input
                id="newPassword"
                type={showNew ? 'text' : 'password'}
                autoComplete="new-password"
                aria-describedby="newPassword-error newPassword-strength"
                aria-invalid={!!errors.newPassword}
                {...register('newPassword')}
                className="w-full px-3.5 py-2.5 pr-10 rounded-lg text-sm outline-none transition-all"
                style={{
                  background: '#141414',
                  border: `1px solid ${errors.newPassword ? '#ef4444' : '#2a2a2a'}`,
                  color: '#f0f0f0',
                  fontFamily: 'Inter, system-ui',
                }}
                onFocus={(e) => {
                  if (!errors.newPassword) e.currentTarget.style.borderColor = '#b11116';
                }}
                onBlur={(e) => {
                  register('newPassword').onBlur(e);
                  if (!errors.newPassword) e.currentTarget.style.borderColor = '#2a2a2a';
                }}
              />
              <button
                type="button"
                onClick={() => setShowNew(v => !v)}
                className="absolute right-3 top-1/2 -translate-y-1/2 cursor-pointer transition-colors"
                style={{ color: '#5a5a5a', background: 'none', border: 'none', padding: 0 }}
                onMouseEnter={(e) => (e.currentTarget.style.color = '#a0a0a0')}
                onMouseLeave={(e) => (e.currentTarget.style.color = '#5a5a5a')}
                aria-label={showNew ? 'Hide password' : 'Show password'}
              >
                <EyeIcon open={showNew} />
              </button>
            </div>

            {/* Strength bars */}
            {newPasswordValue && (
              <div className="mt-2">
                <div className="flex gap-1 mb-1" id="newPassword-strength" aria-live="polite" aria-label={`Password strength: ${strengthLabels[score]}`}>
                  {[1, 2, 3, 4].map((level) => (
                    <div
                      key={level}
                      className="h-1 flex-1 rounded-full transition-all duration-300"
                      style={{ background: level <= score ? strengthColors[score] : '#2a2a2a' }}
                    />
                  ))}
                </div>
                <div className="flex items-center justify-between">
                  <div className="flex flex-wrap gap-x-3 gap-y-1">
                    {[{ label: `At least ${PASSWORD_MIN_LENGTH} characters`, test: (p: string) => p.length >= PASSWORD_MIN_LENGTH }, ...CRITERIA].map((c) => (
                      <span
                        key={c.label}
                        className="text-[11px] flex items-center gap-1"
                        style={{ color: c.test(newPasswordValue) ? '#22c55e' : '#3a3a3a' }}
                      >
                        <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3">
                          {c.test(newPasswordValue) ? (
                            <polyline points="20 6 9 17 4 12" />
                          ) : (
                            <circle cx="12" cy="12" r="8" />
                          )}
                        </svg>
                        {c.label}
                      </span>
                    ))}
                  </div>
                  {score > 0 && (
                    <span className="text-[11px] font-medium" style={{ color: strengthColors[score] }}>
                      {strengthLabels[score]}
                    </span>
                  )}
                </div>
              </div>
            )}

            {errors.newPassword && (
              <p id="newPassword-error" className="mt-1.5 text-xs" style={{ color: '#ef4444' }}>
                {errors.newPassword.message}
              </p>
            )}
          </div>

          <PasswordField
            id="confirmPassword"
            label="Confirm new password"
            required
            registration={register('confirmPassword')}
            error={errors.confirmPassword?.message}
            autoComplete="new-password"
          />

          <button
            type="submit"
            disabled={loading}
            className="w-full py-2.5 rounded-lg text-sm font-semibold cursor-pointer transition-all mt-2"
            style={{
              background: loading ? '#7a0b0f' : '#b11116',
              color: '#ffffff',
              border: 'none',
              fontFamily: 'Inter, system-ui',
              opacity: loading ? 0.8 : 1,
            }}
            onMouseEnter={(e) => {
              if (!loading) e.currentTarget.style.background = '#e4373d';
            }}
            onMouseLeave={(e) => {
              if (!loading) e.currentTarget.style.background = '#b11116';
            }}
          >
            {loading ? (
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
                Updating password…
              </span>
            ) : isForcedFlow ? (
              'Set password & continue'
            ) : (
              'Update password'
            )}
          </button>
        </form>

        <p className="mt-5 text-xs text-center" style={{ color: '#3a3a3a' }}>
          Signed in as{' '}
          <span style={{ fontFamily: 'JetBrains Mono, monospace', color: '#5a5a5a' }}>
            {user?.email}
          </span>
        </p>

        <div className="mt-6 text-center">
          <button
            type="button"
            onClick={() => {
              if (isForcedFlow) {
                clearAuth();
                navigate('/login', { replace: true });
              } else {
                navigate('/profile');
              }
            }}
            className="text-sm cursor-pointer"
            style={{ background: 'none', border: 'none', color: '#8a8a8a', textDecoration: 'underline', textUnderlineOffset: '3px', padding: 0 }}
            onMouseEnter={(e) => (e.currentTarget.style.color = '#d0d0d0')}
            onMouseLeave={(e) => (e.currentTarget.style.color = '#8a8a8a')}
          >
            {isForcedFlow ? 'Back to Sign In' : 'Back to Profile'}
          </button>
        </div>
      </div>
    </div>
  );
}
