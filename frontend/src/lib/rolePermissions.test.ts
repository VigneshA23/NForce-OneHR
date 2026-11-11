import { describe, expect, it } from 'vitest';
import {
  categoryState, filterCatalogue, grantableScopes, sameGrants, setCategory, setGrant, summarizeGrants, toGrantMap, toGrants,
} from './rolePermissions';
import { isRoutePermitted, permissionNavItems } from './permissions';
import { NAV } from './nav.config';
import type { CatalogueModule } from '../api/roles';
import type { MyPermissionsResponse } from '../api/me';

const catalogue: CatalogueModule[] = [
  { code: 'employees', name: 'Employee Master', configurable: true, actions: ['VIEW', 'CREATE', 'EDIT'] },
  { code: 'roles', name: 'Roles & Permissions', configurable: true, actions: ['VIEW', 'CONFIGURE'] },
  { code: 'leave', name: 'Leave', configurable: false, actions: ['VIEW', 'APPROVE'] },
];

const me = (permissions: MyPermissionsResponse['permissions'], custom?: MyPermissionsResponse['permissions']): MyPermissionsResponse =>
  ({ primaryRole: 'EMPLOYEE', roles: [], permissions, customRolePermissions: custom });

describe('role editor selection', () => {
  it('category state is tri-state over the given actions only', () => {
    const map = toGrantMap([{ module: 'employees', action: 'VIEW', scope: 'ALL' }]);
    expect(categoryState(map, 'employees', ['VIEW', 'CREATE', 'EDIT'])).toBe('some');
    expect(categoryState(map, 'employees', ['VIEW'])).toBe('all');
    expect(categoryState(map, 'roles', ['VIEW', 'CONFIGURE'])).toBe('none');
  });

  it('select-all touches only the visible, grantable actions of that one module', () => {
    const start = toGrantMap([{ module: 'roles', action: 'VIEW', scope: 'ALL' }]);
    const next = setCategory(start, 'employees', ['VIEW', 'EDIT'], true, () => 'ALL');
    expect(next).toEqual({ 'roles:VIEW': 'ALL', 'employees:VIEW': 'ALL', 'employees:EDIT': 'ALL' });
    expect(next['employees:CREATE']).toBeUndefined();
  });

  it('select-all keeps an existing narrower scope and skips actions with no grantable scope', () => {
    const start = toGrantMap([{ module: 'employees', action: 'VIEW', scope: 'TEAM' }]);
    const next = setCategory(start, 'employees', ['VIEW', 'EDIT', 'CREATE'], true, (a) => (a === 'CREATE' ? undefined : 'ALL'));
    expect(next).toEqual({ 'employees:VIEW': 'TEAM', 'employees:EDIT': 'ALL' });
  });

  it('clear-all removes only that module', () => {
    const start = toGrantMap([
      { module: 'employees', action: 'VIEW', scope: 'ALL' },
      { module: 'roles', action: 'VIEW', scope: 'ALL' },
    ]);
    expect(setCategory(start, 'employees', ['VIEW', 'CREATE', 'EDIT'], false, () => 'ALL')).toEqual({ 'roles:VIEW': 'ALL' });
  });

  it('toGrants never sends non-configurable modules', () => {
    const map = { 'employees:VIEW': 'ALL', 'leave:APPROVE': 'TEAM' };
    expect(toGrants(map, catalogue)).toEqual([{ module: 'employees', action: 'VIEW', scope: 'ALL' }]);
  });

  it('dirty tracking compares scopes too', () => {
    const a = { 'employees:VIEW': 'ALL' };
    expect(sameGrants(a, { 'employees:VIEW': 'ALL' })).toBe(true);
    expect(sameGrants(a, setGrant(a, 'employees', 'VIEW', 'TEAM'))).toBe(false);
    expect(sameGrants(a, setGrant(a, 'employees', 'VIEW', null))).toBe(false);
  });

  it('only offers scopes the admin holds (cascade: ALL implies TEAM and OWN)', () => {
    expect(grantableScopes(me({ employees: { VIEW: ['ALL'] } }), 'employees', 'VIEW')).toEqual(['OWN', 'TEAM', 'ALL']);
    expect(grantableScopes(me({ employees: { VIEW: ['TEAM'] } }), 'employees', 'VIEW')).toEqual(['OWN', 'TEAM']);
    expect(grantableScopes(me({}), 'employees', 'EDIT')).toEqual([]);
  });

  it('search matches module names and action labels', () => {
    expect(filterCatalogue(catalogue, 'employee').map((m) => m.code)).toEqual(['employees']);
    expect(filterCatalogue(catalogue, 'approve').map((m) => m.code)).toEqual(['leave']);
    expect(filterCatalogue(catalogue, '  ')).toHaveLength(3);
  });

  it('summarizes grants', () => {
    expect(summarizeGrants([])).toBe('No permissions');
    expect(summarizeGrants([{ module: 'a', action: 'VIEW', scope: 'ALL' }, { module: 'a', action: 'EDIT', scope: 'ALL' }]))
      .toBe('2 permissions · 1 module');
  });
});

describe('permissionNavItems', () => {
  it('surfaces admin items a custom role grants on top of the persona nav', () => {
    const perms = me({ user_management: { VIEW: ['ALL'] }, roles: { VIEW: ['ALL'] } },
                     { user_management: { VIEW: ['ALL'] }, roles: { VIEW: ['ALL'] } });
    expect(permissionNavItems(NAV.Employee, perms, 'Employee').map((i) => i.key)).toEqual(['access', 'roles']);
  });

  it('also surfaces items granted through a system role (an edited system-role grant), not just custom roles', () => {
    const perms = me({ employees: { VIEW: ['ALL'] }, exceptions: { VIEW: ['ALL'] } });
    expect(permissionNavItems(NAV.Employee, perms, 'Employee').map((i) => i.key).sort()).toEqual(['employees', 'exceptions']);
  });

  it('leaves the Super Admin persona exactly as curated', () => {
    const perms = me({ employees: { VIEW: ['ALL'] }, exceptions: { VIEW: ['ALL'] }, roles: { VIEW: ['ALL'] } });
    expect(permissionNavItems(NAV['Super Admin'], perms, 'Super Admin')).toEqual([]);
  });

  it('adds one item per distinct requirement and nothing already in the persona nav', () => {
    const perms = me({ organization: { EDIT: ['ALL'] }, roles: { VIEW: ['ALL'] } });
    // (masters + organization share one requirement; HR already lists organization)
    expect(permissionNavItems(NAV['HR Admin'], perms, 'HR Admin').map((i) => i.key)).toEqual(['roles']);
    expect(permissionNavItems(NAV.Employee, perms, 'Employee').map((i) => i.key).sort()).toEqual(['organization', 'roles']);
  });

  it('adds nothing before permissions have loaded (fails closed)', () => {
    expect(permissionNavItems(NAV.Employee, null, 'Employee')).toEqual([]);
  });
});

describe('isRoutePermitted', () => {
  it('guards the Super Admin audit-trail regularization route by its own permission', () => {
    const none = me({ regularization: { VIEW: ['ALL'], APPROVE: ['ALL'] } });
    const sa = me({ regularization: { VIEW: ['ALL'], EXPORT: ['ALL'] } });
    expect(isRoutePermitted('/attendance/regularization/all', none)).toBe(false);
    expect(isRoutePermitted('/attendance/regularization/all', sa)).toBe(true);
    expect(isRoutePermitted('/attendance', none)).toBe(true);
  });
});
