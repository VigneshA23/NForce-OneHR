import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import {
  THEME_STORAGE_KEY, readStoredThemeMode, resolveAppliedTheme,
  type Theme, type ThemeMode,
} from './themePreference';
import { useAuthStore } from '../store/authStore';
import { userScopedKey } from './userScopedStorageKey';

// A thin adapter, not a change to themePreference.ts's own (deliberately pure, browser-free,
// unit-tested) functions — it redirects whatever key those functions ask for to this user's
// scoped key instead, so per-user scoping stays entirely in this side-effect-ful module.
function scopedLocalStorage(): Pick<Storage, 'getItem'> {
  return { getItem: (key: string) => window.localStorage.getItem(userScopedKey(key)) };
}

export type { Theme, ThemeMode };

function prefersLightNow(): boolean {
  try {
    return window.matchMedia('(prefers-color-scheme: light)').matches;
  } catch {
    return false;
  }
}

function getInitialMode(): ThemeMode {
  return readStoredThemeMode(scopedLocalStorage()) ?? 'auto';
}

let _mode: ThemeMode = getInitialMode();
let _theme: Theme = resolveAppliedTheme(_mode, prefersLightNow());

function applyTheme(theme: Theme) {
  const html = document.documentElement;
  html.setAttribute('data-theme', theme);
  html.classList.add('nf-theme-transitioning');
  setTimeout(() => html.classList.remove('nf-theme-transitioning'), 200);
}

function persistMode(mode: ThemeMode) {
  try { window.localStorage.setItem(userScopedKey(THEME_STORAGE_KEY), mode); } catch { /* best effort */ }
}

// Runs at module load, before ThemeProvider ever mounts — so the persisted theme is applied
// on the very first paint instead of flashing the default and then switching.
applyTheme(_theme);

interface ThemeContextValue {
  /** The concrete theme actually painted right now. */
  theme: Theme;
  /** The user's chosen display mode — 'auto' tracks the OS, the other two pin it explicitly. */
  mode: ThemeMode;
  setMode: (mode: ThemeMode) => void;
}

const ThemeContext = createContext<ThemeContextValue>({
  theme: 'dark',
  mode: 'auto',
  setMode: () => {},
});

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [mode, setModeState]   = useState<ThemeMode>(_mode);
  const [theme, setThemeState] = useState<Theme>(_theme);
  const email = useAuthStore(s => s.user?.email);

  function setMode(next: ThemeMode) {
    _mode = next;
    persistMode(next);
    setModeState(next);
    const applied = resolveAppliedTheme(next, prefersLightNow());
    _theme = applied;
    applyTheme(applied);
    setThemeState(applied);
  }

  // Re-reads and re-applies whenever the signed-in user changes — otherwise whichever account's
  // mode was applied at module-load time would keep showing for the next person who signs in
  // without a full page reload.
  useEffect(() => {
    const nextMode = getInitialMode();
    _mode = nextMode;
    setModeState(nextMode);
    const applied = resolveAppliedTheme(nextMode, prefersLightNow());
    _theme = applied;
    applyTheme(applied);
    setThemeState(applied);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [email]);

  // While in 'auto', keep tracking the OS preference live instead of only reading it once.
  useEffect(() => {
    if (mode !== 'auto') return;
    let mql: MediaQueryList;
    try {
      mql = window.matchMedia('(prefers-color-scheme: light)');
    } catch {
      return;
    }
    function onChange() {
      const applied = resolveAppliedTheme('auto', mql.matches);
      _theme = applied;
      applyTheme(applied);
      setThemeState(applied);
    }
    mql.addEventListener('change', onChange);
    return () => mql.removeEventListener('change', onChange);
  }, [mode]);

  return (
    <ThemeContext.Provider value={{ theme, mode, setMode }}>
      {children}
    </ThemeContext.Provider>
  );
}

export function useTheme(): ThemeContextValue {
  return useContext(ThemeContext);
}
