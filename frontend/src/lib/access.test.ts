import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import type { AccessCatalogue, MyPermissionsResponse } from '../api/me';
import { accessReady, canAccessPath, matchRoute, navLabel, visibleNavItems } from './access';

/**
 * The route table is read from the backend's real permission-catalogue.yml (the same file the server
 * serves through /api/me/access-catalogue), so the nav and route guard are tested against the actual
 * definition, not a hand-copied fixture that could drift.
 */
function loadCatalogue(): AccessCatalogue {
  const yml = readFileSync(resolve(__dirname, '../../../backend/src/main/resources/permission-catalogue.yml'), 'utf8');
  const routes = [...yml.matchAll(/- path: (\S+)\s*\n\s*requires: \[([^\]]*)\]/g)]
    .map((m) => ({ path: m[1], requires: m[2].split(',').map((x) => x.trim()).filter(Boolean) }));
  return { routes };
}
const catalogue = loadCatalogue();

type Grants = MyPermissionsResponse['permissions'];
const perms = (permissions: Grants): MyPermissionsResponse => ({ primaryRole: 'X', roles: [], permissions });

// Un-cascaded grants exactly as GET /api/me/permissions returns them, for the built-in roles' seeded defaults
// (only the modules that decide a page).
const MANAGER: Grants = {
  attendance: { VIEW: ['TEAM'], EDIT: ['TEAM'] },
  regularization: { VIEW: ['TEAM'], APPROVE: ['TEAM'] },
  attendance_requests: { VIEW: ['TEAM'], APPROVE: ['TEAM'] },
  overtime: { VIEW: ['TEAM'], APPROVE: ['TEAM'] },
  leave: { VIEW: ['TEAM'], APPROVE: ['TEAM'] },
  expenses: { VIEW: ['TEAM'], APPROVE: ['TEAM'] },
  assets: { APPROVE: ['TEAM'] },
  exceptions: { VIEW: ['TEAM'] },
  reports: { VIEW: ['TEAM'], EXPORT: ['TEAM'] },
  bench: { VIEW: ['TEAM'] },
  audit: { VIEW: ['OWN'], EXPORT: ['OWN'] },
  employees: { VIEW: ['TEAM'] },
  hr_help_content: { APPROVE: ['TEAM'] },
};
const HR: Grants = {
  attendance: { VIEW: ['ALL'], EDIT: ['ALL'] },
  regularization: { VIEW: ['ALL'], APPROVE: ['ALL'] },
  attendance_requests: { VIEW: ['ALL'], APPROVE: ['ALL'] },
  overtime: { VIEW: ['ALL'], APPROVE: ['ALL'] },
  leave: { VIEW: ['ALL'], APPROVE: ['ALL'], CONFIGURE: ['ALL'] },
  expenses: { VIEW: ['ALL'], APPROVE: ['ALL'], CONFIGURE: ['ALL'] },
  assets: { VIEW: ['ALL'], CREATE: ['ALL'], EDIT: ['ALL'], APPROVE: ['ALL'] },
  exceptions: { VIEW: ['ALL'] },
  reports: { VIEW: ['ALL'], EXPORT: ['ALL'] },
  bench: { VIEW: ['ALL'], CREATE: ['ALL'], EDIT: ['ALL'], CONFIGURE: ['ALL'] },
  audit: { VIEW: ['OWN'], EXPORT: ['OWN'] },
  employees: { VIEW: ['ALL'], EDIT: ['ALL'] },
  onboarding: { VIEW: ['ALL'], CREATE: ['ALL'], EDIT: ['ALL'], APPROVE: ['ALL'] },
  documents: { VIEW: ['ALL'], APPROVE: ['ALL'], EXPORT: ['ALL'], CONFIGURE: ['ALL'] },
  policies: { VIEW: ['ALL'], CREATE: ['ALL'], EDIT: ['ALL'], DELETE: ['ALL'] },
  organization: { CREATE: ['ALL'], EDIT: ['ALL'], DELETE: ['ALL'] },
  project_allocations: { VIEW: ['ALL'], CREATE: ['ALL'] },
  talent_skills: { VIEW: ['ALL'], CREATE: ['ALL'], APPROVE: ['ALL'], EXPORT: ['ALL'], CONFIGURE: ['ALL'] },
  helpdesk: { VIEW: ['ALL'], CREATE: ['ALL'], EDIT: ['ALL'] },
  hr_help_content: { VIEW: ['ALL'], APPROVE: ['TEAM'] },
};
const SA: Grants = {
  ...HR,
  regularization: { VIEW: ['ALL'], APPROVE: ['ALL'], EXPORT: ['ALL'], CONFIGURE: ['ALL'] },
  user_management: { VIEW: ['ALL'], CREATE: ['ALL'], EDIT: ['ALL'], DELETE: ['ALL'] },
  roles: { VIEW: ['ALL'], CREATE: ['ALL'], EDIT: ['ALL'], DELETE: ['ALL'], CONFIGURE: ['ALL'] },
  user_roles: { VIEW: ['ALL'], EDIT: ['ALL'] },
  approval_rules: { VIEW: ['ALL'] },
  ai_assistant: { VIEW: ['ALL'], CONFIGURE: ['ALL'] },
  audit_security: { VIEW: ['ALL'] },
};

const BASELINE = ['dashboard', 'my-team', 'directory', 'hierarchy', 'attendance', 'leave', 'requests', 'assets',
  'my-skills', 'bench-opportunities', 'my-documents', 'help'];
const keys = (p: MyPermissionsResponse) => visibleNavItems(p, catalogue).map((i) => i.key).sort();

describe('sidebar is decided by permissions alone', () => {
  it('a person with no extra role gets exactly the baseline self-service modules', () => {
    expect(keys(perms({}))).toEqual([...BASELINE].sort());
  });

  it('Manager defaults add team modules and nothing administrative', () => {
    expect(keys(perms(MANAGER))).toEqual([...BASELINE, 'approvals', 'exceptions', 'reports', 'audit', 'bench'].sort());
  });

  it('HR Admin defaults match the old HR menu (plus the baseline items every user has)', () => {
    expect(keys(perms(HR))).toEqual([...BASELINE, 'approvals', 'exceptions', 'reports', 'audit', 'bench', 'employees',
      'onboarding', 'documents', 'policies', 'organization', 'allocations', 'talent-performance'].sort());
  });

  it('Super Admin defaults add the administration modules', () => {
    const k = keys(perms(SA));
    for (const admin of ['access', 'roles', 'workflows', 'api-usage', 'talent-performance', 'organization', 'policies']) {
      expect(k).toContain(admin);
    }
  });

  it('a custom approver role (no Manager role) reaches the Approval Center and nothing else administrative', () => {
    const k = keys(perms({ leave: { APPROVE: ['TEAM'] } }));
    expect(k).toContain('approvals');
    for (const other of ['exceptions', 'reports', 'roles', 'access', 'employees', 'bench']) expect(k).not.toContain(other);
  });

  it('a custom role that only manages roles gets Roles & Permissions, not User Management', () => {
    const k = keys(perms({ roles: { VIEW: ['ALL'], CONFIGURE: ['ALL'] } }));
    expect(k).toContain('roles');
    expect(k).not.toContain('access');
    expect(k).toContain('dashboard'); // baseline is independent of any role
  });

  it('does not list /masters (same page as /organization) or Phase 2 placeholders', () => {
    const k = keys(perms(SA));
    expect(k).not.toContain('masters');
    expect(k).not.toContain('integrations');
    expect(k).not.toContain('featurelab');
  });

  it('a wider scope satisfies a narrower requirement (ALL covers TEAM)', () => {
    expect(keys(perms({ exceptions: { VIEW: ['ALL'] } }))).toContain('exceptions');
  });
});

describe('route guard', () => {
  it('denies a direct URL to a restricted page without the permission, allows baseline pages', () => {
    const employee = perms({});
    expect(canAccessPath('/roles', employee, catalogue)).toBe(false);
    expect(canAccessPath('/access', employee, catalogue)).toBe(false);
    expect(canAccessPath('/organization', employee, catalogue)).toBe(false);
    expect(canAccessPath('/attendance', employee, catalogue)).toBe(true);
    expect(canAccessPath('/profile/123', employee, catalogue)).toBe(true);
    expect(canAccessPath('/roles', perms(SA), catalogue)).toBe(true);
  });

  it("'/bench' does not claim '/bench-opportunities'", () => {
    expect(matchRoute('/bench-opportunities', catalogue)).toBeUndefined();
    expect(matchRoute('/bench/anything', catalogue)?.path).toBe('/bench');
    expect(canAccessPath('/bench-opportunities', perms({}), catalogue)).toBe(true);
    expect(canAccessPath('/bench', perms({}), catalogue)).toBe(false);
  });

  it('guards the audit-trail regularization list by its own permission', () => {
    const none = perms({ regularization: { VIEW: ['ALL'], APPROVE: ['ALL'] } });
    expect(canAccessPath('/attendance/regularization/all', none, catalogue)).toBe(false);
    expect(canAccessPath('/attendance/regularization/all', perms(SA), catalogue)).toBe(true);
    expect(canAccessPath('/attendance', none, catalogue)).toBe(true);
  });

  it('fails closed for a listed page until permissions load, and reports not-ready', () => {
    expect(canAccessPath('/roles', null, catalogue)).toBe(false);
    expect(accessReady(null, catalogue)).toBe(false);
    expect(accessReady(perms({}), null)).toBe(false);
    expect(accessReady(perms({}), catalogue)).toBe(true);
  });
});

describe('sidebar labels follow what the user can do, not which role they hold', () => {
  it('attendance / leave / requests / audit variants', () => {
    expect(navLabel('attendance', 'x', perms({}))).toBe('My Attendance');
    expect(navLabel('attendance', 'x', perms(MANAGER))).toBe('Team Attendance');
    expect(navLabel('attendance', 'x', perms(HR))).toBe('Attendance Administration');
    expect(navLabel('requests', 'x', perms({}))).toBe('My Requests');
    expect(navLabel('requests', 'x', perms(HR))).toBe('HR Service Requests');
    expect(navLabel('audit', 'x', perms(HR))).toBe('Audit History');
    expect(navLabel('audit', 'x', perms(SA))).toBe('Audit & Security');
    expect(navLabel('assets', 'x', perms(MANAGER))).toBe('Team Assets & Expenses');
    expect(navLabel('assets', 'x', perms(HR))).toBe('Assets & Expenses');
  });
});
