import { describe, expect, it } from 'vitest';
import type { MyPermissionsResponse } from '../api/me';
import type { CatalogueAction, CatalogueNode } from '../api/roles';
import {
  allNodeCodes, bulkScope, filterTree, flattenCatalogue, leavesOf, nodeCounts, nodeState, pruneToGranted, scopeChoices,
  sensitiveDiff, sensitiveSkipped, setNode,
} from './roleTree';

const act = (action: string, minScope = 'OWN', maxScope = 'ALL'): CatalogueAction => ({ action, minScope, maxScope });
const leaf = (code: string, name: string, actions: CatalogueAction[], extra: Partial<CatalogueNode> = {}): CatalogueNode =>
  ({ code, name, type: 'SECTION', description: '', sensitive: false, configurable: true, actions, children: [], ...extra });
const group = (code: string, name: string, type: CatalogueNode['type'], children: CatalogueNode[]): CatalogueNode =>
  ({ code, name, type, description: '', sensitive: false, configurable: false, actions: [], children });

// Attendance (module) > Attendance Requests (submodule) > Regularization + Overtime (sections); Administration > Roles (HIGH).
const tree: CatalogueNode[] = [
  group('mod.attendance', 'Attendance', 'MODULE', [
    leaf('attendance', 'Team Attendance', [act('VIEW', 'TEAM'), act('EDIT', 'TEAM')], { type: 'SUBMODULE' }),
    group('sub.requests', 'Attendance Requests', 'SUBMODULE', [
      leaf('regularization', 'Regularization', [act('VIEW', 'TEAM'), act('APPROVE', 'TEAM'), act('EXPORT', 'ALL', 'ALL')]),
      leaf('overtime', 'Overtime', [act('VIEW', 'TEAM'), act('APPROVE', 'TEAM')]),
    ]),
  ]),
  group('mod.admin', 'Administration', 'MODULE', [
    leaf('roles', 'Roles', [act('VIEW', 'ALL', 'ALL'), act('CONFIGURE', 'ALL', 'ALL')], { type: 'SUBMODULE', sensitive: true }),
    leaf('payroll', 'Payroll', [act('VIEW', 'ALL', 'ALL')], { type: 'SUBMODULE', configurable: false }),
  ]),
];

const mine = (permissions: MyPermissionsResponse['permissions']): MyPermissionsResponse => ({ primaryRole: 'X', roles: [], permissions });
const everything = mine({
  attendance: { VIEW: ['ALL'], EDIT: ['ALL'] },
  regularization: { VIEW: ['ALL'], APPROVE: ['ALL'], EXPORT: ['ALL'] },
  overtime: { VIEW: ['ALL'], APPROVE: ['ALL'] },
  roles: { VIEW: ['ALL'], CONFIGURE: ['ALL'] },
  payroll: { VIEW: ['ALL'] },
});

describe('scope choices follow the catalogue range, capped by what the admin holds', () => {
  it('only scopes the backend evaluates', () => {
    expect(scopeChoices(act('VIEW', 'TEAM'), ['OWN', 'TEAM', 'ALL'])).toEqual(['TEAM', 'ALL']);
    expect(scopeChoices(act('VIEW', 'ALL', 'ALL'), ['OWN', 'TEAM', 'ALL'])).toEqual(['ALL']);
    expect(scopeChoices(act('VIEW', 'OWN', 'OWN'), ['OWN', 'TEAM', 'ALL'])).toEqual(['OWN']);
  });
  it('an admin who only holds TEAM cannot pick ALL', () => {
    expect(scopeChoices(act('VIEW', 'TEAM'), ['OWN', 'TEAM'])).toEqual(['TEAM']);
    expect(scopeChoices(act('VIEW', 'ALL', 'ALL'), ['OWN', 'TEAM'])).toEqual([]);
  });
  it('keeps a scope that is already on the role even if it is outside the range', () => {
    expect(scopeChoices(act('VIEW', 'ALL', 'ALL'), ['OWN', 'TEAM', 'ALL'], 'DEPARTMENT')).toEqual(['ALL', 'DEPARTMENT']);
  });
  it('a bulk tick uses the narrowest supported scope, never silently the widest', () => {
    expect(bulkScope(act('VIEW', 'TEAM'), ['OWN', 'TEAM', 'ALL'])).toBe('TEAM');
    expect(bulkScope(act('EXPORT', 'ALL', 'ALL'), ['OWN', 'TEAM', 'ALL'])).toBe('ALL');
  });
});

describe('parent selection is a bulk edit of the leaves beneath it', () => {
  const attendance = tree[0];

  it('selecting a module grants every applicable action in its subtree at the narrowest scope', () => {
    const map = setNode({}, attendance, true, everything);
    expect(map).toEqual({
      'attendance:VIEW': 'TEAM', 'attendance:EDIT': 'TEAM',
      'regularization:VIEW': 'TEAM', 'regularization:APPROVE': 'TEAM', 'regularization:EXPORT': 'ALL',
      'overtime:VIEW': 'TEAM', 'overtime:APPROVE': 'TEAM',
    });
    expect(nodeState(map, attendance, everything)).toBe('all');
  });

  it('selecting a submodule touches only that submodule', () => {
    const map = setNode({}, attendance.children[1], true, everything);
    expect(Object.keys(map).sort()).toEqual(['overtime:APPROVE', 'overtime:VIEW', 'regularization:APPROVE', 'regularization:EXPORT', 'regularization:VIEW']);
    expect(nodeState(map, attendance, everything)).toBe('some');
    expect(nodeState(map, attendance.children[1], everything)).toBe('all');
  });

  it('one individual change leaves everything else alone, and the parent reads partial', () => {
    const full = setNode({}, attendance, true, everything);
    const { 'overtime:APPROVE': _removed, ...rest } = full;
    expect(nodeState(rest, attendance, everything)).toBe('some');
    expect(rest['overtime:VIEW']).toBe('TEAM');
    expect(Object.keys(rest)).toHaveLength(Object.keys(full).length - 1);
  });

  it('clearing a parent removes only the grants beneath it', () => {
    const start = { ...setNode({}, attendance, true, everything), 'roles:VIEW': 'ALL' };
    const cleared = setNode(start, attendance, false, everything);
    expect(cleared).toEqual({ 'roles:VIEW': 'ALL' });
    expect(nodeState(cleared, attendance, everything)).toBe('none');
  });

  it('an existing wider scope is kept when the parent is ticked again', () => {
    const map = setNode({ 'attendance:VIEW': 'ALL' }, attendance, true, everything);
    expect(map['attendance:VIEW']).toBe('ALL');
  });

  it('high-impact permissions are skipped by a bulk tick, counted, and still cleared by a bulk clear', () => {
    const admin = tree[1];
    const map = setNode({}, admin, true, everything);
    expect(map).toEqual({});                       // roles is sensitive; payroll is not configurable
    expect(sensitiveSkipped(map, admin, everything)).toBe(2);
    const explicit = { 'roles:VIEW': 'ALL', 'roles:CONFIGURE': 'ALL' };
    expect(nodeState(explicit, admin, everything)).toBe('all');
    expect(setNode(explicit, admin, false, everything)).toEqual({});
  });

  it('never offers or grants what the admin does not hold themselves', () => {
    const teamOnly = mine({ regularization: { VIEW: ['TEAM'], APPROVE: ['TEAM'] }, overtime: { VIEW: ['TEAM'] }, attendance: { VIEW: ['TEAM'] } });
    const map = setNode({}, attendance, true, teamOnly);
    expect(map).toEqual({ 'attendance:VIEW': 'TEAM', 'regularization:VIEW': 'TEAM', 'regularization:APPROVE': 'TEAM', 'overtime:VIEW': 'TEAM' });
  });

  it('a non-configurable node is never toggled', () => {
    expect(setNode({}, tree[1].children[1], true, everything)).toEqual({});
    expect(nodeState({ 'payroll:VIEW': 'ALL' }, tree[1].children[1], everything)).toBe('none');
  });
});

describe('tree utilities', () => {
  it('leavesOf / flatten only list nodes that hold grants, in order', () => {
    expect(leavesOf(tree[0]).map((l) => l.code)).toEqual(['attendance', 'regularization', 'overtime']);
    expect(flattenCatalogue(tree).map((m) => m.code)).toEqual(['attendance', 'regularization', 'overtime', 'roles', 'payroll']);
    expect(flattenCatalogue(tree)[1]).toEqual({ code: 'regularization', name: 'Regularization', configurable: true, actions: ['VIEW', 'APPROVE', 'EXPORT'] });
  });

  it('counts granted actions beneath a node', () => {
    expect(nodeCounts({ 'regularization:VIEW': 'TEAM', 'overtime:VIEW': 'TEAM' }, tree[0])).toEqual({ granted: 2, total: 7 });
  });

  it('search keeps matching nodes with their ancestors, and everything under a matching node', () => {
    const hit = filterTree(tree, 'overtime');
    expect(hit).toHaveLength(1);
    expect(allNodeCodes(hit)).toEqual(['mod.attendance', 'sub.requests', 'overtime']);
    expect(allNodeCodes(filterTree(tree, 'attendance requests'))).toEqual(['mod.attendance', 'sub.requests', 'regularization', 'overtime']);
    expect(filterTree(tree, 'nothing like this')).toEqual([]);
    expect(allNodeCodes(filterTree(tree, 'approve'))).toEqual(['mod.attendance', 'sub.requests', 'regularization', 'overtime']);
    expect(filterTree(tree, '  ')).toBe(tree);
  });

  it('prunes to the granted leaves for the read-only view', () => {
    expect(allNodeCodes(pruneToGranted(tree, { 'overtime:VIEW': 'TEAM' }))).toEqual(['mod.attendance', 'sub.requests', 'overtime']);
    expect(pruneToGranted(tree, {})).toEqual([]);
  });

  it('reports added and removed high-impact permissions only', () => {
    const diff = sensitiveDiff({ 'attendance:VIEW': 'TEAM', 'roles:VIEW': 'ALL' }, { 'attendance:EDIT': 'TEAM', 'roles:CONFIGURE': 'ALL' }, tree);
    expect(diff).toEqual({ added: ['Roles: Configure'], removed: ['Roles: View'] });
  });
});
