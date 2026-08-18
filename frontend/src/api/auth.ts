import { API_ORIGIN } from './config';
const BASE = `${API_ORIGIN}/api`;

export interface LoginResponse {
  token: string;
  mustChangePassword: boolean;
  email: string;
  role?: string;
}

export interface ChangePasswordResponse {
  token: string;
  message: string;
}

interface ApiErrorBody {
  message?: string;
  code?: string;
  lockedUntil?: string;
}

// Thrown instead of a plain Error when the backend responds 423 Locked with
// code: "ACCOUNT_LOCKED" (see GlobalExceptionHandler#handleAccountLocked), so the login
// page can render a locked-account state instead of the generic invalid-credentials message.
export class LoginLockedError extends Error {
  lockedUntil: string;

  constructor(message: string, lockedUntil: string) {
    super(message);
    this.name = 'LoginLockedError';
    this.lockedUntil = lockedUntil;
  }
}

async function handle<T>(res: Response): Promise<T> {
  let body: ApiErrorBody = {};
  try {
    body = await res.json();
  } catch {
    // non-JSON response
  }
  if (!res.ok) {
    if (res.status === 423 && body.code === 'ACCOUNT_LOCKED' && body.lockedUntil) {
      throw new LoginLockedError(body.message ?? 'Account locked', body.lockedUntil);
    }
    throw new Error(body.message ?? 'Request failed');
  }
  return body as T;
}

// Text shared by both failure paths the backend can return for forgot-password: a malformed
// email (400, via @Email on ForgotPasswordRequest) and a well-formed but unregistered email
// (404, via GlobalExceptionHandler#handleAccountNotFound). The forgot-password page matches on
// this exact text to show the error instead of silently treating the request as a success.
export const INVALID_EMAIL_MESSAGE = 'Invalid E-mail';

export const authApi = {
  login: (email: string, password: string) =>
    fetch(`${BASE}/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email, password }),
    }).then(handle<LoginResponse>),

  changePassword: (
    currentPassword: string,
    newPassword: string,
    confirmPassword: string,
    token: string,
    signal?: AbortSignal
  ) =>
    fetch(`${BASE}/auth/change-password`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`,
      },
      body: JSON.stringify({ currentPassword, newPassword, confirmPassword }),
      signal,
    }).then(handle<ChangePasswordResponse>),

  forgotPassword: (email: string) =>
    fetch(`${BASE}/auth/forgot-password`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email }),
    }).then(handle<{ message: string }>),

  // One-time, short-lived ticket for opening the force-logout SSE stream (see Shell.tsx) —
  // native EventSource can't send an Authorization header, so this authenticated call trades
  // the real token for an opaque ticket that's safe to put in that connection's query string.
  issueEventsTicket: (token: string) =>
    fetch(`${BASE}/auth/events/ticket`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}` },
    }).then(handle<{ ticket: string }>),
};
