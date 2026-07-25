import { createContext, useContext, useState, type ReactNode } from 'react';

export type Theme = 'dark' | 'light';

function getInitialTheme(): Theme {
  try {
    return window.matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark';
  } catch {
    return 'dark';
  }
}

let _theme: Theme = getInitialTheme();

function applyTheme(theme: Theme) {
  const html = document.documentElement;
  html.setAttribute('data-theme', theme);
  html.classList.add('nf-theme-transitioning');
  setTimeout(() => html.classList.remove('nf-theme-transitioning'), 200);
}

applyTheme(_theme);

interface ThemeContextValue {
  theme: Theme;
  toggleTheme: () => void;
}

const ThemeContext = createContext<ThemeContextValue>({
  theme: 'dark',
  toggleTheme: () => {},
});

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [theme, setTheme] = useState<Theme>(_theme);

  function toggleTheme() {
    const next: Theme = theme === 'dark' ? 'light' : 'dark';
    _theme = next;
    applyTheme(next);
    setTheme(next);
  }

  return (
    <ThemeContext.Provider value={{ theme, toggleTheme }}>
      {children}
    </ThemeContext.Provider>
  );
}

export function useTheme(): ThemeContextValue {
  return useContext(ThemeContext);
}
