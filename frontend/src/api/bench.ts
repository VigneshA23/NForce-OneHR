import { API_ORIGIN } from './config';

function authHeaders(token: string) {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` };
}

async function handle<T>(res: Response): Promise<T> {
  if (res.status === 204) return undefined as T;
  let body: { message?: string } = {};
  try { body = await res.json(); } catch { /* non-json */ }
  if (!res.ok) throw new Error(body.message ?? `Request failed (${res.status})`);
  return body as T;
}

function send<T>(token: string, path: string, method: string, body?: unknown): Promise<T> {
  return fetch(`${API_ORIGIN}${path}`, {
    method,
    headers: authHeaders(token),
    body: body === undefined ? undefined : JSON.stringify(body),
  }).then(handle<T>);
}

// ── Types (mirrors backend dto/bench/*) ────────────────────────────────────────

export type BenchReasonCode = 'ROLE_ENDED' | 'PROJECT_ENDED' | 'NEW_JOINER_UNALLOCATED' | 'AWAITING_ONBOARDING';
export type BenchCloseReason = 'RETURNED_TO_ACTIVE' | 'RESIGNED' | 'OTHER';

export const BENCH_REASON_LABELS: Record<BenchReasonCode, string> = {
  ROLE_ENDED: 'Role ended',
  PROJECT_ENDED: 'Project ended',
  NEW_JOINER_UNALLOCATED: 'New joiner unallocated',
  AWAITING_ONBOARDING: 'Awaiting onboarding',
};

export const CLOSE_REASON_LABELS: Record<BenchCloseReason, string> = {
  RETURNED_TO_ACTIVE: 'Returned to active work',
  RESIGNED: 'Resigned',
  OTHER: 'Other',
};

export interface BenchStatusRecord {
  id: string;
  employeeId: string;
  employeeName: string;
  reasonCode: BenchReasonCode;
  startDate: string;
  endDate: string | null;
  active: boolean;
  agingBucket: string | null;
  closeReason: BenchCloseReason | null;
  allocationId: string | null;
}

export interface TeamBenchRecord {
  employeeId: string;
  employeeName: string;
  reasonCode: BenchReasonCode;
  startDate: string;
  daysOnBench: number;
  lastProject: string | null;
}

export interface BenchListRow {
  id: string;
  employeeId: string;
  employeeName: string;
  reasonCode: BenchReasonCode;
  startDate: string;
  daysOnBench: number;
  agingBucket: string | null;
  matchScore: number;
  skills: string[];
}

export interface BenchDashboard {
  benchedCount: number;
  totalHeadcount: number;
  benchPercent: number;
  buckets: { bucket: string; count: number }[];
  trend: { date: string; benchedCount: number; totalHeadcount: number }[];
}

export interface BucketInput { label: string; minDays: number; maxDays: number | null }
export interface AgingConfig { buckets: BucketInput[]; notifyThresholdDays: number }

export interface BenchCost {
  rows: { bandLabel: string; benchedCount: number; avgCtc: number; totalCost: number }[];
  totalCost: number;
  unbandedCount: number;
}

export interface AllocationOption {
  id: string;
  projectName: string;
  capacityPercent: number;
  startDate: string;
  endDate: string;
}

export interface EndBenchPayload {
  allocationId?: string;
  closeReason?: BenchCloseReason;
  closeNote?: string;
}

export interface CompensationBand { id: string; bandLabel: string; avgCtc: number }
export interface BandAssignment { employeeId: string; employeeName: string; bandId: string; bandLabel: string }

export const benchApi = {
  start: (token: string, payload: { employeeId: string; reasonCode: BenchReasonCode; startDate?: string }) =>
    send<BenchStatusRecord>(token, '/api/bench-status', 'POST', payload),

  end: (token: string, id: string, payload: EndBenchPayload) =>
    send<BenchStatusRecord>(token, `/api/bench-status/${id}/end`, 'PATCH', payload),

  team: (token: string) => send<TeamBenchRecord[]>(token, '/api/bench-status/team', 'GET'),

  list: (token: string, filter: { bucket?: string; skillIds?: string[]; mode?: 'AND' | 'OR' }) => {
    const q = new URLSearchParams();
    if (filter.bucket) q.set('bucket', filter.bucket);
    (filter.skillIds ?? []).forEach(id => q.append('skillIds', id));
    if (filter.mode) q.set('mode', filter.mode);
    const qs = q.toString();
    return send<BenchListRow[]>(token, `/api/bench-status${qs ? `?${qs}` : ''}`, 'GET');
  },

  dashboard: (token: string) => send<BenchDashboard>(token, '/api/bench-status/dashboard', 'GET'),
  cost: (token: string) => send<BenchCost>(token, '/api/bench-status/cost', 'GET'),
  allocationOptions: (token: string, id: string) =>
    send<AllocationOption[]>(token, `/api/bench-status/${id}/allocations`, 'GET'),

  agingConfig: (token: string) => send<AgingConfig>(token, '/api/bench-status/aging-config', 'GET'),
  saveAgingConfig: (token: string, cfg: AgingConfig) =>
    send<AgingConfig>(token, '/api/bench-status/aging-config', 'PUT', cfg),

  bands: (token: string) => send<CompensationBand[]>(token, '/api/compensation-bands', 'GET'),
  addBand: (token: string, bandLabel: string, avgCtc: number) =>
    send<CompensationBand>(token, '/api/compensation-bands', 'POST', { bandLabel, avgCtc }),
  bandAssignments: (token: string) => send<BandAssignment[]>(token, '/api/compensation-bands/assignments', 'GET'),
  assignBand: (token: string, employeeId: string, bandId: string | null) =>
    send<void>(token, '/api/compensation-bands/assignments', 'PUT', { employeeId, bandId }),
};
