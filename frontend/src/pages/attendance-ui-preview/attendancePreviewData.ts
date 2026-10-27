/**
 * MOCK DATA for /attendance-ui-preview only — never imported by the real (authenticated) flow
 * unless AttendancePage is rendered with `forcePreview`. Every record is invented locally; dates
 * are relative to today so the screens always look current. Used to show the approver-side
 * (Manager / HR Admin / Super Admin) lists and rosters without a backend.
 */
import type { AttendanceRecord, RegularizationRecord } from '../../api/attendance';
import type { AttendanceRequestRecord } from '../../api/attendanceRequests';
import type { OvertimeRequestRecord } from '../../api/overtimeRequests';

function isoAgo(n: number): string {
  const d = new Date();
  d.setDate(d.getDate() - n);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

const PEOPLE = [
  { id: 'u-rahul', name: 'Rahul Verma', code: 'NF-1042', email: 'rahul.verma@nforceone.com', dept: 'Engineering' },
  { id: 'u-meera', name: 'Meera Nair', code: 'NF-1187', email: 'meera.nair@nforceone.com', dept: 'Design' },
  { id: 'u-karthik', name: 'Karthik Reddy', code: 'NF-1203', email: 'karthik.reddy@nforceone.com', dept: 'Engineering' },
  { id: 'u-divya', name: 'Divya Iyer', code: 'NF-1311', email: 'divya.iyer@nforceone.com', dept: 'Finance' },
  { id: 'u-arjun', name: 'Arjun Mehta', code: 'NF-1342', email: 'arjun.mehta@nforceone.com', dept: 'Sales' },
  { id: 'u-sneha', name: 'Sneha Kulkarni', code: 'NF-1390', email: 'sneha.kulkarni@nforceone.com', dept: 'HR' },
  { id: 'u-vikram', name: 'Vikram Singh', code: 'NF-1415', email: 'vikram.singh@nforceone.com', dept: 'Engineering' },
  { id: 'u-pooja', name: 'Pooja Desai', code: 'NF-1456', email: 'pooja.desai@nforceone.com', dept: 'Operations' },
];
const APPROVER = { id: 'mock-manager', name: 'Priya Sharma' };

export function previewRegularizations(): { mine: RegularizationRecord[]; forApprover: RegularizationRecord[] } {
  const make = (i: number, p: typeof PEOPLE[number], ago: number, status: RegularizationRecord['status'], reason: string, extra: Partial<RegularizationRecord> = {}): RegularizationRecord => {
    const iso = isoAgo(ago);
    return {
      id: `mock-reg-${i}`, employeeUserId: p.id, employeeName: p.name, employeeEmail: p.email, departmentName: p.dept,
      attendanceDate: iso, requestedCheckIn: `${iso}T09:00:00`, requestedCheckOut: `${iso}T18:00:00`,
      reason, status, assignedApproverId: APPROVER.id, assignedApproverName: APPROVER.name,
      totalMinutes: 540, reviewedByName: null, reviewedAt: null, reviewComment: null,
      approvedByName: null, approvedAt: null, finalApprovedByName: null, finalApprovedAt: null,
      createdAt: `${isoAgo(ago - 1)}T08:30:00+05:30`, approvalHistory: [],
      ...extra,
    };
  };
  const approvedExtra = { reviewedByName: APPROVER.name, reviewedAt: `${isoAgo(1)}T11:00:00+05:30`, reviewComment: 'Approved', approvedByName: APPROVER.name, approvedAt: `${isoAgo(1)}T11:00:00+05:30`, finalApprovedByName: APPROVER.name, finalApprovedAt: `${isoAgo(1)}T11:00:00+05:30` };
  const mine = [
    make(1, { id: 'mock-me', name: 'Preview User', code: 'EMP001', email: 'preview@nforceone.com', dept: 'Engineering' }, 9, 'APPROVED', 'Forgot to check in', approvedExtra),
    make(2, { id: 'mock-me', name: 'Preview User', code: 'EMP001', email: 'preview@nforceone.com', dept: 'Engineering' }, 3, 'PENDING', 'Biometric was offline in the morning'),
  ];
  const forApprover = [
    make(3, PEOPLE[0], 2, 'PENDING', 'Missed the evening check-out, left at 6 PM'),
    make(4, PEOPLE[1], 3, 'PENDING', 'Network outage - could not punch in'),
    make(5, PEOPLE[2], 5, 'APPROVED', 'Client site visit, no access to the portal', approvedExtra),
    make(6, PEOPLE[3], 6, 'REJECTED', 'Forgot to punch', { reviewedByName: APPROVER.name, reviewedAt: `${isoAgo(5)}T10:00:00+05:30`, reviewComment: 'Please attach proof' }),
  ];
  return { mine, forApprover };
}

export function previewAttendanceRequests(): { mine: AttendanceRequestRecord[]; pending: AttendanceRequestRecord[] } {
  const make = (i: number, p: { id: string; name: string; email: string; dept: string }, ago: number, type: 'WFH' | 'PARTIAL_DAY', status: AttendanceRequestRecord['status'], reason: string, extra: Partial<AttendanceRequestRecord> = {}): AttendanceRequestRecord => {
    const iso = isoAgo(ago);
    return {
      id: `mock-ar-${i}`, employeeUserId: p.id, employeeName: p.name, employeeEmail: p.email, departmentName: p.dept,
      requestType: type, requestDate: iso, partialDayHours: type === 'PARTIAL_DAY' ? 2 : null, partialDayMode: type === 'PARTIAL_DAY' ? 'LATE_ARRIVE' : null,
      wfhDayFraction: type === 'WFH' ? 1 : null, reason, status,
      assignedApproverId: APPROVER.id, assignedApproverName: APPROVER.name, notifyUserId: null, notifyUserName: null,
      reviewedByName: null, reviewedAt: null, reviewComment: null, createdAt: `${isoAgo(ago + 1)}T08:00:00+05:30`,
      ...extra,
    };
  };
  const me = { id: 'mock-me', name: 'Preview User', email: 'preview@nforceone.com', dept: 'Engineering' };
  const mine = [
    make(1, me, 8, 'WFH', 'APPROVED', 'Working from home', { reviewedByName: APPROVER.name, reviewedAt: `${isoAgo(9)}T09:00:00+05:30` }),
    make(2, me, 2, 'PARTIAL_DAY', 'PENDING', 'Doctor appointment in the morning'),
  ];
  const pending = [
    make(3, { id: PEOPLE[2].id, name: PEOPLE[2].name, email: PEOPLE[2].email, dept: PEOPLE[2].dept }, -1, 'WFH', 'PENDING', 'Internet installation at home'),
    make(4, { id: PEOPLE[3].id, name: PEOPLE[3].name, email: PEOPLE[3].email, dept: PEOPLE[3].dept }, -2, 'PARTIAL_DAY', 'PENDING', 'Bank work'),
    make(5, { id: PEOPLE[4].id, name: PEOPLE[4].name, email: PEOPLE[4].email, dept: PEOPLE[4].dept }, 4, 'WFH', 'APPROVED', 'Child unwell', { reviewedByName: APPROVER.name, reviewedAt: `${isoAgo(5)}T09:30:00+05:30` }),
  ];
  return { mine, pending };
}

export function previewOvertimeRequests(): { mine: OvertimeRequestRecord[]; pending: OvertimeRequestRecord[] } {
  const make = (i: number, p: { id: string; name: string; email: string; dept: string }, ago: number, mins: number, status: OvertimeRequestRecord['status'], reason: string, extra: Partial<OvertimeRequestRecord> = {}): OvertimeRequestRecord => {
    const iso = isoAgo(ago);
    return {
      id: `mock-ot-${i}`, employeeUserId: p.id, employeeName: p.name, employeeEmail: p.email, departmentName: p.dept,
      workDate: iso, requestedStart: `${iso}T18:00:00`, requestedEnd: `${iso}T${String(18 + Math.floor(mins / 60)).padStart(2, '0')}:${String(mins % 60).padStart(2, '0')}:00`,
      requestedMinutes: mins, reason, status, assignedApproverId: APPROVER.id, assignedApproverName: APPROVER.name,
      notifyUserId: null, notifyUserName: null, reviewedByName: null, reviewedByRole: null, reviewedAt: null, reviewComment: null,
      createdAt: `${iso}T19:00:00+05:30`,
      ...extra,
    };
  };
  const me = { id: 'mock-me', name: 'Preview User', email: 'preview@nforceone.com', dept: 'Engineering' };
  const mine = [make(1, me, 12, 120, 'APPROVED', 'Release deployment', { reviewedByName: APPROVER.name, reviewedByRole: 'MANAGER', reviewedAt: `${isoAgo(11)}T10:00:00+05:30` })];
  const pending = [
    make(2, { id: PEOPLE[0].id, name: PEOPLE[0].name, email: PEOPLE[0].email, dept: PEOPLE[0].dept }, 1, 150, 'PENDING', 'Production hotfix'),
    make(3, { id: PEOPLE[6].id, name: PEOPLE[6].name, email: PEOPLE[6].email, dept: PEOPLE[6].dept }, 2, 90, 'PENDING', 'Quarter-end reconciliation support'),
    make(4, { id: PEOPLE[1].id, name: PEOPLE[1].name, email: PEOPLE[1].email, dept: PEOPLE[1].dept }, 7, 120, 'REJECTED', 'Design review prep', { reviewedByName: APPROVER.name, reviewedByRole: 'MANAGER', reviewedAt: `${isoAgo(6)}T10:00:00+05:30`, reviewComment: 'Not pre-approved' }),
  ];
  return { mine, pending };
}

/** Roster for "Team attendance" (first 5 people) / "Organization attendance" (all 8) on `date`. */
export function previewRoster(scope: 'team' | 'all', date: string): AttendanceRecord[] {
  const shape: { status: AttendanceRecord['status']; inAt: string | null; outAt: string | null; mins: number | null; late: number | null }[] = [
    { status: 'PRESENT', inAt: '09:02:00', outAt: '18:10:00', mins: 548, late: 0 },
    { status: 'LATE', inAt: '09:41:00', outAt: '18:20:00', mins: 519, late: 31 },
    { status: 'PRESENT', inAt: '08:55:00', outAt: '17:58:00', mins: 543, late: 0 },
    { status: 'HALF_DAY', inAt: '09:10:00', outAt: '13:20:00', mins: 250, late: 0 },
    { status: 'MISSING_CHECKOUT', inAt: '09:05:00', outAt: null, mins: null, late: 0 },
    { status: 'ABSENT', inAt: null, outAt: null, mins: null, late: null },
    { status: 'PRESENT', inAt: '09:00:00', outAt: '18:02:00', mins: 542, late: 0 },
    { status: 'LATE', inAt: '09:25:00', outAt: '18:30:00', mins: 545, late: 15 },
  ];
  const people = scope === 'team' ? PEOPLE.slice(0, 5) : PEOPLE;
  return people.map((p, i) => {
    const s = shape[i];
    return {
      id: `mock-roster-${i}`, employeeUserId: p.id, employeeCode: p.code, fullName: p.name, workDate: date,
      checkInAt: s.inAt ? `${date}T${s.inAt}` : null, checkOutAt: s.outAt ? `${date}T${s.outAt}` : null,
      sessionStartedAt: s.inAt ? `${date}T${s.inAt}` : null, workedMinutes: s.mins, status: s.status, lateByMinutes: s.late,
      source: 'SYSTEM', workMode: 'ONSITE', timezone: 'Asia/Kolkata',
      shiftStartAt: `${date}T09:00:00`, shiftEndAt: `${date}T18:00:00`, workdayStartAt: null, workdayEndAt: null, penalized: false,
    } as AttendanceRecord;
  });
}

/** Sample e-mails for the roster preview (the live page reads them from the People Directory API). */
export function previewRosterEmails(): Record<string, string> {
  const out: Record<string, string> = {};
  for (const p of PEOPLE) out[p.id] = `${p.name.toLowerCase().replace(/[^a-z ]/g, '').trim().split(/\s+/).join('.')}@nforceone.com`;
  return out;
}
