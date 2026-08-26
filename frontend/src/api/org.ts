import { API_ORIGIN } from './config';
const BASE = `${API_ORIGIN}/api/org`;

async function handle<T>(res: Response): Promise<T> {
  const body = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error((body as { message?: string }).message ?? `HTTP ${res.status}`);
  return body as T;
}

async function handleEmpty(res: Response): Promise<void> {
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new Error((body as { message?: string }).message ?? `HTTP ${res.status}`);
  }
}

function authHeaders(token: string) {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` };
}

export interface DepartmentRow {
  id: string; name: string; active: boolean; employeeCount: number; createdAt: string; updatedAt: string;
}
export interface DesignationRow {
  id: string; title: string; grade: string | null; level: string | null;
  active: boolean; employeeCount: number; createdAt: string; updatedAt: string;
}
export interface LocationRow {
  id: string; name: string; city: string | null; state: string | null;
  country: string | null; holidayRegion: string | null; timezone: string | null;
  active: boolean; employeeCount: number; createdAt: string; updatedAt: string;
}
export interface ShiftRow {
  id: string; name: string; code: string | null; description: string | null;
  startTime: string; endTime: string; flexible: boolean; breakMinutes: number | null;
  workingDays: string[]; active: boolean; employeeCount: number; createdAt: string;
}
export interface ShiftPayload {
  name: string; code?: string; description?: string;
  startTime: string; endTime: string; flexible: boolean;
  breakMinutes?: number; workingDays?: string[];
}
export interface ShiftEmployeeRow {
  userId: string; employeeCode: string; fullName: string; email: string; departmentName: string | null;
  active: boolean;
}

export const orgApi = {
  // Departments
  listDepartments: (token: string) =>
    fetch(`${BASE}/departments`, { headers: authHeaders(token) }).then(r => handle<DepartmentRow[]>(r)),

  createDepartment: (token: string, name: string) =>
    fetch(`${BASE}/departments`, { method: 'POST', headers: authHeaders(token), body: JSON.stringify({ name }) })
      .then(r => handle<DepartmentRow>(r)),

  updateDepartment: (token: string, id: string, name: string) =>
    fetch(`${BASE}/departments/${id}`, { method: 'PUT', headers: authHeaders(token), body: JSON.stringify({ name }) })
      .then(r => handle<DepartmentRow>(r)),

  toggleDepartmentActive: (token: string, id: string) =>
    fetch(`${BASE}/departments/${id}/toggle-active`, { method: 'PATCH', headers: authHeaders(token) })
      .then(r => handle<DepartmentRow>(r)),

  deleteDepartment: (token: string, id: string) =>
    fetch(`${BASE}/departments/${id}`, { method: 'DELETE', headers: authHeaders(token) })
      .then(r => handleEmpty(r)),

  // Designations
  listDesignations: (token: string) =>
    fetch(`${BASE}/designations`, { headers: authHeaders(token) }).then(r => handle<DesignationRow[]>(r)),

  createDesignation: (token: string, title: string, grade?: string, level?: string) =>
    fetch(`${BASE}/designations`, {
      method: 'POST', headers: authHeaders(token),
      body: JSON.stringify({ title, grade: grade || undefined, level: level || undefined }),
    }).then(r => handle<DesignationRow>(r)),

  updateDesignation: (token: string, id: string, payload: { title: string; grade?: string; level?: string }) =>
    fetch(`${BASE}/designations/${id}`, { method: 'PUT', headers: authHeaders(token), body: JSON.stringify(payload) })
      .then(r => handle<DesignationRow>(r)),

  toggleDesignationActive: (token: string, id: string) =>
    fetch(`${BASE}/designations/${id}/toggle-active`, { method: 'PATCH', headers: authHeaders(token) })
      .then(r => handle<DesignationRow>(r)),

  deleteDesignation: (token: string, id: string) =>
    fetch(`${BASE}/designations/${id}`, { method: 'DELETE', headers: authHeaders(token) })
      .then(r => handleEmpty(r)),

  // Locations
  listLocations: (token: string) =>
    fetch(`${BASE}/locations`, { headers: authHeaders(token) }).then(r => handle<LocationRow[]>(r)),

  createLocation: (token: string, payload: { name: string; city?: string; state?: string; country?: string; holidayRegion?: string; timezone?: string }) =>
    fetch(`${BASE}/locations`, { method: 'POST', headers: authHeaders(token), body: JSON.stringify(payload) })
      .then(r => handle<LocationRow>(r)),

  updateLocation: (token: string, id: string, payload: { name: string; city?: string; state?: string; country?: string; holidayRegion?: string; timezone?: string }) =>
    fetch(`${BASE}/locations/${id}`, { method: 'PUT', headers: authHeaders(token), body: JSON.stringify(payload) })
      .then(r => handle<LocationRow>(r)),

  toggleLocationActive: (token: string, id: string) =>
    fetch(`${BASE}/locations/${id}/toggle-active`, { method: 'PATCH', headers: authHeaders(token) })
      .then(r => handle<LocationRow>(r)),

  deleteLocation: (token: string, id: string) =>
    fetch(`${BASE}/locations/${id}`, { method: 'DELETE', headers: authHeaders(token) })
      .then(r => handleEmpty(r)),

  // Shifts — create/edit/delete are Super Admin only, enforced by the backend regardless of
  // who can reach this UI (see OrgService). Listing itself is open to any authenticated caller.
  listShifts: (token: string) =>
    fetch(`${BASE}/shifts`, { headers: authHeaders(token) }).then(r => handle<ShiftRow[]>(r)),

  listShiftEmployees: (token: string, shiftId: string) =>
    fetch(`${BASE}/shifts/${shiftId}/employees`, { headers: authHeaders(token) }).then(r => handle<ShiftEmployeeRow[]>(r)),

  createShift: (token: string, payload: ShiftPayload) =>
    fetch(`${BASE}/shifts`, { method: 'POST', headers: authHeaders(token), body: JSON.stringify(payload) })
      .then(r => handle<ShiftRow>(r)),

  updateShift: (token: string, id: string, payload: ShiftPayload) =>
    fetch(`${BASE}/shifts/${id}`, { method: 'PUT', headers: authHeaders(token), body: JSON.stringify(payload) })
      .then(r => handle<ShiftRow>(r)),

  toggleShiftActive: (token: string, id: string) =>
    fetch(`${BASE}/shifts/${id}/toggle-active`, { method: 'PATCH', headers: authHeaders(token) })
      .then(r => handle<ShiftRow>(r)),

  deleteShift: (token: string, id: string) =>
    fetch(`${BASE}/shifts/${id}`, { method: 'DELETE', headers: authHeaders(token) })
      .then(r => handleEmpty(r)),
};
