import { API_ORIGIN } from './config';
import type { SkillEntry, CertificateEntry, LearningEntry } from './profile';
const BASE = `${API_ORIGIN}/api/team`;

export interface TeamGrowthData {
  employeeUserId: string;
  employeeName: string;
  skills: SkillEntry[];
  certificates: CertificateEntry[];
  learningEntries: LearningEntry[];
}

export interface TeamGrowthSummary {
  employeeUserId: string;
  employeeName: string;
  employeeCode: string;
  skills: SkillEntry[];
  certificates: CertificateEntry[];
  learningEntries: LearningEntry[];
}

async function handle<T>(res: Response): Promise<T> {
  let body: { message?: string } = {};
  try { body = await res.json(); } catch { /* non-JSON */ }
  if (!res.ok) throw new Error((body as { message?: string }).message ?? 'Request failed');
  return body as T;
}

// Manager's read-only window into a direct report's own Skills tab content — see
// TeamGrowthController/TeamGrowthService on the backend for the permission model.
export const teamGrowthApi = {
  get: (token: string, employeeUserId: string) =>
    fetch(`${BASE}/${employeeUserId}/growth`, { headers: { Authorization: `Bearer ${token}` } })
      .then(handle<TeamGrowthData>),

  listSummaries: (token: string) =>
    fetch(`${BASE}/growth`, { headers: { Authorization: `Bearer ${token}` } })
      .then(handle<TeamGrowthSummary[]>),
};
