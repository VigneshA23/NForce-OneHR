import type { MyPermissionsResponse } from '../api/me';

/** Scopes a granted scope also satisfies — mirrors the backend's ScopeCoverage. GET
 *  /api/me/permissions returns the UN-cascaded grants (a role holding ALL lists only "ALL"), so
 *  the cascade has to happen here: a Super Admin / HR Admin with approve:ALL must still satisfy a
 *  TEAM-level requirement. */
const SCOPE_COVERAGE: Record<string, string[]> = {
  OWN: ['OWN'],
  TEAM: ['OWN', 'TEAM'],
  DEPARTMENT: ['OWN', 'TEAM', 'DEPARTMENT'],
  LOCATION: ['OWN', 'TEAM', 'LOCATION'],
  ALL: ['OWN', 'TEAM', 'DEPARTMENT', 'LOCATION', 'ALL'],
};

/** True if the user's permission matrix grants `module:action` at (at least) `scope`. The
 *  backend returns only the scopes actually granted (not cascaded), so a wider grant (e.g. ALL)
 *  is checked via SCOPE_COVERAGE. Defaults to the narrowest scope (OWN) when the caller doesn't
 *  care which level, matching the backend's own convention. */
export function hasPermission(
  permissions: MyPermissionsResponse | null | undefined,
  module: string,
  action: string,
  scope: string = 'OWN'
): boolean {
  const granted = permissions?.permissions?.[module]?.[action];
  if (!granted) return false;
  return granted.some((g) => (SCOPE_COVERAGE[g] ?? [g]).includes(scope));
}

/** The org-wide workforce views (HR dashboard, Reports) fetch /org-dashboard, /attendance/day and
 *  /leave/organization together, so they need every one of those endpoints' permissions — all
 *  three seeded to exactly HR_ADMIN + SUPER_ADMIN (V225), the role pair these views used to check. */
export function hasOrgWorkforceView(permissions: MyPermissionsResponse | null | undefined): boolean {
  return hasPermission(permissions, 'employees', 'VIEW', 'ALL')
    && hasPermission(permissions, 'attendance', 'VIEW', 'ALL')
    && hasPermission(permissions, 'leave', 'VIEW', 'ALL');
}

export interface PermissionRequirement {
  module: string;
  action: string;
  scope?: string;
}

/** True if ANY of the given requirements is satisfied — for a feature reachable via more than
 *  one underlying permission (e.g. "Approval Center" aggregates several modules' APPROVE queues). */
export function hasAnyPermission(
  permissions: MyPermissionsResponse | null | undefined,
  requirements: PermissionRequirement[]
): boolean {
  return requirements.some((r) => hasPermission(permissions, r.module, r.action, r.scope));
}

/**
 * Which Assets & Expenses view a user gets (page + My Profile's Assets tab) — from the permissions
 * the views' endpoints require (V236/V242), each seeded to exactly the roles that used to pick it:
 * HR view = assets:VIEW:ALL or expenses:VIEW:ALL (HR Admin/Super Admin); Manager view =
 * assets:APPROVE:TEAM or expenses:APPROVE:TEAM (Manager; HR/SA already took the HR view). Before
 * permissions have loaded it falls back to the primary role so the page doesn't flash the wrong view.
 */
export function assetsExpensesView(permissions: MyPermissionsResponse | null | undefined, primaryRole: string | undefined): 'hr' | 'manager' | 'employee' {
  if (!permissions) {
    return primaryRole === 'HR_ADMIN' || primaryRole === 'SUPER_ADMIN' ? 'hr' : primaryRole === 'MANAGER' ? 'manager' : 'employee';
  }
  if (hasAnyPermission(permissions, [{ module: 'assets', action: 'VIEW', scope: 'ALL' }, { module: 'expenses', action: 'VIEW', scope: 'ALL' }])) return 'hr';
  if (hasAnyPermission(permissions, [{ module: 'assets', action: 'APPROVE', scope: 'TEAM' }, { module: 'expenses', action: 'APPROVE', scope: 'TEAM' }])) return 'manager';
  return 'employee';
}
