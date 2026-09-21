// Pure theme-persistence logic, split out of theme.tsx so it can be unit-tested without a
// browser (window/document) — theme.tsx's module-level code applies the theme as a side
// effect on import, which a plain `node`-environment test can't safely trigger.

export type Theme = 'dark' | 'light';

export const THEME_STORAGE_KEY = 'onehr.theme';

/** Reads a persisted theme out of arbitrary storage — takes the store as a parameter (no
 * direct `localStorage` reference) so it can be exercised with a fake store in tests. */
export function readStoredTheme(storage: Pick<Storage, 'getItem'>): Theme | null {
  try {
    const v = storage.getItem(THEME_STORAGE_KEY);
    return v === 'dark' || v === 'light' ? v : null;
  } catch {
    // localStorage unavailable (private mode, etc.) — no persisted preference to honor.
    return null;
  }
}

/** The user's last choice always wins; only falls back to the system preference when nothing
 * has ever been persisted (first visit, or storage was cleared). */
export function resolveInitialTheme(stored: Theme | null, prefersLight: boolean): Theme {
  if (stored) return stored;
  return prefersLight ? 'light' : 'dark';
}

// ── Display mode (adds a persisted "auto" choice on top of the explicit dark/light above) ──────

export type ThemeMode = Theme | 'auto';

/** Same storage slot as readStoredTheme, widened to also accept the "auto" mode. */
export function readStoredThemeMode(storage: Pick<Storage, 'getItem'>): ThemeMode | null {
  try {
    const v = storage.getItem(THEME_STORAGE_KEY);
    return v === 'dark' || v === 'light' || v === 'auto' ? v : null;
  } catch {
    return null;
  }
}

/** Resolves a mode ('dark' | 'light' | 'auto') to the concrete theme actually painted —
 * 'auto' tracks the OS/browser preference live, the other two are explicit overrides. */
export function resolveAppliedTheme(mode: ThemeMode, prefersLight: boolean): Theme {
  if (mode === 'auto') return prefersLight ? 'light' : 'dark';
  return mode;
}
