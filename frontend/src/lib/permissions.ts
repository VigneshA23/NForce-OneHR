import type { MyPermissionsResponse } from '../api/me';
import { ALL_NAV_ITEMS, isNavItemDisabled, type NavItem } from './nav.config';

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
 * Nav items with no entry here are self-service / unrestricted — every role that already lists
 * them in nav.config.ts's NAV map keeps seeing them unconditionally, exactly as today (matches
 * V225's seed-matrix design: self-service endpoints have no role_permissions row at all). Only
 * items that correspond to an actual permission-gated admin feature are listed, mapped to the
 * same module/action/scope Phase 3 assigned that feature's backend endpoints.
 */
const NAV_ITEM_REQUIREMENTS: Partial<Record<string, PermissionRequirement[]>> = {
  approvals: [
    { module: 'regularization', action: 'APPROVE', scope: 'TEAM' },
    { module: 'attendance_requests', action: 'APPROVE', scope: 'TEAM' },
    { module: 'overtime', action: 'APPROVE', scope: 'TEAM' },
    { module: 'leave', action: 'APPROVE', scope: 'TEAM' },
    { module: 'expenses', action: 'APPROVE', scope: 'TEAM' },
  ],
  exceptions: [{ module: 'exceptions', action: 'VIEW', scope: 'TEAM' }],
  employees: [{ module: 'employees', action: 'VIEW', scope: 'ALL' }],
  onboarding: [{ module: 'onboarding', action: 'VIEW', scope: 'ALL' }],
  documents: [{ module: 'documents', action: 'VIEW', scope: 'ALL' }],
  policies: [{ module: 'policies', action: 'VIEW', scope: 'ALL' }],
  organization: [{ module: 'organization', action: 'EDIT', scope: 'ALL' }],
  masters: [{ module: 'organization', action: 'EDIT', scope: 'ALL' }],
  'talent-performance': [{ module: 'talent_skills', action: 'VIEW', scope: 'ALL' }],
  reports: [{ module: 'reports', action: 'VIEW', scope: 'TEAM' }],
  audit: [{ module: 'audit', action: 'VIEW', scope: 'OWN' }],
  access: [{ module: 'user_management', action: 'VIEW', scope: 'ALL' }],
  roles: [{ module: 'roles', action: 'VIEW', scope: 'ALL' }],
  bench: [{ module: 'bench', action: 'VIEW', scope: 'TEAM' }],
  allocations: [{ module: 'project_allocations', action: 'CREATE', scope: 'ALL' }],
  workflows: [{ module: 'approval_rules', action: 'VIEW', scope: 'ALL' }],
  'api-usage': [{ module: 'ai_assistant', action: 'VIEW', scope: 'ALL' }],
};

/** Whether this nav item should be visible to the current user. Undefined/missing permissions
 *  (not yet fetched) fails closed for gated items — never shows an admin module before the real
 *  check lands, same as a brand-new page load would see from the server anyway. */
export function isNavItemVisible(item: NavItem, permissions: MyPermissionsResponse | null | undefined): boolean {
  const requirements = NAV_ITEM_REQUIREMENTS[item.key];
  if (!requirements) return true;
  return hasAnyPermission(permissions, requirements);
}

/**
 * Gated nav items that this user's CUSTOM roles grant but their primary role's curated NAV doesn't
 * list. The sidebar is still chosen per primary (system) role, so without this a custom role's
 * admin grants (e.g. User Management) would never be reachable from the menu. Only custom-role
 * grants count (customRolePermissions), so no system role's curated nav changes shape. Items with
 * identical requirements (organization / masters → the same page) are only added once.
 */
export function customRoleNavItems(roleItems: NavItem[], permissions: MyPermissionsResponse | null | undefined): NavItem[] {
  const custom = permissions?.customRolePermissions;
  if (!custom || Object.keys(custom).length === 0) return [];
  const customOnly = { ...permissions!, permissions: custom };
  const have = new Set(roleItems.map((i) => i.key));
  const seen = new Set(roleItems.filter((i) => NAV_ITEM_REQUIREMENTS[i.key]).map((i) => JSON.stringify(NAV_ITEM_REQUIREMENTS[i.key])));
  return ALL_NAV_ITEMS.filter((i) => {
    const req = NAV_ITEM_REQUIREMENTS[i.key];
    if (!req || have.has(i.key) || isNavItemDisabled(i) || !hasAnyPermission(customOnly, req)) return false;
    const sig = JSON.stringify(req);
    if (seen.has(sig)) return false;
    seen.add(sig);
    return true;
  });
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
