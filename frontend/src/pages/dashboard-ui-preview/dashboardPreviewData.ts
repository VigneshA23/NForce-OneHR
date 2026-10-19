/**
 * MOCK DATA for /dashboard-ui-preview only — every record is invented locally and dated relative to
 * today. Consumed only when PreviewModeContext is true (see lib/previewMode.tsx).
 */
import type { AttendanceConfig, AttendanceRecord, AttendanceStats, TodayAttendance } from '../../api/attendance';
import type { LeaveBalance } from '../../api/leave';
import type { MyRequestItem } from '../../api/myRequests';
import type { HolidayRow } from '../../api/holidays';
import type { BirthdayEntry } from '../../api/birthdays';
import type { NotificationItem } from '../../api/notifications';

const pad = (n: number) => String(n).padStart(2, '0');
export function isoAgo(n: number): string {
  const d = new Date();
  d.setDate(d.getDate() - n);
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}
const todayIso = () => isoAgo(0);

export const PREVIEW_CONFIG: AttendanceConfig = {
  shiftName: 'General Shift', shiftStart: '09:00:00', shiftEnd: '18:00:00',
  lateGraceMinutes: 10, halfDayMaxHours: 4, weeklyOffDays: ['SATURDAY', 'SUNDAY'],
};

export const PREVIEW_STATS: AttendanceStats = {
  me: { presentDays: 18, avgHoursPerDay: 8.4, onTimeArrivalPercent: 82, expectedHoursPerDay: 9 },
  team: { presentDays: 18, avgHoursPerDay: 7.9, onTimeArrivalPercent: 74, expectedHoursPerDay: 9 },
  teamSize: 6,
};

export const PREVIEW_BALANCES: LeaveBalance[] = [
  { leaveTypeCode: 'CASUAL', leaveTypeName: 'Casual Leave', year: 2026, totalDays: 12, usedDays: 4, remainingDays: 8 },
  { leaveTypeCode: 'SICK', leaveTypeName: 'Sick Leave', year: 2026, totalDays: 10, usedDays: 2, remainingDays: 8 },
  { leaveTypeCode: 'EARNED', leaveTypeName: 'Earned Leave', year: 2026, totalDays: 15, usedDays: 3, remainingDays: 12 },
];

export function previewRecord(iso: string, o: Partial<AttendanceRecord> = {}): AttendanceRecord {
  return {
    id: `pv-${iso}`, employeeUserId: 'pv-me', employeeCode: 'EMP001', fullName: 'Preview User', workDate: iso,
    checkInAt: `${iso}T09:02:00`, checkOutAt: `${iso}T18:08:00`, sessionStartedAt: `${iso}T09:02:00`, workedMinutes: 546,
    status: 'PRESENT', lateByMinutes: 0, source: 'SYSTEM', workMode: 'ONSITE', timezone: 'Asia/Kolkata',
    shiftStartAt: `${iso}T09:00:00`, shiftEndAt: `${iso}T18:00:00`, workdayStartAt: null, workdayEndAt: null, penalized: false,
    ...o,
  };
}

/** A month of mock history — mostly present, a couple of late days, one absent, one missing check-out. */
export function previewMonthHistory(from: string, to: string): AttendanceRecord[] {
  const out: AttendanceRecord[] = [];
  const end = new Date(to + 'T12:00:00');
  const today = todayIso();
  for (let d = new Date(from + 'T12:00:00'); d <= end; d.setDate(d.getDate() + 1)) {
    const iso = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
    const dow = d.getDay();
    if (iso > today || dow === 0 || dow === 6) continue;
    const day = d.getDate();
    if (iso === today) out.push(previewRecord(iso, { checkOutAt: null, workedMinutes: null }));
    else if (day % 11 === 0) out.push(previewRecord(iso, { checkInAt: null, checkOutAt: null, sessionStartedAt: null, workedMinutes: null, status: 'ABSENT', lateByMinutes: null }));
    else if (day % 7 === 3) out.push(previewRecord(iso, { checkInAt: `${iso}T09:41:00`, status: 'LATE', lateByMinutes: 31, workedMinutes: 519 }));
    else if (day % 9 === 4) out.push(previewRecord(iso, { checkOutAt: null, status: 'MISSING_CHECKOUT', workedMinutes: null }));
    else out.push(previewRecord(iso));
  }
  return out;
}

export function previewToday(): TodayAttendance {
  const iso = todayIso();
  const now = new Date();
  return {
    workDate: iso,
    serverNow: `${iso}T${pad(now.getHours())}:${pad(now.getMinutes())}:00`,
    canCheckIn: false, canCheckOut: true,
    record: previewRecord(iso, { checkInAt: `${iso}T09:05:00`, sessionStartedAt: `${iso}T09:05:00`, checkOutAt: null, workedMinutes: null }),
    breakUsedMinutes: 15,
  };
}

export const previewHolidays = (): HolidayRow[] => [
  { id: 'h1', holidayName: 'Vijayadashami', holidayDate: todayIso(), locationId: 'l', locationName: 'Head Office', active: true },
  { id: 'h2', holidayName: 'Diwali', holidayDate: isoAgo(-9), locationId: 'l', locationName: 'Head Office', active: true },
  { id: 'h3', holidayName: 'Guru Nanak Jayanti', holidayDate: isoAgo(-30), locationId: 'l', locationName: 'Head Office', active: true },
];

export const previewBirthdays = (): BirthdayEntry[] => {
  const d = (n: number) => { const x = new Date(); x.setDate(x.getDate() + n); return x; };
  return [
    { userId: 'b1', fullName: 'Meera Nair', departmentName: 'Design', designationName: 'Senior Designer', birthdayMonth: d(0).getMonth() + 1, birthdayDay: d(0).getDate(), daysUntil: 0, today: true },
    { userId: 'b2', fullName: 'Karthik Reddy', departmentName: 'Engineering', designationName: 'Tech Lead', birthdayMonth: d(3).getMonth() + 1, birthdayDay: d(3).getDate(), daysUntil: 3, today: false },
    { userId: 'b3', fullName: 'Divya Iyer', departmentName: 'Finance', designationName: 'Analyst', birthdayMonth: d(5).getMonth() + 1, birthdayDay: d(5).getDate(), daysUntil: 5, today: false },
  ];
};

export const previewNotifications = (): NotificationItem[] => [
  { id: 1, type: 'APPROVAL', title: 'Leave approved', message: 'Your Casual Leave for Oct 9 was approved by Priya Sharma.', linkPath: null, read: false, priority: 'NORMAL', createdAt: new Date().toISOString() },
  { id: 2, type: 'ATTENDANCE', title: 'Missing check-out', message: 'You did not check out on Sep 25. Raise a regularization.', linkPath: null, read: false, priority: 'HIGH', createdAt: new Date().toISOString() },
  { id: 3, type: 'POLICY', title: 'New policy published', message: 'Hybrid Work Policy v2 is ready to read.', linkPath: null, read: false, priority: 'NORMAL', createdAt: new Date().toISOString() },
];

export const previewRequests = (): MyRequestItem[] => [
  { id: 'r1', requestType: 'LEAVE', employeeUserId: 'pv-me', employeeName: 'Preview User', createdAt: `${isoAgo(1)}T10:00:00`, status: 'APPROVED', leaveTypeName: 'Casual Leave', leaveStartDate: isoAgo(-3), leaveEndDate: isoAgo(-3), leaveTotalDays: 1, decidedByName: 'Priya Sharma', decidedAt: `${isoAgo(0)}T09:30:00` },
  { id: 'r2', requestType: 'REGULARIZATION', employeeUserId: 'pv-me', employeeName: 'Preview User', createdAt: `${isoAgo(3)}T09:00:00`, status: 'PENDING', attendanceDate: isoAgo(11) },
  { id: 'r3', requestType: 'WFH', employeeUserId: 'pv-me', employeeName: 'Preview User', createdAt: `${isoAgo(6)}T09:00:00`, status: 'APPROVED', attendanceDate: isoAgo(-1), decidedByName: 'Priya Sharma', decidedAt: `${isoAgo(5)}T11:00:00` },
  { id: 'r4', requestType: 'OVERTIME', employeeUserId: 'pv-me', employeeName: 'Preview User', createdAt: `${isoAgo(9)}T19:00:00`, status: 'REJECTED', attendanceDate: isoAgo(10), decisionReason: 'Not pre-approved', decidedByName: 'Priya Sharma', decidedAt: `${isoAgo(8)}T10:00:00` },
] as MyRequestItem[];
