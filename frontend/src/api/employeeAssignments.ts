import { API_ORIGIN } from './config';
const BASE = `${API_ORIGIN}/api`;

function authHeaders(token: string) {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` };
}

async function handle<T>(res: Response): Promise<T> {
  let body: { message?: string } = {};
  try { body = await res.json(); } catch { /* non-json */ }
  if (!res.ok) throw new Error(body.message ?? `Request failed (${res.status})`);
  return body as T;
}

export interface EmployeeAssignmentRow {
  employeeUserId: string;
  employeeCode: string;
  fullName: string;
  departmentName: string | null;
  locationName: string | null;
  shiftId: string | null;
  shiftName: string | null;
  weeklyOffPolicyId: string | null;
  weeklyOffPolicyName: string | null;
  penalisationPolicyId: string | null;
  penalisationPolicyName: string | null;
}

export interface PolicyOption {
  id: string;
  name: string;
}

export interface AssignmentLookups {
  shifts: PolicyOption[];
  weeklyOffPolicies: PolicyOption[];
  penalisationPolicies: PolicyOption[];
  departments: string[];
  locations: string[];
}

export interface AssignmentBulkResult {
  succeededIds: string[];
  failed: { employeeUserId: string; reason: string }[];
}

export interface ImportRowResult {
  row: number;
  employeeCode: string;
  success: boolean;
  error: string | null;
}

export interface ImportResult {
  totalRows: number;
  succeeded: number;
  failed: number;
  results: ImportRowResult[];
}

export interface AssignmentFilters {
  shiftId?: string;
  weeklyOffPolicyId?: string;
  penalisationPolicyId?: string;
  department?: string;
  location?: string;
  search?: string;
}

export const employeeAssignmentsApi = {
  team: (filters: AssignmentFilters, token: string) => {
    const params = new URLSearchParams();
    if (filters.shiftId) params.set('shiftId', filters.shiftId);
    if (filters.weeklyOffPolicyId) params.set('weeklyOffPolicyId', filters.weeklyOffPolicyId);
    if (filters.penalisationPolicyId) params.set('penalisationPolicyId', filters.penalisationPolicyId);
    if (filters.department) params.set('department', filters.department);
    if (filters.location) params.set('location', filters.location);
    if (filters.search) params.set('search', filters.search);
    const qs = params.toString();
    return fetch(`${BASE}/employee-assignments/team${qs ? `?${qs}` : ''}`, { headers: authHeaders(token) })
      .then(r => handle<EmployeeAssignmentRow[]>(r));
  },

  lookups: (token: string) =>
    fetch(`${BASE}/employee-assignments/lookups`, { headers: authHeaders(token) })
      .then(r => handle<AssignmentLookups>(r)),

  bulkUpdateShift: (employeeUserIds: string[], policyId: string, token: string) =>
    fetch(`${BASE}/employee-assignments/bulk-update-shift`, {
      method: 'POST', headers: authHeaders(token), body: JSON.stringify({ employeeUserIds, policyId }),
    }).then(r => handle<AssignmentBulkResult>(r)),

  bulkUpdateWeeklyOff: (employeeUserIds: string[], policyId: string, token: string) =>
    fetch(`${BASE}/employee-assignments/bulk-update-weekly-off`, {
      method: 'POST', headers: authHeaders(token), body: JSON.stringify({ employeeUserIds, policyId }),
    }).then(r => handle<AssignmentBulkResult>(r)),

  bulkUpdatePenalisationPolicy: (employeeUserIds: string[], policyId: string, token: string) =>
    fetch(`${BASE}/employee-assignments/bulk-update-penalisation-policy`, {
      method: 'POST', headers: authHeaders(token), body: JSON.stringify({ employeeUserIds, policyId }),
    }).then(r => handle<AssignmentBulkResult>(r)),

  // Multipart — no Content-Type header, the browser sets the boundary for us.
  import: (file: File, token: string) => {
    const form = new FormData();
    form.append('file', file);
    return fetch(`${BASE}/employee-assignments/import`, {
      method: 'POST', headers: { Authorization: `Bearer ${token}` }, body: form,
    }).then(r => handle<ImportResult>(r));
  },
};
