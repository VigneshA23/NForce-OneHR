import type { CatalogueNode } from '../api/roles';
import { leavesOf } from './roleTree';

/**
 * How the role editor groups permissions: the same classification as the OneHR sidebar
 * (Home, People, Administration, Time & Leave, Requests & Approvals, Insights, Employee Services,
 * Payroll & Compensation), so an administrator finds a permission where the page lives in the menu.
 *
 * PRESENTATION ONLY. Every grant is still `<permission code>:<ACTION>:<SCOPE>` on the same existing
 * permission codes; this file only decides where each code is SHOWN. Nothing about what a code allows,
 * what the backend enforces, or what gets saved changes. A code appears under exactly one place; any
 * code this layout does not know (for example one added to the backend later) is collected under
 * "Other" so it can never become impossible to edit.
 *
 * Sidebar pages that have no permission of their own (everyone may open them, such as Dashboard or My
 * Attendance) or are not built yet (Templates, Integrations, Future Feature Lab) are listed too, as
 * information rows without checkboxes, so the tree matches the menu exactly.
 */

interface Spec {
  label: string;
  /** Permission codes shown under this entry. One code on a page = the page itself carries the actions. */
  leaves?: string[];
  children?: Spec[];
  /** Shown on rows that grant nothing, or to explain how a page relates to a permission. */
  note?: string;
}

const EVERYONE = 'Available to every signed-in user, no permission to grant';
const SOON = 'Not available yet';

const SIDEBAR: Spec[] = [
  { label: 'Home', children: [
    { label: 'Dashboard', note: EVERYONE, children: [{ label: 'Kudos (appreciate anyone)', leaves: ['kudos'] }] },
  ] },
  { label: 'People', children: [
    { label: 'My Team', note: 'The page is open to everyone; what it shows about other people follows the permissions in their own sections', children: [
      { label: 'Team Skill Ratings', leaves: ['skill_ratings'] },
    ] },
    { label: 'Employee Master', leaves: ['employees'] },
    { label: 'Organization', children: [
      { label: 'Org Hierarchy', note: EVERYONE },
      { label: 'Organization Structure', leaves: ['organization'] },
      { label: 'People Directory', note: EVERYONE },
      { label: 'Performance', leaves: ['talent_skills'] },
    ] },
  ] },
  { label: 'Administration', children: [
    { label: 'User Management', leaves: ['user_management'] },
    { label: 'Roles & Permissions', leaves: ['roles', 'user_roles'] },
    { label: 'Organization Masters', leaves: ['organization_shifts'] },
    { label: 'Workflow Studio', leaves: ['approval_rules'] },
    { label: 'Templates', note: SOON },
    { label: 'Integrations', note: SOON },
    { label: 'Future Feature Lab', note: SOON },
  ] },
  { label: 'Time & Leave', children: [
    { label: 'Attendance', children: [
      { label: 'My Attendance', note: EVERYONE },
      { label: 'Team Attendance', leaves: ['attendance'] },
      { label: 'Attendance Administration', note: 'The same permission as Team Attendance, granted at Organization scope' },
      { label: 'Regularization', leaves: ['regularization'] },
      { label: 'Work From Home & Partial Day', leaves: ['attendance_requests'] },
      { label: 'Overtime', leaves: ['overtime'] },
      { label: 'Exception Dashboard', leaves: ['exceptions'] },
      { label: 'Penalization Policy', leaves: ['penalization'] },
      { label: 'Employee Assignments', leaves: ['employee_assignments'] },
    ] },
    { label: 'Leave & Holidays', children: [
      { label: 'My Leave', note: EVERYONE },
      { label: 'Team Leave', note: 'The same permission as Leave Administration, granted at Team scope' },
      { label: 'Leave Administration', leaves: ['leave'] },
      { label: 'Holiday Calendar', leaves: ['holidays'] },
    ] },
  ] },
  { label: 'Requests & Approvals', children: [
    { label: 'Requests', children: [
      { label: 'My Requests', note: EVERYONE },
      { label: 'HR Service Requests', leaves: ['helpdesk'] },
    ] },
    { label: 'Approval Center', note: 'Opens for anyone who holds an Approve permission (leave, regularization, WFH, overtime, expenses, assets, FAQs & Guides)' },
  ] },
  { label: 'Insights', children: [
    { label: 'Reports & Analytics', leaves: ['reports'] },
    { label: 'Audit History', leaves: ['audit'] },
    { label: 'Audit & Security', leaves: ['audit_security'] },
    { label: 'API Usage', leaves: ['ai_assistant'] },
  ] },
  { label: 'Employee Services', children: [
    { label: 'Onboarding', leaves: ['onboarding'] },
    { label: 'Assets & Expenses', children: [
      { label: 'Assets', leaves: ['assets'] },
      { label: 'Expense Claims', leaves: ['expenses'] },
      { label: 'Expense Final-Stage Override', leaves: ['expenses_override'] },
    ] },
    { label: 'Project Allocations', leaves: ['project_allocations', 'projects'] },
    { label: 'Bench Management', leaves: ['bench'] },
    { label: 'Internal Opportunities', leaves: ['opportunities'] },
    { label: 'My Skills', note: EVERYONE },
    { label: 'Performance', note: SOON },
    { label: 'Documents & Compliance', leaves: ['documents'] },
    { label: 'Policies', leaves: ['policies', 'announcements'] },
    { label: 'My Documents', note: EVERYONE },
    { label: 'Help & Guidance', leaves: ['hr_help_content'] },
  ] },
  { label: 'Payroll & Compensation', note: 'Backend only: there is no payroll page in the sidebar yet', children: [
    { label: 'Payroll', leaves: ['payroll'] },
    { label: 'Compensation Bands', leaves: ['compensation'] },
  ] },
];

const TYPES = ['MODULE', 'SUBMODULE', 'SECTION'] as const;
const typeAt = (depth: number) => TYPES[Math.min(depth, 2)];

/** An entry with no permission of its own: shown for completeness, nothing to tick. */
function infoNode(code: string, label: string, note: string, depth: number): CatalogueNode {
  return { code, name: label, type: typeAt(depth), description: note, note, sensitive: false, configurable: false, actions: [], children: [] };
}

/** A leaf node from the backend, re-labelled and re-typed for its new place. Its code, actions, scopes and sensitivity are untouched. */
function withPlace(leaf: CatalogueNode, depth: number, label?: string): CatalogueNode {
  return { ...leaf, name: label ?? leaf.name, type: typeAt(depth), children: [] };
}

/**
 * Rearranges the backend's permission tree to the sidebar classification. Pure and total: every leaf in
 * `tree` appears exactly once in the result (unknown ones under "Other"), with its actions untouched.
 */
export function arrangeBySidebar(tree: CatalogueNode[]): CatalogueNode[] {
  const byCode = new Map(tree.flatMap(leavesOf).map((l) => [l.code, l] as const));
  const used = new Set<string>();
  const take = (code: string): CatalogueNode | undefined => {
    const leaf = byCode.get(code);
    if (leaf) used.add(code);
    return leaf;
  };

  function build(spec: Spec, path: string, depth: number): CatalogueNode | null {
    const code = `nav.${path}`;
    const leaves = (spec.leaves ?? []).map(take).filter((l): l is CatalogueNode => !!l);
    const kids = (spec.children ?? []).map((c, i) => build(c, `${path}.${i}`, depth + 1)).filter((n): n is CatalogueNode => !!n);

    if (kids.length === 0 && leaves.length === 1) {
      // The page IS the permission: the leaf takes the page's name and its place in the menu.
      return withPlace(leaves[0], depth, spec.label);
    }
    if (kids.length === 0 && leaves.length === 0) {
      return infoNode(code, spec.label, spec.note ?? SOON, depth);
    }
    return {
      code, name: spec.label, type: typeAt(depth), description: spec.note ?? '', sensitive: false, configurable: false, actions: [],
      children: [...kids, ...leaves.map((l) => withPlace(l, depth + 1))],
    };
  }

  const arranged = SIDEBAR.map((s, i) => build(s, String(i), 0)).filter((n): n is CatalogueNode => !!n);

  const rest = [...byCode.values()].filter((l) => !used.has(l.code));
  if (rest.length > 0) {
    arranged.push({
      code: 'nav.other', name: 'Other', type: 'MODULE', description: 'Permissions not yet placed in the sidebar layout.', sensitive: false,
      configurable: false, actions: [], children: rest.map((l) => withPlace(l, 1)),
    });
  }
  return arranged;
}
