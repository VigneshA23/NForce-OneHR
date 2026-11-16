import { useCallback } from 'react';
import type { AccessCatalogue, MyPermissionsResponse, RouteRequirement } from '../api/me';
import { useAuthStore } from '../store/authStore';
import { ALL_NAV_ITEMS, isNavItemDisabled, type NavItem } from './nav.config';
import { hasAnyPermission, hasPermission, type PermissionRequirement } from './permissions';

/**
 * The one frontend access layer. Everything the UI shows or hides — sidebar items, route access,
 * tabs, buttons — is decided here from the user's effective permissions and the backend's
 * permission catalogue, never from a role name. A role merely bundles permissions; the backend
 * still enforces every endpoint on its own (this only avoids offering what would be refused).
 */

/** "module:ACTION:SCOPE" -> a requirement object. */
export function parseAuthority(authority: string): PermissionRequirement {
  const [module, action, scope] = authority.split(':');
  return { module, action, scope };
}

/** The catalogue entry for a path: the longest route that is the path itself or a parent segment of it. */
export function matchRoute(pathname: string, catalogue: AccessCatalogue | null | undefined): RouteRequirement | undefined {
  if (!catalogue) return undefined;
  let best: RouteRequirement | undefined;
  for (const r of catalogue.routes) {
    const hit = pathname === r.path || pathname.startsWith(r.path + '/');
    if (hit && (!best || r.path.length > best.path.length)) best = r;
  }
  return best;
}

/**
 * May this user open this page? A path the catalogue does not list is baseline self-service and open
 * to every signed-in user. A listed path needs at least one of its `requires` authorities (a wider
 * scope satisfies a narrower one). Callers must wait until BOTH permissions and the catalogue have
 * loaded (see accessReady) — with either missing this returns false for a listed path.
 */
export function canAccessPath(
  pathname: string,
  permissions: MyPermissionsResponse | null | undefined,
  catalogue: AccessCatalogue | null | undefined,
): boolean {
  const route = matchRoute(pathname, catalogue);
  if (!route) return true;
  return hasAnyPermission(permissions, route.requires.map(parseAuthority));
}

export function accessReady(
  permissions: MyPermissionsResponse | null | undefined,
  catalogue: AccessCatalogue | null | undefined,
): boolean {
  return !!permissions && !!catalogue;
}

/**
 * Sidebar label for a module — the same destination can be a personal view or an administrative one,
 * and the label says which, by what the user can do (not by which role they hold).
 */
export function navLabel(key: string, fallback: string, permissions: MyPermissionsResponse | null | undefined): string {
  const has = (m: string, a: string, s: string) => hasPermission(permissions, m, a, s);
  switch (key) {
    case 'attendance':
      return has('attendance', 'VIEW', 'ALL') ? 'Attendance Administration' : has('attendance', 'VIEW', 'TEAM') ? 'Team Attendance' : 'My Attendance';
    case 'leave':
      return has('leave', 'VIEW', 'ALL') ? 'Leave Administration' : has('leave', 'VIEW', 'TEAM') ? 'Team Leave & Holidays' : 'Leave & Holidays';
    case 'requests':
      return has('helpdesk', 'VIEW', 'ALL') ? 'HR Service Requests' : 'My Requests';
    case 'assets':
      return !has('assets', 'VIEW', 'ALL') && !has('expenses', 'VIEW', 'ALL') && (has('assets', 'APPROVE', 'TEAM') || has('expenses', 'APPROVE', 'TEAM'))
        ? 'Team Assets & Expenses' : 'Assets & Expenses';
    case 'audit':
      return has('audit_security', 'VIEW', 'ALL') ? 'Audit & Security' : 'Audit History';
    case 'bench':
      return has('bench', 'EDIT', 'ALL') ? 'Bench Management' : 'Team Bench';
    case 'organization':
      return 'Organization Structure';
    default:
      return fallback;
  }
}

/** /masters is the same page as /organization; one menu entry is enough. */
const NAV_SKIPPED = new Set(['masters']);

/**
 * Every module the user may open, with its label. One master list for everybody — a custom role, a
 * built-in role and a person with no extra role all go through the same rule, so access never depends
 * on which preset menu a role used to map to.
 */
export function visibleNavItems(
  permissions: MyPermissionsResponse | null | undefined,
  catalogue: AccessCatalogue | null | undefined,
): NavItem[] {
  return ALL_NAV_ITEMS
    .filter((i) => !isNavItemDisabled(i) && !NAV_SKIPPED.has(i.key) && canAccessPath(i.path, permissions, catalogue))
    .map((i) => ({ ...i, label: navLabel(i.key, i.label, permissions) }));
}

export type Can = (module: string, action: string, scope?: string) => boolean;

/**
 * `can('user_management', 'CREATE')` for hiding an action the user could not perform. Scope defaults to
 * ALL: the buttons it guards are organisation-wide operations; pass 'TEAM' / 'OWN' for narrower ones.
 */
export function useCan(): Can {
  const permissions = useAuthStore((s) => s.permissions);
  return useCallback((module, action, scope = 'ALL') => hasPermission(permissions, module, action, scope), [permissions]);
}
