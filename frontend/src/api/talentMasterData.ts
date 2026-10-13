import { API_ORIGIN } from './config';

const BASE = `${API_ORIGIN}/api/org/talent`;

function authHeaders(token: string) {
  return { Authorization: `Bearer ${token}` };
}

async function handle<T>(res: Response): Promise<T> {
  let body: { message?: string } = {};
  try { body = await res.json(); } catch { /* non-JSON, e.g. 204 No Content */ }
  if (!res.ok) throw new Error((body as { message?: string }).message ?? `Request failed (${res.status})`);
  return body as T;
}

export interface SkillMasterOption {
  id: string;
  name: string;
  category: string | null;
  aliases: string[];
}

export interface ProficiencyLevelOption {
  id: string;
  name: string;
  sortOrder: number;
  description: string | null;
}

export interface CertificateCategoryOption {
  id: string;
  name: string;
  hasExpiry: boolean;
}

// Every authenticated employee can read these three lists (no role restriction) — they feed the
// Skills/Certificates tab's own pickers, same org-wide/no-scoping exposure as the directory
// listing itself. Only the Settings-side create/merge/deactivate endpoints are HR/Super-Admin-only.
export const skillMasterApi = {
  listActive: (token: string) =>
    fetch(`${BASE}/skills/active`, { headers: authHeaders(token) }).then(handle<SkillMasterOption[]>),
};

export const proficiencyLevelApi = {
  listActive: (token: string) =>
    fetch(`${BASE}/proficiency-levels/active`, { headers: authHeaders(token) }).then(handle<ProficiencyLevelOption[]>),
};

export const certificateCategoryApi = {
  listActive: (token: string) =>
    fetch(`${BASE}/certificate-categories/active`, { headers: authHeaders(token) }).then(handle<CertificateCategoryOption[]>),
};

export interface SkillRequestEntry {
  id: string;
  name: string;
  category: string | null;
  note: string | null;
  status: 'Pending' | 'Approved' | 'Rejected' | 'Mapped';
  mappedSkillName: string | null;
  createdAt: string;
}

export interface SkillRequestPayload {
  name: string;
  category?: string;
  note?: string;
}

export const skillRequestApi = {
  listMine: (token: string) =>
    fetch(`${BASE}/skill-requests/mine`, { headers: authHeaders(token) }).then(handle<SkillRequestEntry[]>),

  submit: (token: string, payload: SkillRequestPayload) =>
    fetch(`${BASE}/skill-requests`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...authHeaders(token) },
      body: JSON.stringify(payload),
    }).then(handle<SkillRequestEntry>),
};
