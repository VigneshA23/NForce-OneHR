import { API_ORIGIN } from './config';

async function call<T>(token: string, path: string, method = 'GET', body?: unknown): Promise<T> {
  const res = await fetch(`${API_ORIGIN}${path}`, {
    method,
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (res.status === 204) return undefined as T;
  let json: { message?: string } = {};
  try { json = await res.json(); } catch { /* empty body (200 with no content) */ }
  if (!res.ok) throw new Error(json.message ?? `Request failed (${res.status})`);
  return json as T;
}

// ── Types (mirrors backend dto/skill/*) ────────────────────────────────────────

export interface Skill { id: string; skill: string; category: string }

export interface SkillRating {
  skillId: string;
  skill: string;
  category: string;
  selfRating: number;
  managerRating: number | null;
}

export interface TeamSkillRating {
  employeeId: string;
  employeeName: string;
  skillId: string;
  skill: string;
  selfRating: number;
  managerRating: number | null;
}

export const skillsApi = {
  taxonomy: (token: string) => call<Skill[]>(token, '/api/skill-taxonomy'),
  addSkill: (token: string, skill: string, category: string) =>
    call<Skill>(token, '/api/skill-taxonomy', 'POST', { skill, category }),

  mine: (token: string) => call<SkillRating[]>(token, '/api/skill-ratings/me'),
  rateSelf: (token: string, skillId: string, rating: number) =>
    call<void>(token, '/api/skill-ratings/me', 'PUT', { skillId, rating }),
  removeSelf: (token: string, skillId: string) =>
    call<void>(token, `/api/skill-ratings/me/${skillId}`, 'DELETE'),

  team: (token: string) => call<TeamSkillRating[]>(token, '/api/skill-ratings/team'),
  rateAsManager: (token: string, employeeId: string, skillId: string, rating: number) =>
    call<void>(token, `/api/skill-ratings/${employeeId}/${skillId}/manager?rating=${rating}`, 'PUT'),
};
