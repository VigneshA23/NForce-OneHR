// Per-user recent global-search queries, kept in localStorage (same pattern as
// themePreference.ts/useTimeFormatPreference.ts) — no backend table for what's a purely
// per-browser convenience, most recent first, deduplicated, capped at MAX_ENTRIES.
//
// Takes the store as a parameter (default: the real `localStorage`) so it can be exercised with
// a fake store in tests, same reasoning as themePreference.ts's readStoredTheme.

const STORAGE_PREFIX = 'onehr.search.recent';
const MAX_ENTRIES = 5;

type Store = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>;

function storageKey(userEmail: string): string {
  return `${STORAGE_PREFIX}:${userEmail.toLowerCase()}`;
}

export function readRecentSearches(userEmail: string, store: Store = localStorage): string[] {
  try {
    const raw = store.getItem(storageKey(userEmail));
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed.filter((v): v is string => typeof v === 'string') : [];
  } catch {
    // localStorage unavailable (private mode, etc.) — no persisted history to honor.
    return [];
  }
}

/** Adds `query` to the front of the list (deduped, case-insensitive), capped at MAX_ENTRIES. */
export function addRecentSearch(userEmail: string, query: string, store: Store = localStorage): string[] {
  const trimmed = query.trim();
  if (!trimmed) return readRecentSearches(userEmail, store);
  const existing = readRecentSearches(userEmail, store);
  const next = [trimmed, ...existing.filter(q => q.toLowerCase() !== trimmed.toLowerCase())].slice(0, MAX_ENTRIES);
  try { store.setItem(storageKey(userEmail), JSON.stringify(next)); } catch { /* best effort */ }
  return next;
}

export function clearRecentSearches(userEmail: string, store: Store = localStorage): void {
  try { store.removeItem(storageKey(userEmail)); } catch { /* best effort */ }
}
