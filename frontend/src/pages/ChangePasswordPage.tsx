import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { useNavigate } from 'react-router-dom';
import { authApi } from '../api/auth';
import { useAuthStore } from '../store/authStore';
import { BrandMark } from '../components/BrandMark';
import { KeyRound, LockKeyhole, ShieldCheck, Check, Circle } from 'lucide-react';
import './changePassword.css';
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
    <div className="nf-cp-field">
      <label htmlFor={id} className="nf-cp-label">
        {label} {required && <span className="nf-cp-req">*</span>}
      </label>
      <div className={`nf-cp-input${error ? ' has-error' : ''}`}>
        <KeyRound size={16} className="nf-cp-lead" aria-hidden="true" />
        <input
          id={id}
          type={show ? 'text' : 'password'}
          autoComplete={autoComplete}
          aria-describedby={error ? `${id}-error` : undefined}
          aria-invalid={!!error}
          {...registration}
          onBlur={(e) => registration.onBlur(e)}
        />
        <button
          type="button"
          onClick={() => setShow((v) => !v)}
          className="nf-cp-eye"
          aria-label={show ? 'Hide password' : 'Show password'}
        >
          <EyeIcon open={show} />
        </button>
      </div>
      {error && <p id={`${id}-error`} className="nf-cp-err">{error}</p>}
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
  const checks = [{ label: `At least ${PASSWORD_MIN_LENGTH} characters`, test: (p: string) => p.length >= PASSWORD_MIN_LENGTH }, ...CRITERIA];

  return (
    <div className="nf-cp">
      <div className="nf-cp-glow" aria-hidden="true" />
      <div className="nf-cp-wrap">
        <div className="nf-cp-brand">
          <BrandMark size="sm" />
          <span>NForce OneHR</span>
        </div>

        <div className="nf-cp-card">
          <div className="nf-cp-head">
            <span className="nf-cp-ico"><LockKeyhole size={22} /></span>
            <div>
              <h2>{isForcedFlow ? 'Set your password' : 'Change your password'}</h2>
              <p>
                {isForcedFlow
                  ? 'For security, you must set a new password before you can access the platform. This step cannot be skipped.'
                  : 'Enter your current password and choose a new one.'}
              </p>
            </div>
          </div>

          {serverError && <div className="nf-cp-alert" role="alert">{serverError}</div>}

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
            <div className="nf-cp-field">
              <label htmlFor="newPassword" className="nf-cp-label">
                New password <span className="nf-cp-req">*</span>
              </label>
              <div className={`nf-cp-input${errors.newPassword ? ' has-error' : ''}`}>
                <KeyRound size={16} className="nf-cp-lead" aria-hidden="true" />
                <input
                  id="newPassword"
                  type={showNew ? 'text' : 'password'}
                  autoComplete="new-password"
                  aria-describedby="newPassword-error newPassword-strength"
                  aria-invalid={!!errors.newPassword}
                  {...register('newPassword')}
                />
                <button
                  type="button"
                  onClick={() => setShowNew(v => !v)}
                  className="nf-cp-eye"
                  aria-label={showNew ? 'Hide password' : 'Show password'}
                >
                  <EyeIcon open={showNew} />
                </button>
              </div>

              <div className="nf-cp-meter">
                <div className="nf-cp-bars" id="newPassword-strength" aria-live="polite" aria-label={`Password strength: ${strengthLabels[score]}`}>
                  {[1, 2, 3, 4].map((level) => (
                    <i key={level} style={{ background: level <= score ? strengthColors[score] : undefined }} />
                  ))}
                  <span style={{ color: score > 0 ? strengthColors[score] : undefined }}>{strengthLabels[score] || 'Strength'}</span>
                </div>
                <div className="nf-cp-rules">
                  {checks.map((c) => {
                    const ok = !!newPasswordValue && c.test(newPasswordValue);
                    return (
                      <span key={c.label} className={ok ? 'is-ok' : ''}>
                        {ok ? <Check size={12} /> : <Circle size={10} />}
                        {c.label}
                      </span>
                    );
                  })}
                </div>
              </div>

              {errors.newPassword && <p id="newPassword-error" className="nf-cp-err">{errors.newPassword.message}</p>}
            </div>

            <PasswordField
              id="confirmPassword"
              label="Confirm new password"
              required
              registration={register('confirmPassword')}
              error={errors.confirmPassword?.message}
              autoComplete="new-password"
            />

            <button type="submit" disabled={loading} className="nf-cp-submit">
              {loading ? (
                <span className="flex items-center justify-center gap-2">
                  <svg className="animate-spin" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
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

          <div className="nf-cp-foot">
            <p><ShieldCheck size={14} />Signed in as <b>{user?.email}</b></p>
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
            >
              {isForcedFlow ? 'Back to Sign In' : 'Back to Profile'}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
