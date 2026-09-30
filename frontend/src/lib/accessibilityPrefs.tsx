import { createContext, useContext, useState, type ReactNode } from 'react';

// All four preferences here are wired up for real and apply app-wide — unlike most of
// UserPreferencesPage's other fields, which are persisted placeholders only (see that file's
// top-of-file note). Applied as data-attributes (or, for font size, a direct style) on <html> so
// a plain CSS rule in index.css can react to them from anywhere, the same pattern as theme.tsx's
// data-theme.

export type FontSize = 'Small' | 'Default' | 'Large';
const FONT_ZOOM: Record<FontSize, string> = { Small: '90%', Default: '100%', Large: '115%' };

const STORAGE_KEY = 'onehr.a11y';

interface A11yState {
  reduceAnimations: boolean;
  keyboardNavFocus: boolean;
  highContrast: boolean;
  fontSize: FontSize;
}

const DEFAULTS: A11yState = {
  reduceAnimations: false,
  keyboardNavFocus: false,
  highContrast: false,
  fontSize: 'Default',
};

function readStored(): A11yState {
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    if (!raw) return DEFAULTS;
    const parsed = JSON.parse(raw);
    return {
      reduceAnimations: !!parsed.reduceAnimations,
      keyboardNavFocus: !!parsed.keyboardNavFocus,
      highContrast: !!parsed.highContrast,
      fontSize: parsed.fontSize === 'Small' || parsed.fontSize === 'Large' ? parsed.fontSize : 'Default',
    };
  } catch {
    return DEFAULTS;
  }
}

function applyToDom(state: A11yState) {
  const html = document.documentElement;
  html.setAttribute('data-motion', state.reduceAnimations ? 'reduce' : 'auto');
  html.setAttribute('data-kbd-nav', state.keyboardNavFocus ? 'on' : 'off');
  html.setAttribute('data-contrast', state.highContrast ? 'high' : 'normal');
  // `zoom` (not a CSS standard, but supported by every Chromium/WebKit browser) is used here
  // rather than a root font-size percentage because most of this app's typography is plain
  // pixel values in inline styles (fontSize: 13, etc.), not rem-based — a root font-size change
  // alone would not reach any of it. zoom rescales the whole rendered page uniformly instead,
  // so it actually reaches every one of those pixel values.
  html.style.zoom = FONT_ZOOM[state.fontSize];
}

let _state: A11yState = readStored();
applyToDom(_state);

interface AccessibilityContextValue extends A11yState {
  setReduceAnimations: (v: boolean) => void;
  setKeyboardNavFocus: (v: boolean) => void;
  setHighContrast: (v: boolean) => void;
  setFontSize: (v: FontSize) => void;
}

const AccessibilityContext = createContext<AccessibilityContextValue>({
  ...DEFAULTS,
  setReduceAnimations: () => {},
  setKeyboardNavFocus: () => {},
  setHighContrast: () => {},
  setFontSize: () => {},
});

export function AccessibilityProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<A11yState>(_state);

  function persist(next: A11yState) {
    _state = next;
    try { window.localStorage.setItem(STORAGE_KEY, JSON.stringify(next)); } catch { /* best effort */ }
    applyToDom(next);
    setState(next);
  }

  return (
    <AccessibilityContext.Provider
      value={{
        ...state,
        setReduceAnimations: (v) => persist({ ...state, reduceAnimations: v }),
        setKeyboardNavFocus: (v) => persist({ ...state, keyboardNavFocus: v }),
        setHighContrast: (v) => persist({ ...state, highContrast: v }),
        setFontSize: (v) => persist({ ...state, fontSize: v }),
      }}
    >
      {children}
    </AccessibilityContext.Provider>
  );
}

export function useAccessibilityPrefs(): AccessibilityContextValue {
  return useContext(AccessibilityContext);
}
