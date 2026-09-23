// Per-user recent global-search DESTINATIONS, kept in localStorage (same pattern as
// themePreference.ts/useTimeFormatPreference.ts) — no backend table for what's a purely
// per-browser convenience, most recent first, deduplicated by path, capped at MAX_ENTRIES.
//
// Deliberately not the raw text the user typed: "recent searches" that just re-fills the search
// box with old query text isn't useful history — what's actually worth remembering is which
// specific place they landed on (a nav item, an employee record, a leave request, ...), so
// clicking an entry jumps straight back there. See Shell.tsx's handleResultSelect/
// handleRecentSearchClick for where entries are added/consumed.
//
// Takes the store as a parameter (default: the real `localStorage`) so it can be exercised with
// a fake store in tests, same reasoning as themePreference.ts's readStoredTheme.

const STORAGE_PREFIX = 'onehr.search.recent';
const MAX_ENTRIES = 5;

type Store = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>;

/** One destination reached from the global search bar — a nav item or a specific search result. */
export interface RecentDestination {
  /** What to show in the "Recent Searches" list, e.g. "Leave & Holidays" or an employee's name. */
  label: string;
  /** The in-app route to navigate to when this entry is clicked again. */
  path: string;
}

function storageKey(userEmail: string): string {
  return `${STORAGE_PREFIX}:${userEmail.toLowerCase()}`;
}

function isRecentDestination(value: unknown): value is RecentDestination {
  return typeof value === 'object' && value !== null
    && typeof (value as Record<string, unknown>).label === 'string'
    && typeof (value as Record<string, unknown>).path === 'string';
}

export function readRecentSearches(userEmail: string, store: Store = localStorage): RecentDestination[] {
  try {
    const raw = store.getItem(storageKey(userEmail));
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    // A pre-existing string[] from before destinations were tracked (or any other malformed
    // value) simply filters down to an empty list here — self-healing, no migration needed for
    // what was always just a per-viewer convenience.
    return Array.isArray(parsed) ? parsed.filter(isRecentDestination) : [];
  } catch {
    // localStorage unavailable (private mode, etc.) — no persisted history to honor.
    return [];
  }
}

/** Adds `destination` to the front of the list (deduped by path), capped at MAX_ENTRIES. */
export function addRecentSearch(
  userEmail: string, destination: RecentDestination, store: Store = localStorage,
): RecentDestination[] {
  const label = destination.label.trim();
  const path = destination.path.trim();
  if (!label || !path) return readRecentSearches(userEmail, store);
  const existing = readRecentSearches(userEmail, store);
  const next = [{ label, path }, ...existing.filter(d => d.path !== path)].slice(0, MAX_ENTRIES);
  try { store.setItem(storageKey(userEmail), JSON.stringify(next)); } catch { /* best effort */ }
  return next;
}

export function clearRecentSearches(userEmail: string, store: Store = localStorage): void {
  try { store.removeItem(storageKey(userEmail)); } catch { /* best effort */ }
}
