/**
 * Keeps every user on the latest deployed build without a hard refresh.
 *
 * Each build gets an id (compiled into the bundle as __BUILD_ID__ and written to /version.json).
 * The app compares the two: when the server's id differs, a newer version has been deployed and
 * the page does a full load, which pulls the fresh index.html and bundle. Checked on start, when
 * the tab becomes visible again, on a timer, and right after login. Never runs in dev, and the
 * reload is guarded so it can never loop.
 */
const BUILD_ID: string = typeof __BUILD_ID__ !== 'undefined' ? __BUILD_ID__ : 'dev';
const GUARD_KEY = 'nf-version-reload';
const CHECK_EVERY_MS = 5 * 60 * 1000;

export function currentBuildId(): string {
  return BUILD_ID;
}

/** True when the server is serving a different build than the one running in this tab. */
export async function isNewVersionAvailable(): Promise<boolean> {
  if (BUILD_ID === 'dev') return false;
  try {
    const res = await fetch(`/version.json?t=${Date.now()}`, { cache: 'no-store' });
    if (!res.ok) return false;
    const body = (await res.json()) as { id?: unknown };
    return typeof body.id === 'string' && body.id !== '' && body.id !== BUILD_ID;
  } catch {
    // Offline, blocked, or the file is not JSON: never disturb the user over it.
    return false;
  }
}

/** Loads the latest build at `target` (default: the current URL). One attempt per build, so no loops. Returns whether it started a load. */
export function loadLatest(target?: string): boolean {
  try {
    const last = sessionStorage.getItem(GUARD_KEY);
    if (last === BUILD_ID) return false;
    sessionStorage.setItem(GUARD_KEY, BUILD_ID);
  } catch { /* storage unavailable: still reload once below */ }
  if (target) window.location.assign(target);
  else window.location.reload();
  return true;
}

/** Check now; load the latest build if one exists. Resolves true only when a page load was started. */
export async function refreshIfStale(target?: string): Promise<boolean> {
  if (!(await isNewVersionAvailable())) return false;
  return loadLatest(target);
}

export function installVersionCheck(): void {
  if (BUILD_ID === 'dev') return;
  void refreshIfStale();
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') void refreshIfStale();
  });
  window.addEventListener('online', () => { void refreshIfStale(); });
  window.setInterval(() => { void refreshIfStale(); }, CHECK_EVERY_MS);
  // A lazily loaded chunk that no longer exists means this tab is on an old build.
  window.addEventListener('vite:preloadError', (e) => {
    e.preventDefault();
    loadLatest();
  });
}
