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

// NOTE: Approve/Reject and Team/Organization "Pending Approvals"/"On Leave Today" KPIs are
// intentionally NOT modeled here. In production those live in ApprovalsPage.tsx ("Approval
// Center") and DashboardPage.tsx respectively — the Leave page itself (LeavePage.tsx, ported
// unmodified in logic into this preview's Leave tab) is self-service only, for every role. This
// preview must not imply otherwise. Only Holiday administration (Add/Edit/Delete) is role-gated,
// matching HolidaysPage.tsx's own `isAdmin` check.
export interface RoleConfig {
  role: PreviewRole;
  /** Mirrors lib/nav.config.ts's toShellRole() output — same 4 strings the app already uses. */
  shellRoleLabel: 'Employee' | 'Manager' | 'HR Admin' | 'Super Admin';
  isAdmin: boolean; // matches HolidaysPage.tsx's own `role === 'HR_ADMIN' || role === 'SUPER_ADMIN'` gate
  mockUserName: string;
  mockUserInitials: string;
}

export const ROLE_CONFIGS: Record<PreviewRole, RoleConfig> = {
  employee: {
    role: 'employee', shellRoleLabel: 'Employee', isAdmin: false,
    mockUserName: 'Divya Shenoy', mockUserInitials: 'DS',
  },
  manager: {
    role: 'manager', shellRoleLabel: 'Manager', isAdmin: false,
    mockUserName: 'Arjun Mehta', mockUserInitials: 'AM',
  },
  hr: {
    role: 'hr', shellRoleLabel: 'HR Admin', isAdmin: true,
    mockUserName: 'Priya Raghunathan', mockUserInitials: 'PR',
  },
  superAdmin: {
    role: 'superAdmin', shellRoleLabel: 'Super Admin', isAdmin: true,
    mockUserName: 'Rohit Shrivastava', mockUserInitials: 'RS',
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
