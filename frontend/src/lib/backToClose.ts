import { useEffect, useRef } from 'react';

/** The slice of `window` this needs — injectable so the logic is testable without a DOM. */
export interface HistoryWindow {
  history: { state: unknown; pushState(state: unknown, unused: string): void; replaceState(state: unknown, unused: string): void; back(): void };
  addEventListener(type: 'popstate', fn: () => void): void;
  removeEventListener(type: 'popstate', fn: () => void): void;
  setTimeout(fn: () => void, ms: number): unknown;
}

const markerOf = (state: unknown) => (state as { nfModal?: string } | null)?.nfModal;
const isOurs = (state: unknown, id: string) => markerOf(state) === id;
const mark = (state: unknown, id: string) => ({ ...(state as object | null ?? {}), nfModal: id });

/**
 * While a modal is open, the browser Back button acts like its close button — so an unsaved-changes
 * prompt can intercept it — instead of leaving the page underneath. Works by pushing one same-URL
 * history entry (keeping the router's own state fields) and re-pushing it whenever Back pops it;
 * when the modal closes normally, that entry is popped again so history is left as it was.
 * Returns a detach function. `isMounted` lets React StrictMode's mount→cleanup→mount reuse the
 * entry instead of popping and re-pushing it.
 */
export function attachBackToClose(win: HistoryWindow, id: string, onBack: () => void, isMounted: () => boolean): () => void {
  // A marker left by a modal that just closed into this one (e.g. editor → details drawer, same
  // commit) is taken over rather than stacked, so history still unwinds to where it started.
  if (markerOf(win.history.state) === undefined) win.history.pushState(mark(win.history.state, id), '');
  else if (!isOurs(win.history.state, id)) win.history.replaceState(mark(win.history.state, id), '');
  const onPop = () => {
    win.history.pushState(mark(win.history.state, id), '');
    onBack();
  };
  win.addEventListener('popstate', onPop);
  return () => {
    win.removeEventListener('popstate', onPop);
    win.setTimeout(() => { if (!isMounted() && isOurs(win.history.state, id)) win.history.back(); }, 0);
  };
}

/** React wrapper for {@link attachBackToClose}; `onBack` is read fresh on every Back press. */
export function useBackToClose(onBack: () => void) {
  const onBackRef = useRef(onBack);
  useEffect(() => { onBackRef.current = onBack; });
  const id = useRef(`m${Math.random().toString(36).slice(2)}`).current;
  const mounted = useRef(0);
  useEffect(() => {
    mounted.current++;
    const detach = attachBackToClose(window, id, () => onBackRef.current(), () => mounted.current > 0);
    return () => { mounted.current--; detach(); };
  }, [id]);
}
