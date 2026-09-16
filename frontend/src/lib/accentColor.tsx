import { createContext, useContext, useState, type ReactNode } from 'react';

// "Theme color" — a persisted accent-color override layered on top of dark/light mode. Applied
// as inline custom properties on <html> so it wins over the --brand/--brand-bright/--brand-deep
// values index.css sets per [data-theme="dark"|"light"] block, without needing a color-specific
// CSS rule for every one of the (theme x accent) combinations.

export type AccentColor = 'red' | 'purple' | 'blue' | 'green' | 'pink';

interface AccentSwatch {
  label: string;
  brand: string;
  brandBright: string;
  brandDeep: string;
}

export const ACCENT_SWATCHES: Record<AccentColor, AccentSwatch> = {
  // Matches the app's shipped red/crimson branding — the default, so picking nothing changes
  // nothing about how the app looks today. (Previously mislabeled "Pink" — it's the same red
  // hex as the shipped brand color, so it's named for what it actually looks like.)
  red:    { label: 'Red',    brand: '#B11116', brandBright: '#E4373D', brandDeep: '#7A0C10' },
  purple: { label: 'Purple', brand: '#6D28D9', brandBright: '#9F67F5', brandDeep: '#4C1D95' },
  blue:   { label: 'Blue',   brand: '#1D4ED8', brandBright: '#3B82F6', brandDeep: '#1E3A8A' },
  green:  { label: 'Green',  brand: '#15803D', brandBright: '#22C55E', brandDeep: '#14532D' },
  // A genuine pink/magenta, distinct from the red above.
  pink:   { label: 'Pink',   brand: '#BE185D', brandBright: '#EC4899', brandDeep: '#831843' },
};

const ACCENT_STORAGE_KEY = 'onehr.accentColor';

function isAccentColor(v: string | null): v is AccentColor {
  return !!v && v in ACCENT_SWATCHES;
}

function readStoredAccent(): AccentColor {
  try {
    const v = window.localStorage.getItem(ACCENT_STORAGE_KEY);
    return isAccentColor(v) ? v : 'red';
  } catch {
    return 'red';
  }
}

function hexToRgb(hex: string): string {
  const n = parseInt(hex.slice(1), 16);
  return `${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255}`;
}

function applyAccent(accent: AccentColor) {
  const swatch = ACCENT_SWATCHES[accent];
  const root = document.documentElement.style;
  root.setProperty('--brand', swatch.brand);
  root.setProperty('--brand-bright', swatch.brandBright);
  root.setProperty('--brand-deep', swatch.brandDeep);
  // BrandMark's logo ring/glow (index.css --bm-ring/--bm-glow) are plain rgba() literals, not
  // var(--brand)-based, so they need their own override here to follow the chosen accent too.
  const rgb = hexToRgb(swatch.brandBright);
  root.setProperty('--bm-ring', `rgba(${rgb}, .25)`);
  root.setProperty('--bm-glow', `radial-gradient(circle, rgba(${rgb}, .18) 0%, rgba(${rgb}, 0) 70%)`);
}

let _accent: AccentColor = readStoredAccent();
applyAccent(_accent);

interface AccentContextValue {
  accent: AccentColor;
  setAccent: (accent: AccentColor) => void;
}

const AccentContext = createContext<AccentContextValue>({
  accent: 'red',
  setAccent: () => {},
});

export function AccentColorProvider({ children }: { children: ReactNode }) {
  const [accent, setAccentState] = useState<AccentColor>(_accent);

  function setAccent(next: AccentColor) {
    _accent = next;
    try { window.localStorage.setItem(ACCENT_STORAGE_KEY, next); } catch { /* best effort */ }
    applyAccent(next);
    setAccentState(next);
  }

  return (
    <AccentContext.Provider value={{ accent, setAccent }}>
      {children}
    </AccentContext.Provider>
  );
}

export function useAccentColor(): AccentContextValue {
  return useContext(AccentContext);
}
