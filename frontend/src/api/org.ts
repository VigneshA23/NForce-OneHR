const BASE = '/api/org';

async function handle<T>(res: Response): Promise<T> {
  const body = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error((body as { message?: string }).message ?? `HTTP ${res.status}`);
  return body as T;
}

function authHeaders(token: string) {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` };
}

export interface DepartmentRow  { id: string; name: string; active: boolean; createdAt: string }
export interface DesignationRow { id: string; title: string; grade: string | null; active: boolean; createdAt: string }
export interface LocationRow    { id: string; name: string; city: string | null; state: string | null; country: string | null; active: boolean; createdAt: string }

export const orgApi = {
  // Departments
  listDepartments: (token: string) =>
    fetch(`${BASE}/departments`, { headers: authHeaders(token) }).then(r => handle<DepartmentRow[]>(r)),

  createDepartment: (token: string, name: string) =>
    fetch(`${BASE}/departments`, {
      method: 'POST',
      headers: authHeaders(token),
      body: JSON.stringify({ name }),
    }).then(r => handle<DepartmentRow>(r)),

  // Designations
  listDesignations: (token: string) =>
    fetch(`${BASE}/designations`, { headers: authHeaders(token) }).then(r => handle<DesignationRow[]>(r)),

  createDesignation: (token: string, title: string, grade?: string) =>
    fetch(`${BASE}/designations`, {
      method: 'POST',
      headers: authHeaders(token),
      body: JSON.stringify({ title, grade: grade || undefined }),
    }).then(r => handle<DesignationRow>(r)),

  // Locations
  listLocations: (token: string) =>
    fetch(`${BASE}/locations`, { headers: authHeaders(token) }).then(r => handle<LocationRow[]>(r)),

  createLocation: (token: string, payload: { name: string; city?: string; state?: string; country?: string }) =>
    fetch(`${BASE}/locations`, {
      method: 'POST',
      headers: authHeaders(token),
      body: JSON.stringify(payload),
    }).then(r => handle<LocationRow>(r)),
};
