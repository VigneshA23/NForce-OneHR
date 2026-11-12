import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import type { CatalogueNode } from '../api/roles';
import { arrangeBySidebar } from './sidebarHierarchy';
import { leavesOf } from './roleTree';

/** Every real permission code, read from the backend catalogue so the layout is checked against the actual list. */
function realLeafCodes(): string[] {
  const yml = readFileSync(resolve(__dirname, '../../../backend/src/main/resources/permission-catalogue.yml'), 'utf8');
  const modulesPart = yml.slice(0, yml.indexOf('\nroutes:'));
  return [...modulesPart.matchAll(/- code: (\S+)/g)].map((m) => m[1]).filter((c) => !c.startsWith('mod.') && !c.startsWith('sub.'));
}

const leaf = (code: string, extra: Partial<CatalogueNode> = {}): CatalogueNode => ({
  code, name: code, type: 'SUBMODULE', description: `desc ${code}`, sensitive: false, configurable: true,
  actions: [{ action: 'VIEW', minScope: 'TEAM', maxScope: 'ALL' }, { action: 'EDIT', minScope: 'ALL', maxScope: 'ALL' }],
  children: [], ...extra,
});
const backendTree = (codes: string[]): CatalogueNode[] => [{
  code: 'mod.any', name: 'Any', type: 'MODULE', description: '', sensitive: false, configurable: false, actions: [],
  children: codes.map((c) => leaf(c)),
}];

type Outline = [string, Outline?][];
const outline = (nodes: CatalogueNode[]): Outline => nodes.map((n): [string, Outline?] => (n.children.length ? [n.name, outline(n.children)] : [n.name]));

// The OneHR sidebar classification. Entries that are bare permission codes (lower case) are existing
// permissions that have no page of their own in the sidebar, kept under the page they belong to so they stay editable.
const EXPECTED: Outline = [
  ['Home', [['Dashboard', [['Kudos (appreciate anyone)']]]]],
  ['People', [
    ['My Team', [['Team Skill Ratings']]],
    ['Employee Master'],
    ['Organization', [['Org Hierarchy'], ['Organization Structure'], ['People Directory'], ['Performance']]],
  ]],
  ['Administration', [
    ['User Management'],
    ['Roles & Permissions', [['roles'], ['user_roles']]],
    ['Organization Masters'], ['Workflow Studio'], ['Templates'], ['Integrations'], ['Future Feature Lab'],
  ]],
  ['Time & Leave', [
    ['Attendance', [
      ['My Attendance'], ['Team Attendance'], ['Attendance Administration'], ['Regularization'],
      ['Work From Home & Partial Day'], ['Overtime'], ['Exception Dashboard'], ['Penalization Policy'], ['Employee Assignments'],
    ]],
    ['Leave & Holidays', [['My Leave'], ['Team Leave'], ['Leave Administration'], ['Holiday Calendar']]],
  ]],
  ['Requests & Approvals', [
    ['Requests', [['My Requests'], ['HR Service Requests']]],
    ['Approval Center'],
  ]],
  ['Insights', [['Reports & Analytics'], ['Audit History'], ['Audit & Security'], ['API Usage']]],
  ['Employee Services', [
    ['Onboarding'],
    ['Assets & Expenses', [['Assets'], ['Expense Claims'], ['Expense Final-Stage Override']]],
    ['Project Allocations', [['project_allocations'], ['projects']]],
    ['Bench Management'], ['Internal Opportunities'], ['My Skills'], ['Performance'],
    ['Documents & Compliance'],
    ['Policies', [['policies'], ['announcements']]],
    ['My Documents'], ['Help & Guidance'],
  ]],
  ['Payroll & Compensation', [['Payroll'], ['Compensation Bands']]],
];

describe('role editor groups permissions like the sidebar', () => {
  const codes = realLeafCodes();
  const arranged = arrangeBySidebar(backendTree(codes));

  it('reads the real catalogue (guards against an empty or mis-parsed list)', () => {
    expect(codes.length).toBeGreaterThan(30);
    expect(codes).toContain('regularization');
  });

  it('matches the OneHR sidebar classification, top to bottom', () => {
    expect(outline(arranged)).toEqual(EXPECTED);
  });

  it('shows every existing permission exactly once, so none becomes impossible to edit', () => {
    const shown = arranged.flatMap(leavesOf).map((l) => l.code);
    expect([...shown].sort()).toEqual([...codes].sort());
    expect(new Set(shown).size).toBe(shown.length);
    expect(arranged.some((n) => n.name === 'Other')).toBe(false);
  });

  it('leaves permission codes, actions, scope ranges and sensitivity exactly as the backend sent them', () => {
    const original = new Map(backendTree(codes).flatMap(leavesOf).map((l) => [l.code, l] as const));
    for (const shown of arranged.flatMap(leavesOf)) {
      const o = original.get(shown.code)!;
      expect(shown.actions).toEqual(o.actions);
      expect(shown.sensitive).toBe(o.sensitive);
      expect(shown.configurable).toBe(o.configurable);
    }
  });

  it('a page that is one permission takes the sidebar name and keeps the permission code', () => {
    const attendance = arranged.flatMap(function walk(n): CatalogueNode[] { return [n, ...n.children.flatMap(walk)]; }).find((n) => n.code === 'attendance')!;
    expect(attendance.name).toBe('Team Attendance');
    expect(attendance.actions.length).toBeGreaterThan(0);
  });

  it('pages with no permission of their own are rows with nothing to tick', () => {
    const all = arranged.flatMap(function walk(n): CatalogueNode[] { return [n, ...n.children.flatMap(walk)]; });
    const dashboard = all.find((n) => n.name === 'Dashboard')!;
    expect(dashboard.actions).toEqual([]);
    const myAttendance = all.find((n) => n.name === 'My Attendance')!;
    expect(myAttendance.actions).toEqual([]);
    expect(myAttendance.children).toEqual([]);
    expect(myAttendance.note).toMatch(/every signed-in user/);
  });

  it('a permission this layout does not know yet is kept, under "Other", never dropped', () => {
    const withNew = arrangeBySidebar(backendTree([...codes, 'future_thing']));
    const other = withNew.find((n) => n.name === 'Other')!;
    expect(other.children.map((c) => c.code)).toEqual(['future_thing']);
  });

  it('a permission the backend does not offer is simply a row without actions', () => {
    const without = arrangeBySidebar(backendTree(codes.filter((c) => c !== 'audit_security')));
    const insights = without.find((n) => n.name === 'Insights')!;
    const sec = insights.children.find((n) => n.name === 'Audit & Security')!;
    expect(sec.actions).toEqual([]);
  });
});
