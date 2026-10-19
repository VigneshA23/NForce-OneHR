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

export interface ProjectRecord {
  id: string;
  name: string;
  active: boolean;
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

  create: (token: string, name: string): Promise<ProjectRecord> =>
    fetch(`${API_ORIGIN}/api/projects`, { method: 'POST', headers: authHeaders(token), body: JSON.stringify({ name }) })
      .then(handle<ProjectRecord>),
};

export const projectAllocationsApi = {
  bulkAllocate: (token: string, payload: BulkAllocationPayload): Promise<BulkAllocationResponse> =>
    fetch(`${API_ORIGIN}/api/allocations/bulk`, { method: 'POST', headers: authHeaders(token), body: JSON.stringify(payload) })
      .then(handle<BulkAllocationResponse>),
};
