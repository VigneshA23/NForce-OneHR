import { NAV, isNavItemDisabled, toShellRole } from '../nav.config';
import type { Role } from '../nav.config';

/**
 * Turns a `pageId` from the assistant into a route this user can actually open.
 *
 * The server has already authorised the pageId against its own registry before returning it, so
 * this is defence in depth rather than the primary control. It is worth having anyway, for a
 * specific reason: `Shell.tsx` resolves the active nav item with
 * `navItems.find(n => path.startsWith(n.path)) ?? navItems[0]`, so a route a role has no nav item
 * for falls back to `dashboard` — which is enabled — and the real page still renders. The frontend
 * is not a safety net for navigation, which is exactly why the one place that generates navigation
 * from model output should check rather than assume.
 *
 * Resolution is deliberately driven by `NAV[role]` rather than by a hand-written route table, so a
 * nav item that is renamed, moved between roles or locked is reflected here the moment it changes
 * in one file.
 */
export interface PageTarget {
  route: string;
  /** The label the sidebar uses for this role — not the label the model produced. */
  label: string;
}

/**
 * Pages every signed-in user can reach that are not in anyone's sidebar.
 *
 * These are the only routes resolvable outside `NAV`, and the list is closed: a pageId that is
 * neither a nav key nor one of these resolves to nothing at all. Both are reached today through
 * the topbar (the avatar menu and the notifications bell) rather than the nav, and both are
 * genuinely per-user, so there is no role to check.
 *
 * Kept in step with `pages/registry.yaml`, which is the server-side list — if an entry is added
 * there without a match here, the assistant will simply decline to offer it.
 */
const NON_NAV_TARGETS: Record<string, PageTarget> = {
  profile: { route: '/profile', label: 'My Profile' },
  notifications: { route: '/notifications', label: 'Notifications' },
};

/**
 * @param pageId  a nav key, or one of NON_NAV_TARGETS
 * @param dbRole  the raw role on the auth store (`SUPER_ADMIN`, `HR_ADMIN`, …)
 * @returns the target, or null when this user has no way to get there
 */
export function resolvePageTarget(pageId: string | null | undefined, dbRole: string | undefined): PageTarget | null {
  if (!pageId) return null;
  const key = pageId.trim();
  if (!key) return null;

  const role: Role = toShellRole(dbRole);
  const item = NAV[role].find((n) => n.key === key);

  if (item) {
    // A Phase 2 or locked item renders a roadmap placeholder, not the module. Offering to "take
    // you there" would land the user on a page telling them the feature does not exist yet, which
    // reads as the assistant being wrong rather than the feature being unbuilt.
    return isNavItemDisabled(item) ? null : { route: item.path, label: item.label };
  }

  return NON_NAV_TARGETS[key] ?? null;
}

/** Whether a navigation action can be offered at all, without building the target. */
export function canNavigateTo(pageId: string | null | undefined, dbRole: string | undefined): boolean {
  return resolvePageTarget(pageId, dbRole) !== null;
}
