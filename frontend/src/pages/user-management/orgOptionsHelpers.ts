import type { EmployeeRecord } from '../../api/employees';
import type { ShiftRow } from '../../api/org';
import type { PolicyOption } from '../../api/employeeAssignments';
import type { Location } from './SharedControls';

// Moved out of UserManagementPage.tsx (same reasoning as SharedControls.tsx) so both it and the
// four-step employee creation wizard can import this org-wide reference data shape and its
// filter helpers independently, without the wizard importing from the page that renders it.
// Logic, filtering rules, and comments are unchanged from the originals.

export interface OrgOptions {
  businessUnits: any[]; departments: any[]; designations: any[]; locations: any[]; managers: EmployeeRecord[]; shifts: ShiftRow[];
  // Step 3 (Work & Policy Configuration) dropdown data — fetched once alongside everything else
  // above via employeeAssignmentsApi.lookups(token), the same existing single-call endpoint the
  // My Team bulk-assignment screen already uses (see UserManagementPage's orgOptions effect).
  // Optional so EditModal/other existing callers that don't pass these fields still type-check.
  weeklyOffPolicies?: PolicyOption[];
  penalisationPolicies?: PolicyOption[];
}

export const ROLES = [
  { value: 'EMPLOYEE',    label: 'Employee' },
  { value: 'MANAGER',     label: 'Manager' },
  { value: 'HR_ADMIN',    label: 'HR Admin' },
  { value: 'SUPER_ADMIN', label: 'Super Admin' },
];

export const EMPLOYMENT_TYPES = ['FULL_TIME', 'PART_TIME', 'CONTRACT', 'INTERN'];
export const WORK_MODES = ['ONSITE', 'HYBRID', 'REMOTE'];

/** "Sep 16, 2026" from a plain "YYYY-MM-DD" date-input value — for the Effective From helper text. */
export function fmtEffectiveDate(iso: string): string {
  return new Date(iso + 'T00:00:00').toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' });
}

// Same reasoning/shape as getManagersForRole just below: `shifts` (opts.shifts) is fetched once
// per page load and shared by Add/Edit — an inactive shift must not be a pickable option for a
// new or changed assignment, but an employee already on a shift that's since been deactivated
// must still see their own current assignment rendered (not silently blanked out) when the Edit
// modal opens. `currentShiftId` is `form.shiftId`, so this is a no-op filter for Add (starts
// undefined) and only keeps the one already-assigned entry for Edit.
export function getShiftOptions(shifts: ShiftRow[], currentShiftId: string | undefined): ShiftRow[] {
  return shifts.filter(s => s.active !== false || s.id === currentShiftId);
}

// Same reasoning/shape as getShiftOptions just above, for the 3 other master-data dropdowns that
// were still handing out every row (including deactivated ones) unfiltered.
export function getDepartmentOptions(departments: any[], currentId: string | undefined): any[] {
  return departments.filter(d => d.active !== false || d.id === currentId);
}

// Designations are department-scoped (V202) — only designations mapped to the selected
// Department are offered, so a "QA Engineer III" can no longer be picked under Finance. A
// designation with no departmentId at all predates V202 and isn't scoped to any department yet;
// it's excluded here as unmapped (same as any other department mismatch) until someone re-saves
// it in Org Setup with a Department. The currently-assigned designation is always kept, exactly
// like the active/inactive exception above, so re-saving an employee whose designation was set
// before this filter existed doesn't silently wipe it off the form.
export function getDesignationOptions(designations: any[], currentId: string | undefined, departmentId: string | undefined): any[] {
  return designations.filter(d => (d.active !== false || d.id === currentId) && (d.id === currentId || d.departmentId === departmentId));
}

export function getLocationOptions(locations: Location[], currentId: string | undefined): Location[] {
  return locations.filter(l => l.active !== false || l.id === currentId);
}

// Deactivated managers are deliberately kept in this list (not filtered out) so an employee
// already reporting to one still shows that name in the dropdown instead of it silently
// vanishing — the option is rendered disabled (see the two <option> call sites below) rather
// than excluded, so it can't be picked for a NEW assignment. A genuinely deleted manager is
// excluded server-side already (EmployeeService.listPotentialManagers), so nothing to do here
// for that case.
//
// An Employee can report to a Manager or an HR Admin; a Manager can report to an HR Admin or a
// Super Admin; HR Admin/Super Admin still only report to a Super Admin. HR Admin was previously
// excluded from every tier even though EmployeeService.listPotentialManagers already treats it
// as an eligible manager role.
export function getManagersForRole(role: string, managers: EmployeeRecord[]): EmployeeRecord[] {
  if (role === 'EMPLOYEE') return managers.filter(m => m.role === 'MANAGER' || m.role === 'HR_ADMIN');
  if (role === 'MANAGER') return managers.filter(m => m.role === 'HR_ADMIN' || m.role === 'SUPER_ADMIN');
  return managers.filter(m => m.role === 'SUPER_ADMIN');
}
