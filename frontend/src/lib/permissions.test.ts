import { describe, expect, it } from 'vitest';
import type { MyPermissionsResponse } from '../api/me';
import { assetsExpensesView, hasOrgWorkforceView } from './permissions';
import { dashboardViewFor } from '../pages/DashboardPage';
import { attendanceAccess } from '../pages/AttendancePage';

type Grants = MyPermissionsResponse['permissions'];

function perms(primaryRole: string, permissions: Grants): MyPermissionsResponse {
  return { primaryRole, roles: [primaryRole], permissions };
}

// The subset of V225's seeded matrix the converted page gates read — un-cascaded, exactly as
// GET /api/me/permissions returns it.
const MANAGER_GRANTS: Grants = {
  attendance: { VIEW: ['TEAM'] },
  regularization: { VIEW: ['TEAM'], APPROVE: ['TEAM'] },
  attendance_requests: { VIEW: ['TEAM'], APPROVE: ['TEAM'] },
  overtime: { VIEW: ['TEAM'], APPROVE: ['TEAM'] },
  leave: { VIEW: ['TEAM'], APPROVE: ['TEAM'] },
};
const HR_GRANTS: Grants = {
  attendance: { VIEW: ['ALL'] },
  regularization: { VIEW: ['ALL'], APPROVE: ['ALL'] },
  attendance_requests: { VIEW: ['ALL'], APPROVE: ['ALL'] },
  overtime: { VIEW: ['ALL'], APPROVE: ['ALL'] },
  leave: { VIEW: ['ALL'], APPROVE: ['ALL'], CONFIGURE: ['ALL'] },
  employees: { VIEW: ['ALL'], EDIT: ['ALL'] },
  holidays: { VIEW: ['ALL'], CREATE: ['ALL'], EDIT: ['ALL'], DELETE: ['ALL'] },
  hr_help_content: { VIEW: ['ALL'], CREATE: ['ALL'], EDIT: ['ALL'], DELETE: ['ALL'] },
};
const SA_GRANTS: Grants = {
  ...HR_GRANTS,
  // V234: no-lookback regularization + the "View All & Audit Trail" list are Super-Admin-only.
  regularization: { VIEW: ['ALL'], APPROVE: ['ALL'], CONFIGURE: ['ALL'], EXPORT: ['ALL'] },
  employees: { VIEW: ['ALL'], EDIT: ['ALL'], CREATE: ['ALL'] },
  ai_assistant: { VIEW: ['ALL'], CONFIGURE: ['ALL'] },
  user_management: { VIEW: ['ALL'], CREATE: ['ALL'], EDIT: ['ALL'], DELETE: ['ALL'] },
};

const SYSTEM = {
  EMPLOYEE: perms('EMPLOYEE', {}),
  MANAGER: perms('MANAGER', MANAGER_GRANTS),
  HR_ADMIN: perms('HR_ADMIN', HR_GRANTS),
  SUPER_ADMIN: perms('SUPER_ADMIN', SA_GRANTS),
};

describe('hasOrgWorkforceView', () => {
  it('is exactly HR Admin + Super Admin among system roles', () => {
    expect(hasOrgWorkforceView(SYSTEM.EMPLOYEE)).toBe(false);
    expect(hasOrgWorkforceView(SYSTEM.MANAGER)).toBe(false);
    expect(hasOrgWorkforceView(SYSTEM.HR_ADMIN)).toBe(true);
    expect(hasOrgWorkforceView(SYSTEM.SUPER_ADMIN)).toBe(true);
  });

  it('needs all three org endpoints, not just one', () => {
    expect(hasOrgWorkforceView(perms('EMPLOYEE', { employees: { VIEW: ['ALL'] } }))).toBe(false);
  });
});

describe('dashboardViewFor', () => {
  it('lands every system role on the dashboard it had by role', () => {
    expect(dashboardViewFor('EMPLOYEE', SYSTEM.EMPLOYEE)).toBe('employee');
    expect(dashboardViewFor('MANAGER', SYSTEM.MANAGER)).toBe('manager');
    expect(dashboardViewFor('HR_ADMIN', SYSTEM.HR_ADMIN)).toBe('hr');
    expect(dashboardViewFor('SUPER_ADMIN', SYSTEM.SUPER_ADMIN)).toBe('super-admin');
  });

  it('falls back to the role mapping before permissions load', () => {
    expect(dashboardViewFor('EMPLOYEE', null)).toBe('employee');
    expect(dashboardViewFor('MANAGER', null)).toBe('manager');
    expect(dashboardViewFor('HR_ADMIN', null)).toBe('hr');
    expect(dashboardViewFor('SUPER_ADMIN', null)).toBe('super-admin');
  });

  it('gives a custom-role holder the view their permissions allow', () => {
    expect(dashboardViewFor('EMPLOYEE', perms('EMPLOYEE', HR_GRANTS))).toBe('hr');
    expect(dashboardViewFor('EMPLOYEE', perms('EMPLOYEE', { attendance: { VIEW: ['TEAM'] } }))).toBe('manager');
    // Super Admin's composite dashboard is still primary-role only.
    expect(dashboardViewFor('EMPLOYEE', perms('EMPLOYEE', SA_GRANTS))).toBe('hr');
  });
});

describe('attendanceAccess', () => {
  it('matches the old role checks for every system role', () => {
    expect(attendanceAccess(SYSTEM.EMPLOYEE)).toEqual({
      canApproveRegularization: false, canApproveAttendanceRequests: false, canApproveOvertime: false,
      regularizationFinalStage: false, regularizationUnlimited: false, regularizationAuditTrail: false, roster: null,
    });
    expect(attendanceAccess(SYSTEM.MANAGER)).toEqual({
      canApproveRegularization: true, canApproveAttendanceRequests: true, canApproveOvertime: true,
      regularizationFinalStage: false, regularizationUnlimited: false, regularizationAuditTrail: false, roster: 'team',
    });
    for (const [p, superAdmin] of [[SYSTEM.HR_ADMIN, false], [SYSTEM.SUPER_ADMIN, true]] as const) {
      expect(attendanceAccess(p)).toEqual({
        canApproveRegularization: true, canApproveAttendanceRequests: true, canApproveOvertime: true,
        regularizationFinalStage: true, regularizationUnlimited: superAdmin, regularizationAuditTrail: superAdmin, roster: 'all',
      });
    }
  });

  it('gates each queue on its own permission for a custom-role holder', () => {
    const a = attendanceAccess(perms('EMPLOYEE', { overtime: { APPROVE: ['TEAM'] } }));
    expect(a.canApproveOvertime).toBe(true);
    expect(a.canApproveRegularization).toBe(false);
    expect(a.canApproveAttendanceRequests).toBe(false);
    expect(a.roster).toBeNull();
  });
});


describe('assetsExpensesView', () => {
  // V236/V242 seed: expenses + assets VIEW/APPROVE at ALL for HR Admin/Super Admin, APPROVE:TEAM for Manager.
  const hr = perms('HR_ADMIN', { assets: { VIEW: ['ALL'], APPROVE: ['ALL'] }, expenses: { VIEW: ['ALL'], APPROVE: ['ALL'] } });
  const manager = perms('MANAGER', { assets: { APPROVE: ['TEAM'] }, expenses: { VIEW: ['TEAM'], APPROVE: ['TEAM'] } });

  it('gives each system role the same view as before', () => {
    expect(assetsExpensesView(hr, 'HR_ADMIN')).toBe('hr');
    expect(assetsExpensesView(perms('SUPER_ADMIN', hr.permissions), 'SUPER_ADMIN')).toBe('hr');
    expect(assetsExpensesView(manager, 'MANAGER')).toBe('manager');
    expect(assetsExpensesView(perms('EMPLOYEE', {}), 'EMPLOYEE')).toBe('employee');
  });

  it('follows a custom role holder’s permissions, not their Employee primary role', () => {
    expect(assetsExpensesView(perms('EMPLOYEE', { expenses: { VIEW: ['ALL'] } }), 'EMPLOYEE')).toBe('hr');
    expect(assetsExpensesView(perms('EMPLOYEE', { assets: { APPROVE: ['TEAM'] } }), 'EMPLOYEE')).toBe('manager');
  });

  it('falls back to the primary role until permissions load', () => {
    expect(assetsExpensesView(null, 'SUPER_ADMIN')).toBe('hr');
    expect(assetsExpensesView(undefined, 'MANAGER')).toBe('manager');
  });
});
