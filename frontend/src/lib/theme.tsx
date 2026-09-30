import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import {
  THEME_STORAGE_KEY, readStoredThemeMode, resolveAppliedTheme,
  type Theme, type ThemeMode,
} from './themePreference';

export type { Theme, ThemeMode };

function prefersLightNow(): boolean {
  try {
    return window.matchMedia('(prefers-color-scheme: light)').matches;
  } catch {
    return false;
  }
}

function getInitialMode(): ThemeMode {
  return readStoredThemeMode(window.localStorage) ?? 'auto';
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
  try { window.localStorage.setItem(THEME_STORAGE_KEY, mode); } catch { /* best effort */ }
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

  function setMode(next: ThemeMode) {
    _mode = next;
    persistMode(next);
    setModeState(next);
    const applied = resolveAppliedTheme(next, prefersLightNow());
    _theme = applied;
    applyTheme(applied);
    setThemeState(applied);
  }

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
