import { useAuthStore } from '../store/authStore';

/**
 * Appends the current user's email to a base localStorage key. localStorage is scoped to the
 * browser origin, not to whoever happens to be signed in — a bare key (e.g. "onehr.accentColor")
 * is shared by every account that ever logs in on that browser, so one person's theme/accent/
 * accessibility choice silently becomes the next person's too. Falls back to the bare key when
 * nobody is signed in yet (e.g. the login page itself), since there's no "whose preference" to
 * scope to at that point.
 */
export function userScopedKey(baseKey: string): string {
  const email = useAuthStore.getState().user?.email;
  return email ? `${baseKey}.${email}` : baseKey;
}
