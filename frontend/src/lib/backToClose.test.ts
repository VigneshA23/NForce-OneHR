import { describe, expect, it, vi } from 'vitest';
import { attachBackToClose, type HistoryWindow } from './backToClose';

/** A tiny fake history stack + popstate, enough to model Back presses. */
function fakeWindow(initial: unknown = { idx: 3 }) {
  const stack: unknown[] = [initial];
  let pos = 0;
  const listeners = new Set<() => void>();
  const timers: (() => void)[] = [];
  const win: HistoryWindow = {
    history: {
      get state() { return stack[pos]; },
      pushState(state) { stack.splice(pos + 1); stack.push(state); pos++; },
      replaceState(state) { stack[pos] = state; },
      back() { if (pos > 0) { pos--; listeners.forEach((l) => l()); } },
    },
    addEventListener: (_t, fn) => { listeners.add(fn); },
    removeEventListener: (_t, fn) => { listeners.delete(fn); },
    setTimeout: (fn) => { timers.push(fn); return 0; },
  };
  return { win, stack: () => stack.slice(0, pos + 1), flush: () => timers.splice(0).forEach((t) => t()) };
}

describe('attachBackToClose', () => {
  it('Back runs onBack and keeps the user on the page', () => {
    const { win, stack } = fakeWindow();
    const onBack = vi.fn();
    attachBackToClose(win, 'a', onBack, () => true);
    expect(stack()).toHaveLength(2);

    win.history.back();

    expect(onBack).toHaveBeenCalledTimes(1);
    expect(stack()).toHaveLength(2); // re-pushed: a second Back is intercepted too
    expect((win.history.state as { idx: number }).idx).toBe(3); // router's own fields preserved
  });

  it('closing normally pops the extra entry, restoring history', () => {
    const { win, stack, flush } = fakeWindow();
    let mounted = true;
    const detach = attachBackToClose(win, 'a', vi.fn(), () => mounted);
    mounted = false;
    detach();
    flush();
    expect(stack()).toEqual([{ idx: 3 }]);
  });

  it('StrictMode remount reuses the entry instead of stacking or popping it', () => {
    const { win, stack, flush } = fakeWindow();
    let mounted = 1;
    const detach1 = attachBackToClose(win, 'a', vi.fn(), () => mounted > 0);
    mounted--; detach1();
    mounted++; attachBackToClose(win, 'a', vi.fn(), () => mounted > 0);
    flush();
    expect(stack()).toHaveLength(2);
  });

  it('a modal opening as another closes takes over its entry, so history still unwinds fully', () => {
    const { win, stack, flush } = fakeWindow();
    let editorMounted = true;
    const detachEditor = attachBackToClose(win, 'editor', vi.fn(), () => editorMounted);
    editorMounted = false; detachEditor();
    let drawerMounted = true;
    const detachDrawer = attachBackToClose(win, 'drawer', vi.fn(), () => drawerMounted);
    flush();
    expect(stack()).toHaveLength(2);
    drawerMounted = false; detachDrawer(); flush();
    expect(stack()).toEqual([{ idx: 3 }]);
  });
});
