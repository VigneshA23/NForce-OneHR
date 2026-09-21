import { describe, expect, it } from 'vitest';
import {
  computeHeadcountSummary, computeAttendanceSummary, computeLeaveSummary,
  buildHeadcountSheetRows, buildAttendanceSheetRows, buildLeaveSheetRows,
} from './ReportsPage';
import type { DirectReport } from '../api/dashboard';
import type { AttendanceRecord } from '../api/attendance';
import type { LeaveRequestRecord } from '../api/leave';

function report(overrides: Partial<DirectReport> = {}): DirectReport {
  return {
    userId: 'u1', employeeCode: 'E1', fullName: 'Jane Doe',
    designationName: 'Engineer', departmentName: 'Engineering', active: true,
    ...overrides,
  };
}

function attendance(overrides: Partial<AttendanceRecord> = {}): AttendanceRecord {
  return {
    id: 'a1', employeeUserId: 'u1', employeeCode: 'E1', fullName: 'Jane Doe',
    workDate: '2026-09-21', checkInAt: '2026-09-21T09:00:00Z', checkOutAt: null,
    sessionStartedAt: null, workedMinutes: null, status: 'PRESENT', lateByMinutes: null,
    source: null, workMode: null, timezone: null,
    ...overrides,
  } as AttendanceRecord;
}

function leave(overrides: Partial<LeaveRequestRecord> = {}): LeaveRequestRecord {
  return {
    id: 'l1', employeeUserId: 'u1', employeeName: 'Jane Doe', employeeCode: 'E1',
    leaveTypeCode: 'PAID', leaveTypeName: 'Paid Leave', leaveTypeClassification: 'PAID',
    startDate: '2026-09-10', endDate: '2026-09-10', halfDay: false, totalDays: 1,
    status: 'APPROVED', employeeReason: 'personal', decisionReason: null,
    decidedByName: null, decidedAt: null, createdAt: '2026-09-09T00:00:00Z',
    ...overrides,
  };
}

describe('computeHeadcountSummary', () => {
  it('counts total, active and inactive', () => {
    const rows = [report({ userId: '1', active: true }), report({ userId: '2', active: false }), report({ userId: '3', active: true })];
    const s = computeHeadcountSummary(rows);
    expect(s.total).toBe(3);
    expect(s.active).toBe(2);
    expect(s.inactive).toBe(1);
  });

  it('groups by department, falling back to Unassigned', () => {
    const rows = [
      report({ userId: '1', departmentName: 'Engineering' }),
      report({ userId: '2', departmentName: 'Engineering' }),
      report({ userId: '3', departmentName: null }),
    ];
    const s = computeHeadcountSummary(rows);
    expect(s.byDepartment).toEqual(expect.arrayContaining([
      { name: 'Engineering', count: 2 },
      { name: 'Unassigned', count: 1 },
    ]));
  });

  it('handles an empty scope', () => {
    const s = computeHeadcountSummary([]);
    expect(s).toEqual({ total: 0, active: 0, inactive: 0, byDepartment: [] });
  });
});

describe('computeAttendanceSummary', () => {
  it('buckets every known status', () => {
    const records = [
      attendance({ employeeUserId: '1', status: 'PRESENT' }),
      attendance({ employeeUserId: '2', status: 'LATE' }),
      attendance({ employeeUserId: '3', status: 'ABSENT' }),
      attendance({ employeeUserId: '4', status: 'ON_LEAVE' }),
      attendance({ employeeUserId: '5', status: 'HALF_DAY' }),
      attendance({ employeeUserId: '6', status: 'MISSING_CHECKOUT' }),
    ];
    const s = computeAttendanceSummary(records, 6);
    expect(s.present).toBe(3); // PRESENT + HALF_DAY + MISSING_CHECKOUT
    expect(s.late).toBe(1);
    expect(s.absent).toBe(1);
    expect(s.onLeave).toBe(1);
    expect(s.notCheckedIn).toBe(0);
  });

  it('treats employees with no record at all as Not Checked-In', () => {
    const records = [attendance({ employeeUserId: '1', status: 'PRESENT' })];
    const s = computeAttendanceSummary(records, 5);
    expect(s.notCheckedIn).toBe(4);
  });

  it('never goes negative when there are more records than headcount', () => {
    const records = [attendance({ employeeUserId: '1' }), attendance({ employeeUserId: '2' })];
    const s = computeAttendanceSummary(records, 1);
    expect(s.notCheckedIn).toBe(0);
  });

  it('handles no attendance data at all', () => {
    const s = computeAttendanceSummary([], 0);
    expect(s).toEqual({ present: 0, late: 0, absent: 0, onLeave: 0, notCheckedIn: 0 });
  });
});

describe('computeLeaveSummary', () => {
  it('totals approved count and days, and passes pending through untouched', () => {
    const records = [
      leave({ id: '1', totalDays: 1, leaveTypeName: 'Paid Leave' }),
      leave({ id: '2', totalDays: 2.5, leaveTypeName: 'Sick Leave' }),
    ];
    const s = computeLeaveSummary(records, 4);
    expect(s.approvedCount).toBe(2);
    expect(s.totalDaysApproved).toBe(3.5);
    expect(s.pendingCount).toBe(4);
  });

  it('breaks down by leave type', () => {
    const records = [
      leave({ id: '1', leaveTypeName: 'Paid Leave', totalDays: 1 }),
      leave({ id: '2', leaveTypeName: 'Paid Leave', totalDays: 1 }),
      leave({ id: '3', leaveTypeName: 'Sick Leave', totalDays: 2 }),
    ];
    const s = computeLeaveSummary(records, 0);
    expect(s.byType).toEqual(expect.arrayContaining([
      { name: 'Paid Leave', count: 2, days: 2 },
      { name: 'Sick Leave', count: 1, days: 2 },
    ]));
  });

  it('handles no leave at all', () => {
    const s = computeLeaveSummary([], 0);
    expect(s).toEqual({ approvedCount: 0, totalDaysApproved: 0, pendingCount: 0, byType: [] });
  });
});

describe('Excel export row builders', () => {
  it('buildHeadcountSheetRows matches the displayed columns', () => {
    const rows = buildHeadcountSheetRows([report({ fullName: 'Jane Doe', employeeCode: 'E1', departmentName: 'Eng', designationName: 'SWE', active: true })]);
    expect(rows).toEqual([{ 'Full Name': 'Jane Doe', 'Employee Code': 'E1', 'Department': 'Eng', 'Designation': 'SWE', 'Status': 'Active' }]);
  });

  it('buildAttendanceSheetRows includes status and timing', () => {
    const rows = buildAttendanceSheetRows([attendance({ status: 'LATE', lateByMinutes: 15 })]);
    expect(rows[0]).toMatchObject({ 'Status': 'LATE', 'Late By (min)': 15 });
  });

  it('buildLeaveSheetRows includes dates and status', () => {
    const rows = buildLeaveSheetRows([leave({ startDate: '2026-09-10', endDate: '2026-09-12', status: 'APPROVED' })]);
    expect(rows[0]).toMatchObject({ 'Start Date': '2026-09-10', 'End Date': '2026-09-12', 'Status': 'APPROVED' });
  });

  it('export row builders handle empty data without throwing', () => {
    expect(buildHeadcountSheetRows([])).toEqual([]);
    expect(buildAttendanceSheetRows([])).toEqual([]);
    expect(buildLeaveSheetRows([])).toEqual([]);
  });
});
