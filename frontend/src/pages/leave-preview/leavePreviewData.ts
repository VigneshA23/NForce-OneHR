/**
 * Mock data ONLY, for the role-specific Leave & Holidays visual previews
 * (see LeavePreviewPage.tsx). Nothing in this file is fetched from or sent
 * to any real API — every value here is invented for design review.
 *
 * Shapes mirror the real types in api/leave.ts / api/holidays.ts exactly
 * (LeaveType, LeaveBalance, LeaveRequestRecord, HolidayRow) so the preview
 * renders with the same components the production page would use.
 */

export type PreviewRole = 'employee' | 'manager' | 'hr' | 'superAdmin';

export type MockStatus = 'PENDING' | 'APPROVED' | 'REJECTED';

export interface MockLeaveType { code: string; name: string; }
export interface MockBalance { leaveTypeCode: string; leaveTypeName: string; totalDays: number; usedDays: number; remainingDays: number; }
export interface MockRequest {
  id: string; employeeUserId: string; employeeName: string; departmentName: string;
  leaveTypeCode: string; leaveTypeName: string; startDate: string; endDate: string;
  halfDay: boolean; totalDays: number; status: MockStatus; employeeReason: string;
  decisionReason: string | null; decidedByName: string | null; decidedAt: string | null;
}
export interface MockHoliday { id: string; holidayName: string; holidayDate: string; locationName: string; active: boolean; }

export interface RoleConfig {
  role: PreviewRole;
  /** Mirrors lib/nav.config.ts's toShellRole() output — same 4 strings production branches on. */
  shellRoleLabel: 'Employee' | 'Manager' | 'HR Admin' | 'Super Admin';
  isAdmin: boolean;     // matches HolidayController / leaveApi.organization() gate (HR_ADMIN, SUPER_ADMIN)
  isManager: boolean;   // matches "current reporting manager" gate
  canApprove: boolean;  // isAdmin || isManager
  mockUserName: string;
  mockUserInitials: string;
  approvalsScopeLabel: string;   // e.g. "Team", "Organization"
  onLeaveScopeLabel: string;     // e.g. "Team", "Organization"
  contextDescription: string;
}

export const ROLE_CONFIGS: Record<PreviewRole, RoleConfig> = {
  employee: {
    role: 'employee', shellRoleLabel: 'Employee', isAdmin: false, isManager: false, canApprove: false,
    mockUserName: 'Divya Shenoy', mockUserInitials: 'DS',
    approvalsScopeLabel: '', onLeaveScopeLabel: '',
    contextDescription: 'View your leave balance, request leave, and track approvals.',
  },
  manager: {
    role: 'manager', shellRoleLabel: 'Manager', isAdmin: false, isManager: true, canApprove: true,
    mockUserName: 'Arjun Mehta', mockUserInitials: 'AM',
    approvalsScopeLabel: 'Team', onLeaveScopeLabel: 'Team',
    contextDescription: 'View your leave balance, request leave, and review your team\'s pending requests.',
  },
  hr: {
    role: 'hr', shellRoleLabel: 'HR Admin', isAdmin: true, isManager: false, canApprove: true,
    mockUserName: 'Priya Raghunathan', mockUserInitials: 'PR',
    approvalsScopeLabel: 'Organization', onLeaveScopeLabel: 'Organization',
    contextDescription: 'View your leave balance, manage organization-wide requests, and administer company holidays.',
  },
  superAdmin: {
    role: 'superAdmin', shellRoleLabel: 'Super Admin', isAdmin: true, isManager: false, canApprove: true,
    mockUserName: 'Rohit Shrivastava', mockUserInitials: 'RS',
    approvalsScopeLabel: 'Organization', onLeaveScopeLabel: 'Organization',
    contextDescription: 'View your leave balance, manage organization-wide requests, and administer company holidays.',
  },
};

export const MOCK_TYPES: MockLeaveType[] = [
  { code: 'ANNUAL', name: 'Annual Leave' },
  { code: 'SICK', name: 'Sick Leave' },
  { code: 'CASUAL', name: 'Casual Leave' },
  { code: 'MATERNITY', name: 'Maternity & Parental Leave' },
];

// Signed-in user's OWN balance/history — every role sees their own leave too (even HR/Super
// Admin/Manager are employees themselves), just with different additional sections around it.
export const OWN_BALANCES: MockBalance[] = [
  { leaveTypeCode: 'ANNUAL', leaveTypeName: 'Annual Leave', totalDays: 18, usedDays: 6.5, remainingDays: 11.5 },
  { leaveTypeCode: 'SICK', leaveTypeName: 'Sick Leave', totalDays: 10, usedDays: 2, remainingDays: 8 },
  { leaveTypeCode: 'CASUAL', leaveTypeName: 'Casual Leave', totalDays: 6, usedDays: 5.5, remainingDays: 0.5 },
  { leaveTypeCode: 'MATERNITY', leaveTypeName: 'Maternity & Parental Leave', totalDays: 0, usedDays: 0, remainingDays: 0 },
];

function ownRequests(employeeUserId: string, employeeName: string, departmentName: string): MockRequest[] {
  return [
    { id: `${employeeUserId}-r5`, employeeUserId, employeeName, departmentName, leaveTypeCode: 'ANNUAL', leaveTypeName: 'Annual Leave', startDate: '2026-10-12', endDate: '2026-10-16', halfDay: false, totalDays: 5, status: 'PENDING', employeeReason: 'Family wedding out of town, travelling with dependents', decisionReason: null, decidedByName: null, decidedAt: null },
    { id: `${employeeUserId}-r4`, employeeUserId, employeeName, departmentName, leaveTypeCode: 'SICK', leaveTypeName: 'Sick Leave', startDate: '2026-09-25', endDate: '2026-09-25', halfDay: true, totalDays: 0.5, status: 'PENDING', employeeReason: 'Dentist appointment', decisionReason: null, decidedByName: null, decidedAt: null },
    { id: `${employeeUserId}-r3`, employeeUserId, employeeName, departmentName, leaveTypeCode: 'ANNUAL', leaveTypeName: 'Annual Leave', startDate: '2026-08-14', endDate: '2026-08-21', halfDay: false, totalDays: 6, status: 'APPROVED', employeeReason: 'Annual family vacation', decisionReason: null, decidedByName: 'Priya Raghunathan', decidedAt: '2026-07-30T11:22:00' },
    { id: `${employeeUserId}-r2`, employeeUserId, employeeName, departmentName, leaveTypeCode: 'CASUAL', leaveTypeName: 'Casual Leave', startDate: '2026-07-10', endDate: '2026-07-10', halfDay: false, totalDays: 1, status: 'REJECTED', employeeReason: 'Personal work', decisionReason: 'Critical release week — please reschedule if possible', decidedByName: 'Arjun Mehta', decidedAt: '2026-07-08T09:40:00' },
    { id: `${employeeUserId}-r1`, employeeUserId, employeeName, departmentName, leaveTypeCode: 'SICK', leaveTypeName: 'Sick Leave', startDate: '2026-06-02', endDate: '2026-06-03', halfDay: false, totalDays: 2, status: 'APPROVED', employeeReason: 'Viral fever, doctor advised rest', decisionReason: null, decidedByName: 'Priya Raghunathan', decidedAt: '2026-06-01T13:40:00' },
  ];
}

export function ownRequestsFor(role: RoleConfig): MockRequest[] {
  return ownRequests('me', role.mockUserName, role.role === 'employee' ? 'Customer Success' : 'Leadership');
}

// ── Team roster (Manager's direct reports) — used for the Manager's approvals panel and
// "on leave today" count. Deliberately spans multiple statuses/leave types/edge cases. ──
export const TEAM_ROSTER: MockRequest[] = [
  { id: 't1', employeeUserId: 'emp-101', employeeName: 'Kavya Nair', departmentName: 'Customer Success', leaveTypeCode: 'ANNUAL', leaveTypeName: 'Annual Leave', startDate: '2026-09-28', endDate: '2026-10-02', halfDay: false, totalDays: 4, status: 'PENDING', employeeReason: 'Sister\'s wedding, travelling to Kerala', decisionReason: null, decidedByName: null, decidedAt: null },
  { id: 't2', employeeUserId: 'emp-102', employeeName: 'Rahul Verma', departmentName: 'Customer Success', leaveTypeCode: 'SICK', leaveTypeName: 'Sick Leave', startDate: '2026-09-22', endDate: '2026-09-23', halfDay: false, totalDays: 2, status: 'PENDING', employeeReason: 'Flu, doctor advised bed rest', decisionReason: null, decidedByName: null, decidedAt: null },
  { id: 't3', employeeUserId: 'emp-103', employeeName: 'Sneha Iyer', departmentName: 'Customer Success', leaveTypeCode: 'CASUAL', leaveTypeName: 'Casual Leave', startDate: '2026-09-22', endDate: '2026-09-22', halfDay: true, totalDays: 0.5, status: 'PENDING', employeeReason: 'Bank work, half day needed', decisionReason: null, decidedByName: null, decidedAt: null },
  { id: 't4', employeeUserId: 'emp-104', employeeName: 'Farhan Sheikh', departmentName: 'Customer Success', leaveTypeCode: 'ANNUAL', leaveTypeName: 'Annual Leave', startDate: '2026-09-18', endDate: '2026-09-24', halfDay: false, totalDays: 7, status: 'APPROVED', employeeReason: 'Pre-planned vacation', decisionReason: null, decidedByName: 'Arjun Mehta', decidedAt: '2026-09-01T10:00:00' },
  { id: 't5', employeeUserId: 'emp-105', employeeName: 'Meera Pillai', departmentName: 'Customer Success', leaveTypeCode: 'SICK', leaveTypeName: 'Sick Leave', startDate: '2026-08-20', endDate: '2026-08-20', halfDay: false, totalDays: 1, status: 'REJECTED', employeeReason: 'Not feeling well', decisionReason: 'Missing supporting note — please resubmit with details', decidedByName: 'Arjun Mehta', decidedAt: '2026-08-20T15:00:00' },
];

// ── Organization roster (HR Admin / Super Admin scope) — several departments/locations,
// larger than the team roster to demonstrate the org-wide view distinctly. ──
export const ORG_ROSTER: MockRequest[] = [
  ...TEAM_ROSTER,
  { id: 'o1', employeeUserId: 'emp-201', employeeName: 'Aditya Kulkarni', departmentName: 'Engineering', leaveTypeCode: 'ANNUAL', leaveTypeName: 'Annual Leave', startDate: '2026-10-05', endDate: '2026-10-09', halfDay: false, totalDays: 5, status: 'PENDING', employeeReason: 'Diwali travel to hometown', decisionReason: null, decidedByName: null, decidedAt: null },
  { id: 'o2', employeeUserId: 'emp-202', employeeName: 'Lakshmi Venkatesh', departmentName: 'Finance', leaveTypeCode: 'CASUAL', leaveTypeName: 'Casual Leave', startDate: '2026-09-23', endDate: '2026-09-23', halfDay: false, totalDays: 1, status: 'PENDING', employeeReason: 'Property registration appointment', decisionReason: null, decidedByName: null, decidedAt: null },
  { id: 'o3', employeeUserId: 'emp-203', employeeName: 'Vikram Choudhary', departmentName: 'Engineering', leaveTypeCode: 'SICK', leaveTypeName: 'Sick Leave', startDate: '2026-09-22', endDate: '2026-09-24', halfDay: false, totalDays: 3, status: 'PENDING', employeeReason: 'Recovering from minor surgery', decisionReason: null, decidedByName: null, decidedAt: null },
  { id: 'o4', employeeUserId: 'emp-204', employeeName: 'Neha Kapoor', departmentName: 'Marketing', leaveTypeCode: 'ANNUAL', leaveTypeName: 'Annual Leave', startDate: '2026-09-15', endDate: '2026-09-19', halfDay: false, totalDays: 5, status: 'APPROVED', employeeReason: 'Family function', decisionReason: null, decidedByName: 'Priya Raghunathan', decidedAt: '2026-09-05T09:00:00' },
  { id: 'o5', employeeUserId: 'emp-205', employeeName: 'Suresh Babu', departmentName: 'Finance', leaveTypeCode: 'CASUAL', leaveTypeName: 'Casual Leave', startDate: '2026-08-11', endDate: '2026-08-11', halfDay: false, totalDays: 1, status: 'REJECTED', employeeReason: 'Personal errands', decisionReason: 'Month-end close in progress, please reschedule', decidedByName: 'Priya Raghunathan', decidedAt: '2026-08-10T17:00:00' },
];

export const MOCK_HOLIDAYS: MockHoliday[] = [
  { id: 'h1', holidayName: 'Gandhi Jayanti', holidayDate: '2026-10-02', locationName: 'Bengaluru HQ', active: true },
  { id: 'h2', holidayName: 'Dussehra', holidayDate: '2026-10-20', locationName: 'Bengaluru HQ', active: true },
  { id: 'h3', holidayName: 'Diwali', holidayDate: '2026-11-08', locationName: 'Bengaluru HQ', active: true },
  { id: 'h4', holidayName: 'Diwali (Second Day)', holidayDate: '2026-11-09', locationName: 'Bengaluru HQ', active: true },
  { id: 'h5', holidayName: 'Guru Nanak Jayanti', holidayDate: '2026-11-24', locationName: 'Bengaluru HQ', active: true },
  { id: 'h6', holidayName: 'Christmas', holidayDate: '2026-12-25', locationName: 'Bengaluru HQ', active: true },
  { id: 'h7', holidayName: "New Year's Day", holidayDate: '2027-01-01', locationName: 'Bengaluru HQ', active: true },
  { id: 'h8', holidayName: 'Makar Sankranti', holidayDate: '2027-01-14', locationName: 'Hyderabad Office', active: true },
  { id: 'h9', holidayName: 'Republic Day', holidayDate: '2027-01-26', locationName: 'Bengaluru HQ', active: true },
  { id: 'h10', holidayName: 'Independence Day', holidayDate: '2026-08-15', locationName: 'Bengaluru HQ', active: false },
];

export function approvalsFor(role: RoleConfig): MockRequest[] {
  if (role.role === 'manager') return TEAM_ROSTER.filter(r => r.status === 'PENDING');
  if (role.role === 'hr' || role.role === 'superAdmin') return ORG_ROSTER.filter(r => r.status === 'PENDING');
  return [];
}

export function onLeaveTodayCountFor(role: RoleConfig): number | null {
  const todayIso = '2026-09-22';
  const roster = role.role === 'manager' ? TEAM_ROSTER : role.role === 'hr' || role.role === 'superAdmin' ? ORG_ROSTER : null;
  if (!roster) return null;
  const ids = new Set(
    roster
      .filter(r => r.status === 'APPROVED' && r.startDate <= todayIso && r.endDate >= todayIso)
      .map(r => r.employeeUserId)
  );
  return ids.size;
}
