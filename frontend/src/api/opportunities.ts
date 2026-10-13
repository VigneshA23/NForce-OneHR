import { API_ORIGIN } from './config';

async function call<T>(token: string, path: string, method = 'GET', body?: unknown): Promise<T> {
  const res = await fetch(`${API_ORIGIN}${path}`, {
    method,
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (res.status === 204) return undefined as T;
  let json: { message?: string } = {};
  try { json = await res.json(); } catch { /* empty body (201 with no content) */ }
  if (!res.ok) throw new Error(json.message ?? `Request failed (${res.status})`);
  return json as T;
}

// ── Types (mirrors backend dto/opportunity/*) ──────────────────────────────────

export interface Opportunity {
  id: string;
  title: string;
  durationWeeks: number;
  status: 'OPEN' | 'CLOSED';
  requiredSkills: string[];
  postedBy: string | null;
  createdAt: string;
  interestCount: number;
  interested: boolean;
  assignedEmployee: string | null;
}

export interface InterestedEmployee { employeeId: string; employeeName: string; expressedAt: string }

export interface CloseOpportunityPayload {
  employeeId: string;
  projectId: string;
  capacityPercent: number;
  startDate: string;
  endDate: string;
}

export const opportunitiesApi = {
  list: (token: string) => call<Opportunity[]>(token, '/api/opportunities'),
  post: (token: string, payload: { title: string; durationWeeks: number; requiredSkillIds: string[] }) =>
    call<Opportunity>(token, '/api/opportunities', 'POST', payload),
  expressInterest: (token: string, id: string) => call<void>(token, `/api/opportunities/${id}/interest`, 'POST'),
  interests: (token: string, id: string) => call<InterestedEmployee[]>(token, `/api/opportunities/${id}/interests`),
  close: (token: string, id: string, payload: CloseOpportunityPayload) =>
    call<Opportunity>(token, `/api/opportunities/${id}/close`, 'POST', payload),
};
