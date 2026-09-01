import { useAuthStore } from '../store/authStore';

// Centralized handler for server-initiated session invalidation — session timeout, a
// password change (AuthService#changePassword/forgotPassword, UserManagementService
// #resetPassword), a profile/role change (UserManagementService#updateUser bumping
// User.tokenVersion), or deactivation. None of the frontend/src/api/*.ts modules share a
// common HTTP client — each calls the global fetch() directly — so this wraps fetch itself
// once at app startup instead of touching every module.
//
// A stale/invalid/missing JWT never reaches a controller: JwtAuthenticationFilter leaves the
// SecurityContext empty, so Spring Security's filter-chain-level `.anyRequest().authenticated()`
// check rejects it with an empty-bodied 403 before Spring MVC (and GlobalExceptionHandler) ever
// runs. An authenticated-but-unauthorized request (e.g. an Employee hitting a
// @PreAuthorize("hasRole('SUPER_ADMIN')") endpoint) is thrown from inside the controller
// invocation instead, so it always comes back as a 403 with a JSON error body from
// GlobalExceptionHandler#handleAccessDenied. The empty body is what tells the two apart —
// only the former means "this session is no longer valid."
export const SESSION_INVALIDATED_MESSAGE = 'Your password was changed. Please sign in again.';
export const SESSION_TIMEOUT_MESSAGE = 'Your session has timed out. Please sign in again.';
export const SESSION_PROFILE_UPDATED_MESSAGE =
  'Your account details were updated by an administrator. Please sign in again.';
export const SESSION_DEACTIVATED_MESSAGE =
  'Your account has been deactivated. Please contact your HR administrator.';
// Fallback for a stale/malformed/tampered token with no more specific cause recorded (see
// JwtAuthenticationFilter's SESSION_INVALIDATED default, and the "no X-Session-Reason header at
// all" case below — a token that fails signature/parse checks without being expired).
const SESSION_GENERIC_MESSAGE = 'Your session is no longer valid. Please sign in again.';

// The empty 403 body itself is identical for every forced-logout cause below, so the frontend
// can only tell them apart via the X-Session-Reason response header JwtAuthenticationFilter sets
// (backend) — see JwtAuthenticationFilter#SESSION_REASON_HEADER for exactly when each value is
// set. Keys not listed here (or a missing header) fall back to SESSION_GENERIC_MESSAGE.
const SESSION_REASON_MESSAGES: Record<string, string> = {
  EXPIRED: SESSION_TIMEOUT_MESSAGE,
  PASSWORD_CHANGED: SESSION_INVALIDATED_MESSAGE,
  PROFILE_UPDATED: SESSION_PROFILE_UPDATED_MESSAGE,
  DEACTIVATED: SESSION_DEACTIVATED_MESSAGE,
  // tokenVersion went stale but no reason was recorded for the bump (e.g. a row from before
  // token_version_reason existed) — still an accurate "signed out elsewhere" message.
  SESSION_INVALIDATED: SESSION_GENERIC_MESSAGE,
};

const SESSION_MESSAGE_KEY = 'onehr:authMessage';

// Must be called immediately before the hard `window.location.href` redirect that consumes it
// (never before a client-side route change) — see the read-once note on consumeSessionMessage
// below for why. Shared by the fetch interceptor below and Shell.tsx's FORCE_LOGOUT SSE handler,
// the two places a forced logout originates.
export function stashSessionMessageForLogin(message: string): void {
  sessionStorage.setItem(SESSION_MESSAGE_KEY, message);
}

let handledSessionInvalidation = false;

export function installAuthFetch(): void {
  const originalFetch = window.fetch.bind(window);

  window.fetch = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const response = await originalFetch(input, init);

    if (response.status === 403 && !handledSessionInvalidation) {
      const url = typeof input === 'string' ? input : input.toString();
      const isApiCall = url.includes('/api/');
      const isAuthenticatedCall = hasBearerAuth(init?.headers) || hasBearerAuth((input as Request)?.headers);

      if (isApiCall && isAuthenticatedCall) {
        const bodyText = await response.clone().text().catch(() => '');
        if (bodyText.trim().length === 0) {
          handledSessionInvalidation = true;
          useAuthStore.getState().clearAuth();
          // Only ever write the message immediately before the redirect that consumes it —
          // if we're already on /login there's no reload left in this page load to read it
          // back, and an unconsumed key would leak sessionStorage-inheriting tabs (a
          // reopened-closed tab, a duplicated tab, a crash/session restore) into showing a
          // phantom banner for a session invalidation that isn't theirs.
          if (window.location.pathname !== '/login') {
            const reason = response.headers.get('X-Session-Reason');
            const message = (reason && SESSION_REASON_MESSAGES[reason]) || SESSION_GENERIC_MESSAGE;
            stashSessionMessageForLogin(message);
            window.location.href = '/login';
          }
        }
      }
    }

    return response;
  };
}

function hasBearerAuth(headers: HeadersInit | undefined): boolean {
  if (!headers) return false;
  if (headers instanceof Headers) return headers.has('Authorization');
  if (Array.isArray(headers)) return headers.some(([key]) => key.toLowerCase() === 'authorization');
  return Object.keys(headers).some((key) => key.toLowerCase() === 'authorization');
}

// Captured once at module evaluation — not inside a function a React hook calls — because
// main.tsx renders in <StrictMode>, which deliberately double-invokes functions passed to
// useState/useReducer/useMemo in development to catch impurity. Reading-and-clearing
// sessionStorage inside such a function is exactly that impurity: the second invocation would
// find it already cleared and silently return null. A module is evaluated exactly once per page
// load, and this message is only ever written (via stashSessionMessageForLogin) immediately
// before a hard `window.location.href` redirect, so capturing it here at import time is always
// in time to see it.
const pendingSessionMessage = sessionStorage.getItem(SESSION_MESSAGE_KEY);
if (pendingSessionMessage) sessionStorage.removeItem(SESSION_MESSAGE_KEY);

/** Returns the one-shot message left for the login page by a forced session logout, if any. */
export function consumeSessionMessage(): string | null {
  return pendingSessionMessage;
}
