import { describe, expect, it } from 'vitest';
import { groupRequestsByType, TYPE_LABELS } from '../components/TypeBadge';
import type { ApprovalItem } from '../api/approvalCenter';
import { REPORT_COLUMN_SETS, autoSizeColumns } from './MyTeamPage';
import type { WorkSheet } from 'xlsx';

function mockRequest(requestType: 'LEAVE' | 'REGULARIZATION', id: string): ApprovalItem {
  return {
    id,
    requestType,
    employeeUserId: 'user-1',
    employeeName: 'Test User',
    createdAt: '2026-09-10T10:00:00Z',
  };
}

describe('groupRequestsByType', () => {
  it('returns empty array when there are no requests', () => {
    expect(groupRequestsByType([])).toEqual([]);
  });

  it('handles a single leave request without grouping', () => {
    const requests = [mockRequest('LEAVE', 'req-1')];
    const grouped = groupRequestsByType(requests);
    expect(grouped).toEqual([
      { type: 'LEAVE', count: 1 },
    ]);
  });

  it('groups multiple leave requests and counts them (e.g. 3 leave requests)', () => {
    const requests = [
      mockRequest('LEAVE', 'req-1'),
      mockRequest('LEAVE', 'req-2'),
      mockRequest('LEAVE', 'req-3'),
    ];
    const grouped = groupRequestsByType(requests);
    expect(grouped).toEqual([
      { type: 'LEAVE', count: 3 },
    ]);
  });

  it('groups mixed leave and regularization requests in consistent order', () => {
    const requests = [
      mockRequest('REGULARIZATION', 'reg-1'),
      mockRequest('LEAVE', 'lv-1'),
      mockRequest('LEAVE', 'lv-2'),
      mockRequest('REGULARIZATION', 'reg-2'),
      mockRequest('LEAVE', 'lv-3'),
    ];
    const grouped = groupRequestsByType(requests);
    expect(grouped).toEqual([
      { type: 'LEAVE', count: 3 },
      { type: 'REGULARIZATION', count: 2 },
    ]);
  });
});

describe('TypeBadge badge text calculation', () => {
  function getBadgeText(type: 'LEAVE' | 'REGULARIZATION', count?: number): string {
    const label = TYPE_LABELS[type] ?? type;
    const suffix = count && count > 1 ? ` +${count - 1}` : '';
    return `${label}${suffix}`;
  }

  it('renders "Leave" when count is 1 or undefined', () => {
    expect(getBadgeText('LEAVE')).toBe('Leave');
    expect(getBadgeText('LEAVE', 1)).toBe('Leave');
  });

  it('renders "Leave +1" when count is 2', () => {
    expect(getBadgeText('LEAVE', 2)).toBe('Leave +1');
  });

  it('renders "Leave +2" when count is 3 (3 requests -> Leave +2)', () => {
    expect(getBadgeText('LEAVE', 3)).toBe('Leave +2');
  });

  it('renders "Attendance Reg." when count is 1', () => {
    expect(getBadgeText('REGULARIZATION', 1)).toBe('Attendance Reg.');
  });

  it('renders "Attendance Reg. +1" when count is 2', () => {
    expect(getBadgeText('REGULARIZATION', 2)).toBe('Attendance Reg. +1');
  });

  it('renders "Attendance Reg. +2" when count is 3', () => {
    expect(getBadgeText('REGULARIZATION', 3)).toBe('Attendance Reg. +2');
  });
});

describe('MyTeamPage viewMode and tab persistence on refresh', () => {
  function resolveInitialViewMode(isEmployee: boolean, savedStorageMode: string | null): 'direct' | 'peers' {
    if (isEmployee) return 'peers';
    if (savedStorageMode === 'peers' || savedStorageMode === 'direct') return savedStorageMode;
    return 'direct';
  }

  function resolveInitialTab(
    paramTab: string | null,
    savedStorageTab: string | null
  ): 'overview' | 'effort' | 'negligence' | 'penalties' | 'assignments' | 'reports' {
    const validTabs = ['overview', 'effort', 'negligence', 'penalties', 'assignments', 'reports'];
    if (paramTab && validTabs.includes(paramTab)) return paramTab as any;
    if (savedStorageTab && validTabs.includes(savedStorageTab)) return savedStorageTab as any;
    return 'overview';
  }

  it('employee always resolves to peers viewMode regardless of storage or defaults', () => {
    expect(resolveInitialViewMode(true, null)).toBe('peers');
    expect(resolveInitialViewMode(true, 'direct')).toBe('peers');
    expect(resolveInitialViewMode(true, 'peers')).toBe('peers');
  });

  it('manager or admin defaults to direct when no saved storage exists', () => {
    expect(resolveInitialViewMode(false, null)).toBe('direct');
  });

  it('manager or admin restores saved viewMode after page refresh', () => {
    expect(resolveInitialViewMode(false, 'peers')).toBe('peers');
    expect(resolveInitialViewMode(false, 'direct')).toBe('direct');
  });

  it('restores saved tab after page refresh', () => {
    expect(resolveInitialTab(null, 'effort')).toBe('effort');
    expect(resolveInitialTab(null, 'negligence')).toBe('negligence');
    expect(resolveInitialTab(null, 'penalties')).toBe('penalties');
    expect(resolveInitialTab(null, 'assignments')).toBe('assignments');
    expect(resolveInitialTab(null, 'reports')).toBe('reports');
    expect(resolveInitialTab(null, null)).toBe('overview');
  });

  it('prefers URL search parameter over stored tab if present', () => {
    expect(resolveInitialTab('effort', 'penalties')).toBe('effort');
    expect(resolveInitialTab('invalid', 'penalties')).toBe('penalties');
  });
});

describe('Overtime report no longer exports a fake OT start/end time', () => {
  const overtimeHeaders = REPORT_COLUMN_SETS.OVERTIME.map(c => c.header);

  it('does not include Start Time or End Time columns', () => {
    expect(overtimeHeaders).not.toContain('Start Time');
    expect(overtimeHeaders).not.toContain('End Time');
  });

  it('does not export any time-of-day value for a request with only a midnight-anchored placeholder span', () => {
    // requestedStart/requestedEnd are synthesized by the overtime request modal as a
    // midnight-anchored span sized to the entered duration (never a real clock time) —
    // see AttendancePage.tsx OvertimeRequestModal.handleSubmit.
    const row = {
      employeeUserId: 'u1', employeeCode: 'E1', fullName: 'Jane Doe', date: '2026-09-10',
      checkIn: '2026-09-10T00:00:00', checkOut: '2026-09-10T02:00:00',
      reason: 'Deployment support', status: 'APPROVED', requestMode: null, hours: 2,
    };
    const exported = Object.fromEntries(REPORT_COLUMN_SETS.OVERTIME.map(c => [c.header, c.exportValue(row)]));
    expect(Object.values(exported)).not.toContain('12:00 AM');
    expect(Object.values(exported)).not.toContain('2:00 AM');
    expect(exported).toEqual({
      Employee: 'Jane Doe',
      Date: '2026-09-10',
      'Overtime Hours': 2,
      Reason: 'Deployment support',
      Status: 'APPROVED',
    });
  });
});

describe('Excel export column auto-sizing', () => {
  function widthOf(ws: WorkSheet, index: number): number {
    const cols = ws['!cols'];
    if (!cols) throw new Error('expected !cols to be set');
    return (cols[index] as { wch: number }).wch;
  }

  it('sizes columns wider than their bare header when content is longer', () => {
    const headers = ['Employee Name', 'Date', 'Check In', 'Reason'];
    const rows = [
      { 'Employee Name': 'Alexandria Montgomery-Fitzgerald', Date: '2026-09-10', 'Check In': '9:02 AM', Reason: 'x' },
      { 'Employee Name': 'Jo Lee', Date: '2026-09-11', 'Check In': '9:15 AM', Reason: 'y' },
    ];
    const ws = {} as WorkSheet;
    autoSizeColumns(ws, rows, headers);

    expect(widthOf(ws, 0)).toBeGreaterThan('Employee Name'.length);
    expect(widthOf(ws, 1)).toBeGreaterThanOrEqual('Date'.length);
    expect(widthOf(ws, 2)).toBeGreaterThanOrEqual('Check In'.length);
  });

  it('caps width for unusually long free-text fields instead of growing unbounded', () => {
    const longReason = 'R'.repeat(500);
    const ws = {} as WorkSheet;
    autoSizeColumns(ws, [{ Reason: longReason }], ['Reason']);
    expect(widthOf(ws, 0)).toBeLessThanOrEqual(50);
  });

  it('still gives short columns a readable minimum width', () => {
    const ws = {} as WorkSheet;
    autoSizeColumns(ws, [{ Id: '1' }], ['Id']);
    expect(widthOf(ws, 0)).toBeGreaterThanOrEqual(8);
  });
});

