import { useAuthStore } from '../store/authStore';

// Centralized handler for server-initiated session invalidation (password change — see
// AuthService#changePassword/forgotPassword and UserManagementService#resetPassword bumping
// User.tokenVersion). None of the frontend/src/api/*.ts modules share a common HTTP client —
// each calls the global fetch() directly — so this wraps fetch itself once at app startup
// instead of touching every module.
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
const SESSION_MESSAGE_KEY = 'onehr:authMessage';

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
          sessionStorage.setItem(SESSION_MESSAGE_KEY, SESSION_INVALIDATED_MESSAGE);
          if (window.location.pathname !== '/login') {
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
// load, and this message is only ever written immediately before the hard `window.location.href`
// redirect below, so capturing it here at import time is always in time to see it.
const pendingSessionMessage = sessionStorage.getItem(SESSION_MESSAGE_KEY);
if (pendingSessionMessage) sessionStorage.removeItem(SESSION_MESSAGE_KEY);

/** Returns the one-shot message left for the login page by a forced session logout, if any. */
export function consumeSessionMessage(): string | null {
  return pendingSessionMessage;
}
