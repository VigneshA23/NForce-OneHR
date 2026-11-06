import { API_ORIGIN } from './config';

function authHeaders(token: string) {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` };
}

async function handle<T>(res: Response): Promise<T> {
  let body: { message?: string } = {};
  try { body = await res.json(); } catch { /* non-json */ }
  if (!res.ok) throw new Error(body.message ?? `Request failed (${res.status})`);
  return body as T;
}

// ── Types (mirrors backend dto/allocation/*) ───────────────────────────────────

export type ProjectStatus = 'ACTIVE' | 'ON_HOLD' | 'CLOSED';

export const PROJECT_STATUS_LABEL: Record<ProjectStatus, string> = {
  ACTIVE: 'Active',
  ON_HOLD: 'On Hold',
  CLOSED: 'Closed',
};

export interface ProjectRecord {
  id: string;
  code: string;
  name: string;
  startDate: string;
  endDate: string | null;
  ownerEmployeeId: string | null;
  ownerName: string | null;
  departmentId: string | null;
  departmentName: string | null;
  clientId: string | null;
  clientName: string | null;
  status: ProjectStatus;
}

/** US-B01/B02/B03 create + update body. There is no delete — a project is closed by setting status CLOSED. */
export interface ProjectPayload {
  code: string;
  name: string;
  startDate: string;
  endDate: string | null;
  ownerEmployeeId: string | null;
  departmentId: string | null;
  clientId: string | null;
  status: ProjectStatus;
}

/** US-B02: a plain client / cost-center name — no billing data. */
export interface ClientRecord {
  id: string;
  name: string;
}

export interface BulkAllocationPayload {
  employeeIds: string[];
  projectId: string;
  capacityPercent: number;
  startDate: string;
  endDate: string;
}

export interface AllocationResult {
  employeeId: string;
  status: 'SUCCESS' | 'FAILED';
  reason?: string;
}

export interface BulkAllocationResponse {
  results: AllocationResult[];
}

export const projectsApi = {
  list: (token: string): Promise<ProjectRecord[]> =>
    fetch(`${API_ORIGIN}/api/projects`, { headers: authHeaders(token) }).then(handle<ProjectRecord[]>),

  create: (token: string, payload: ProjectPayload): Promise<ProjectRecord> =>
    fetch(`${API_ORIGIN}/api/projects`, { method: 'POST', headers: authHeaders(token), body: JSON.stringify(payload) })
      .then(handle<ProjectRecord>),

  update: (token: string, id: string, payload: ProjectPayload): Promise<ProjectRecord> =>
    fetch(`${API_ORIGIN}/api/projects/${id}`, { method: 'PUT', headers: authHeaders(token), body: JSON.stringify(payload) })
      .then(handle<ProjectRecord>),

  listClients: (token: string): Promise<ClientRecord[]> =>
    fetch(`${API_ORIGIN}/api/projects/clients`, { headers: authHeaders(token) }).then(handle<ClientRecord[]>),

  createClient: (token: string, name: string): Promise<ClientRecord> =>
    fetch(`${API_ORIGIN}/api/projects/clients`, { method: 'POST', headers: authHeaders(token), body: JSON.stringify({ name }) })
      .then(handle<ClientRecord>),
};

export const projectAllocationsApi = {
  bulkAllocate: (token: string, payload: BulkAllocationPayload): Promise<BulkAllocationResponse> =>
    fetch(`${API_ORIGIN}/api/allocations/bulk`, { method: 'POST', headers: authHeaders(token), body: JSON.stringify(payload) })
      .then(handle<BulkAllocationResponse>),
};
