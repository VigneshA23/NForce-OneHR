const BASE = '/api';

function authHeaders(token: string) {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` };
}

async function handle<T>(res: Response): Promise<T> {
  let body: { message?: string } = {};
  try { body = await res.json(); } catch { /* non-json */ }
  if (!res.ok) throw new Error((body as any).message ?? `Request failed (${res.status})`);
  return body as T;
}

export interface ManagerRef { userId: string; fullName: string; email: string; }

export interface EmployeeRecord {
  userId: string;
  employeeCode: string;
  fullName: string;
  email: string;
  role: string;
  departmentId: string | null;
  departmentName: string | null;
  designationId: string | null;
  designationName: string | null;
  locationId: string | null;
  locationName: string | null;
  employmentType: string;
  workMode: string;
  joiningDate: string;
  active: boolean;
  currentManager: ManagerRef | null;
  tempPassword?: string;
}

export interface CreateEmployeePayload {
  fullName: string;
  email: string;
  employeeCode?: string;
  departmentId?: string;
  designationId?: string;
  locationId?: string;
  employmentType?: string;
  workMode?: string;
  joiningDate: string;
  managerId?: string;
}

export interface UpdateEmployeePayload {
  fullName?: string;
  departmentId?: string;
  designationId?: string;
  locationId?: string;
  employmentType?: string;
  workMode?: string;
}

export interface CreateUserPayload extends CreateEmployeePayload {
  role: string;
}

export interface UpdateUserPayload {
  fullName?: string;
  role?: string;
  departmentId?: string;
  designationId?: string;
  locationId?: string;
  employmentType?: string;
  workMode?: string;
  managerId?: string;
}

export interface ResetPasswordResult { tempPassword: string; message: string; }

export const employeesApi = {
  list: (token: string) =>
    fetch(`${BASE}/employees`, { headers: authHeaders(token) }).then(handle<EmployeeRecord[]>),

  create: (payload: CreateEmployeePayload, token: string) =>
    fetch(`${BASE}/employees`, { method: 'POST', headers: authHeaders(token), body: JSON.stringify(payload) }).then(handle<EmployeeRecord>),

  update: (userId: string, payload: UpdateEmployeePayload, token: string) =>
    fetch(`${BASE}/employees/${userId}`, { method: 'PATCH', headers: authHeaders(token), body: JSON.stringify(payload) }).then(handle<EmployeeRecord>),

  potentialManagers: (token: string) =>
    fetch(`${BASE}/employees/potential-managers`, { headers: authHeaders(token) }).then(handle<EmployeeRecord[]>),
};

export const usersApi = {
  list: (token: string) =>
    fetch(`${BASE}/users`, { headers: authHeaders(token) }).then(handle<EmployeeRecord[]>),

  create: (payload: CreateUserPayload, token: string) =>
    fetch(`${BASE}/users`, { method: 'POST', headers: authHeaders(token), body: JSON.stringify(payload) }).then(handle<EmployeeRecord>),

  update: (userId: string, payload: UpdateUserPayload, token: string) =>
    fetch(`${BASE}/users/${userId}`, { method: 'PATCH', headers: authHeaders(token), body: JSON.stringify(payload) }).then(handle<EmployeeRecord>),

  resetPassword: (userId: string, token: string) =>
    fetch(`${BASE}/users/${userId}/reset-password`, { method: 'POST', headers: authHeaders(token) }).then(handle<ResetPasswordResult>),

  setStatus: (userId: string, active: boolean, token: string) =>
    fetch(`${BASE}/users/${userId}/status`, { method: 'PATCH', headers: authHeaders(token), body: JSON.stringify({ active }) }).then(handle<EmployeeRecord>),
};
