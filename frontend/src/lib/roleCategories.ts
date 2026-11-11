/** A role list a person belongs to (mirrors the backend's RoleCategorization.Category). */
export interface RoleCategory { code: string; name: string }

export const BUILT_IN_ORDER = ['SUPER_ADMIN', 'HR_ADMIN', 'MANAGER', 'EMPLOYEE'];

/**
 * The role list(s) a user belongs to — assigned roles only; Employee is just the default for people
 * with none (same rule as the backend's RoleCategorization). Falls back to the primary role for
 * responses that predate `categories`. This is for grouping/filtering by role only: the Employee
 * Master still lists everyone.
 */
export function categoriesOf(u: { role?: string; categories?: RoleCategory[] }): RoleCategory[] {
  if (u.categories && u.categories.length > 0) return u.categories;
  const code = u.role || 'EMPLOYEE';
  return [{ code, name: code === 'EMPLOYEE' ? 'Employee' : code.replace(/_/g, ' ') }];
}

/** Distinct role lists across users, built-ins first (Super Admin, HR Admin, Manager, Employee), then custom by name. */
export function roleOptionsOf(users: { role?: string; categories?: RoleCategory[] }[]): RoleCategory[] {
  const byCode = new Map<string, string>();
  users.forEach(u => categoriesOf(u).forEach(c => { if (!byCode.has(c.code)) byCode.set(c.code, c.name); }));
  return Array.from(byCode, ([code, name]) => ({ code, name })).sort((a, b) => {
    const ia = BUILT_IN_ORDER.indexOf(a.code), ib = BUILT_IN_ORDER.indexOf(b.code);
    return (ia < 0 ? 99 : ia) - (ib < 0 ? 99 : ib) || a.name.localeCompare(b.name);
  });
}

/** How many people fall under each role list; someone with several roles counts under each. */
export function roleCountsOf(users: { role?: string; categories?: RoleCategory[] }[]): Record<string, number> {
  const counts: Record<string, number> = {};
  users.forEach(u => categoriesOf(u).forEach(c => { counts[c.code] = (counts[c.code] ?? 0) + 1; }));
  return counts;
}

/** True when the person belongs only to the default Employee category (no administrative or custom role). */
export function isDefaultEmployee(u: { role?: string; categories?: RoleCategory[] }): boolean {
  const cats = categoriesOf(u);
  return cats.length === 1 && cats[0].code === 'EMPLOYEE';
}
