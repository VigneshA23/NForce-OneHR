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

// ── Types (mirrors backend dto/bench/*) ────────────────────────────────────────

export type BenchReasonCode = 'ROLE_ENDED' | 'PROJECT_ENDED' | 'NEW_JOINER_UNALLOCATED' | 'AWAITING_ONBOARDING';

export const BENCH_REASON_LABELS: Record<BenchReasonCode, string> = {
  ROLE_ENDED: 'Role ended',
  PROJECT_ENDED: 'Project ended',
  NEW_JOINER_UNALLOCATED: 'New joiner unallocated',
  AWAITING_ONBOARDING: 'Awaiting onboarding',
};

export interface BenchStatusRecord {
  id: string;
  employeeId: string;
  employeeName: string;
  reasonCode: BenchReasonCode;
  startDate: string;
  endDate: string | null;
  active: boolean;
}

export interface TeamBenchRecord {
  employeeId: string;
  employeeName: string;
  reasonCode: BenchReasonCode;
  startDate: string;
  daysOnBench: number;
  lastProject: string | null;
}

export const benchApi = {
  start: (token: string, payload: { employeeId: string; reasonCode: BenchReasonCode; startDate?: string }): Promise<BenchStatusRecord> =>
    fetch(`${API_ORIGIN}/api/bench-status`, { method: 'POST', headers: authHeaders(token), body: JSON.stringify(payload) })
      .then(handle<BenchStatusRecord>),

  end: (token: string, id: string): Promise<BenchStatusRecord> =>
    fetch(`${API_ORIGIN}/api/bench-status/${id}/end`, { method: 'PATCH', headers: authHeaders(token), body: JSON.stringify({}) })
      .then(handle<BenchStatusRecord>),

  team: (token: string): Promise<TeamBenchRecord[]> =>
    fetch(`${API_ORIGIN}/api/bench-status/team`, { headers: authHeaders(token) }).then(handle<TeamBenchRecord[]>),
};
