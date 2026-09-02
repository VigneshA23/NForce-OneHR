import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import './index.css';
import App from './App.tsx';
import { ThemeProvider } from './lib/theme';
import { installAuthFetch } from './lib/authFetch';

installAuthFetch();

// Drives a `--app-height` custom property from the live `visualViewport` size, as a more
// reliable stand-in for `100dvh` on iOS Safari. `dvh` is *supposed* to dynamically track the
// viewport as the dynamic bottom toolbar shrinks/expands, but WebKit has known bugs where a
// `position: fixed` element's dvh-based height gets stuck at whatever it measured when the
// element was inserted (e.g. opening this app's mobile nav drawer right after a tap forces the
// toolbar back to its expanded/full state) and never re-measures larger again even once the
// toolbar later re-collapses on scroll — leaving a permanent gap of unfilled, empty space at
// the bottom for the rest of the session. `visualViewport`'s own resize event is what the
// toolbar's show/hide is built on, so it fires reliably on every such transition; every place
// that used bare `100dvh` for a structural height now reads `var(--app-height, 100dvh)`
// instead, so it re-measures itself on any real viewport change (`100dvh` remains as the
// fallback for the brief window before this script runs, and for browsers without
// `visualViewport`).
function setAppHeight() {
  const height = window.visualViewport?.height ?? window.innerHeight;
  document.documentElement.style.setProperty('--app-height', `${height}px`);
}
setAppHeight();
window.visualViewport?.addEventListener('resize', setAppHeight);
window.addEventListener('resize', setAppHeight);
window.addEventListener('orientationchange', setAppHeight);

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <ThemeProvider>
      <App />
    </ThemeProvider>
  </StrictMode>
);
