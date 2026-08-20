import type { NotificationItem } from '../api/notifications';

type Listener = (items: NotificationItem[]) => void;

const listeners = new Set<Listener>();

/**
 * Registers a listener for notifications the app-wide poll (Shell's bell — see Shell.tsx)
 * detects as newly arrived since its previous tick. Returns an unsubscribe function; callers
 * must invoke it on unmount so a remounted page doesn't accumulate duplicate listeners.
 *
 * This is intentionally thin — it does not poll or fetch anything itself. Shell already owns
 * the one app-wide notification poll (reused for the bell badge); this module just lets other
 * mounted pages (e.g. LeavePage) react to what that poll finds, without each page running its
 * own separate polling loop.
 */
export function subscribeToNewNotifications(listener: Listener): () => void {
  listeners.add(listener);
  return () => { listeners.delete(listener); };
}

/** Called only by Shell's polling loop when it finds notifications newer than its last tick. */
export function publishNewNotifications(items: NotificationItem[]): void {
  if (items.length === 0) return;
  for (const listener of listeners) listener(items);
}
