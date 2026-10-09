import { API_ORIGIN } from './config';
import type { SkillMasterOption, ProficiencyLevelOption, CertificateCategoryOption } from './talentMasterData';

const BASE = `${API_ORIGIN}/api/org/talent`;

function authHeaders(token: string) {
  return { Authorization: `Bearer ${token}` };
}
function jsonHeaders(token: string) {
  return { 'Content-Type': 'application/json', ...authHeaders(token) };
}

async function handle<T>(res: Response): Promise<T> {
  let body: unknown = {};
  try { body = await res.json(); } catch { /* non-JSON, e.g. 204 No Content */ }
  if (!res.ok) throw new Error((body as { message?: string })?.message ?? `Request failed (${res.status})`);
  return body as T;
}

// ── Dashboard ──────────────────────────────────────────────────────────────

export interface NameCount { name: string; count: number; }
export interface CertificateExpiryBreakdown { in30Days: number; in60Days: number; in90Days: number; }
export interface TalentDashboardSummary {
  totalEmployees: number;
  withSkills: number;
  withCertificates: number;
  withActiveLearningTrack: number;
  certificatesExpiringIn30Days: number;
  topSkills: NameCount[];
  proficiencyDistribution: NameCount[];
  skillsByDepartment: Record<string, NameCount[]>;
  certificatesByCategory: NameCount[];
  certificateExpiryBreakdown: CertificateExpiryBreakdown;
  learningStatusCounts: NameCount[];
}

export const talentDashboardApi = {
  get: (token: string) => fetch(`${BASE}/dashboard`, { headers: authHeaders(token) }).then(handle<TalentDashboardSummary>),
};

// ── Directory ──────────────────────────────────────────────────────────────

export interface SkillChip { id: string; skillId: string | null; skillName: string; proficiencyLevel: string; }
export interface CertificateChip {
  id: string; name: string; categoryId: string | null; categoryName: string | null;
  issuingOrganization: string | null; credentialId: string | null; credentialUrl: string | null;
  issueDate: string | null; expiryDate: string | null;
}
export interface LearningChip { id: string; title: string; status: string; learningType: string; }

export interface TalentEmployeeSummary {
  employeeUserId: string;
  employeeName: string;
  employeeCode: string;
  departmentName: string | null;
  locationName: string | null;
  designationName: string | null;
  managerName: string | null;
  skills: SkillChip[];
  certificates: CertificateChip[];
  learningEntries: LearningChip[];
}

export interface TalentEmployeeDetail {
  employeeUserId: string;
  employeeName: string;
  skills: SkillChip[];
  certificates: CertificateChip[];
  learningEntries: LearningChip[];
}

export const talentDirectoryApi = {
  listAll: (token: string) => fetch(`${BASE}/directory`, { headers: authHeaders(token) }).then(handle<TalentEmployeeSummary[]>),
  getDetail: (token: string, employeeUserId: string) =>
    fetch(`${BASE}/directory/${employeeUserId}`, { headers: authHeaders(token) }).then(handle<TalentEmployeeDetail>),
  exportUrl: () => `${BASE}/directory/export`,
};

// ── Search ─────────────────────────────────────────────────────────────────

export interface MatchedSkill { skillName: string; proficiencyLevel: string; }
export interface TalentSearchResultItem {
  employeeUserId: string;
  employeeName: string;
  employeeCode: string;
  departmentName: string | null;
  managerName: string | null;
  matchingSkills: MatchedSkill[];
  certificates: CertificateChip[];
  currentLearningTrack: string | null;
  matchScore: number;
}
export interface TalentSearchPageResponse {
  items: TalentSearchResultItem[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface TalentSearchRequest {
  skillIds?: string[];
  matchMode?: 'ALL' | 'ANY';
  minimumProficiencyLevelId?: string;
  certified?: boolean | null;
  department?: string;
  location?: string;
  managerName?: string;
  learningStatus?: string;
  page?: number;
  size?: number;
  sortBy?: 'BEST_MATCH' | 'NAME';
}

export interface SavedTalentSearch { id: string; name: string; query: TalentSearchRequest; createdAt: string; }

export const talentSearchApi = {
  search: (token: string, req: TalentSearchRequest) =>
    fetch(`${BASE}/search`, { method: 'POST', headers: jsonHeaders(token), body: JSON.stringify(req) }).then(handle<TalentSearchPageResponse>),
  save: (token: string, name: string, query: TalentSearchRequest) =>
    fetch(`${BASE}/search/saved`, { method: 'POST', headers: jsonHeaders(token), body: JSON.stringify({ name, query }) }).then(handle<SavedTalentSearch>),
  listSaved: (token: string) => fetch(`${BASE}/search/saved`, { headers: authHeaders(token) }).then(handle<SavedTalentSearch[]>),
  deleteSaved: (token: string, id: string) =>
    fetch(`${BASE}/search/saved/${id}`, { method: 'DELETE', headers: authHeaders(token) }).then(res => {
      if (!res.ok) throw new Error('Failed to delete saved search');
    }),
};

// ── Settings: Skills ───────────────────────────────────────────────────────

export interface SkillMasterSettingsEntry extends SkillMasterOption { active: boolean; usageCount: number; }

export const skillSettingsApi = {
  listAll: (token: string) => fetch(`${BASE}/skills`, { headers: authHeaders(token) }).then(handle<SkillMasterSettingsEntry[]>),
  create: (token: string, payload: { name: string; category?: string; aliases?: string[] }) =>
    fetch(`${BASE}/skills`, { method: 'POST', headers: jsonHeaders(token), body: JSON.stringify(payload) }).then(handle<SkillMasterSettingsEntry>),
  update: (token: string, id: string, payload: { name?: string; category?: string }) =>
    fetch(`${BASE}/skills/${id}`, { method: 'PATCH', headers: jsonHeaders(token), body: JSON.stringify(payload) }).then(handle<SkillMasterSettingsEntry>),
  deactivate: (token: string, id: string) =>
    fetch(`${BASE}/skills/${id}/deactivate`, { method: 'POST', headers: authHeaders(token) }).then(res => { if (!res.ok) throw new Error('Failed to deactivate'); }),
  reactivate: (token: string, id: string) =>
    fetch(`${BASE}/skills/${id}/reactivate`, { method: 'POST', headers: authHeaders(token) }).then(res => { if (!res.ok) throw new Error('Failed to reactivate'); }),
  addAlias: (token: string, id: string, alias: string) =>
    fetch(`${BASE}/skills/${id}/aliases`, { method: 'POST', headers: jsonHeaders(token), body: JSON.stringify({ alias }) }).then(handle<SkillMasterSettingsEntry>),
  merge: (token: string, sourceSkillId: string, targetSkillId: string) =>
    fetch(`${BASE}/skills/merge`, { method: 'POST', headers: jsonHeaders(token), body: JSON.stringify({ sourceSkillId, targetSkillId }) }).then(handle<SkillMasterSettingsEntry>),
};

// ── Settings: Proficiency Levels ───────────────────────────────────────────

export interface ProficiencyLevelSettingsEntry extends ProficiencyLevelOption { active: boolean; usageCount: number; }

export const proficiencyLevelSettingsApi = {
  listAll: (token: string) => fetch(`${BASE}/proficiency-levels`, { headers: authHeaders(token) }).then(handle<ProficiencyLevelSettingsEntry[]>),
  create: (token: string, payload: { name: string; description?: string }) =>
    fetch(`${BASE}/proficiency-levels`, { method: 'POST', headers: jsonHeaders(token), body: JSON.stringify(payload) }).then(handle<ProficiencyLevelSettingsEntry>),
  rename: (token: string, id: string, payload: { name: string; description?: string }) =>
    fetch(`${BASE}/proficiency-levels/${id}`, { method: 'PATCH', headers: jsonHeaders(token), body: JSON.stringify(payload) }).then(handle<ProficiencyLevelSettingsEntry>),
  reorder: (token: string, orderedIds: string[]) =>
    fetch(`${BASE}/proficiency-levels/reorder`, { method: 'POST', headers: jsonHeaders(token), body: JSON.stringify({ orderedIds }) }).then(handle<ProficiencyLevelSettingsEntry[]>),
  deactivate: (token: string, id: string) =>
    fetch(`${BASE}/proficiency-levels/${id}/deactivate`, { method: 'POST', headers: authHeaders(token) }).then(res => { if (!res.ok) throw new Error('Failed to deactivate'); }),
  reactivate: (token: string, id: string) =>
    fetch(`${BASE}/proficiency-levels/${id}/reactivate`, { method: 'POST', headers: authHeaders(token) }).then(res => { if (!res.ok) throw new Error('Failed to reactivate'); }),
};

// ── Settings: Certificate Categories ───────────────────────────────────────

export interface CertificateCategorySettingsEntry extends CertificateCategoryOption { active: boolean; usageCount: number; }

export const certificateCategorySettingsApi = {
  listAll: (token: string) => fetch(`${BASE}/certificate-categories`, { headers: authHeaders(token) }).then(handle<CertificateCategorySettingsEntry[]>),
  create: (token: string, payload: { name: string; hasExpiry: boolean }) =>
    fetch(`${BASE}/certificate-categories`, { method: 'POST', headers: jsonHeaders(token), body: JSON.stringify(payload) }).then(handle<CertificateCategorySettingsEntry>),
  update: (token: string, id: string, payload: { name: string; hasExpiry: boolean }) =>
    fetch(`${BASE}/certificate-categories/${id}`, { method: 'PATCH', headers: jsonHeaders(token), body: JSON.stringify(payload) }).then(handle<CertificateCategorySettingsEntry>),
  deactivate: (token: string, id: string) =>
    fetch(`${BASE}/certificate-categories/${id}/deactivate`, { method: 'POST', headers: authHeaders(token) }).then(res => { if (!res.ok) throw new Error('Failed to deactivate'); }),
  reactivate: (token: string, id: string) =>
    fetch(`${BASE}/certificate-categories/${id}/reactivate`, { method: 'POST', headers: authHeaders(token) }).then(res => { if (!res.ok) throw new Error('Failed to reactivate'); }),
};

// ── Settings: Skill Requests queue ─────────────────────────────────────────

export interface SkillRequestQueueEntry {
  id: string;
  requestedByUserId: string;
  requesterName: string;
  name: string;
  category: string | null;
  note: string | null;
  status: string;
  mappedToSkillId: string | null;
  mappedSkillName: string | null;
  createdAt: string;
}

export const skillRequestSettingsApi = {
  listPending: (token: string) => fetch(`${BASE}/skill-requests/pending`, { headers: authHeaders(token) }).then(handle<SkillRequestQueueEntry[]>),
  approve: (token: string, id: string) =>
    fetch(`${BASE}/skill-requests/${id}/approve`, { method: 'POST', headers: authHeaders(token) }).then(handle<SkillRequestQueueEntry>),
  mapToExisting: (token: string, id: string, existingSkillId: string) =>
    fetch(`${BASE}/skill-requests/${id}/map`, { method: 'POST', headers: jsonHeaders(token), body: JSON.stringify({ existingSkillId }) }).then(handle<SkillRequestQueueEntry>),
  reject: (token: string, id: string) =>
    fetch(`${BASE}/skill-requests/${id}/reject`, { method: 'POST', headers: authHeaders(token) }).then(handle<SkillRequestQueueEntry>),
};

// ── Settings: Migration Review ─────────────────────────────────────────────

export interface MigrationScanSummary {
  totalUnmigratedRows: number;
  candidatesCreated: number;
  candidatesUpdated: number;
  autoMatched: number;
}

export interface SkillMigrationCandidate {
  id: string;
  rawText: string;
  normalizedText: string;
  occurrenceCount: number;
  proposedSkillId: string | null;
  proposedSkillName: string | null;
  status: string;
  resolvedSkillId: string | null;
  resolvedSkillName: string | null;
  reviewedAt: string | null;
}

export const migrationReviewApi = {
  scan: (token: string) => fetch(`${BASE}/migration/scan`, { method: 'POST', headers: authHeaders(token) }).then(handle<MigrationScanSummary>),
  listPending: (token: string) => fetch(`${BASE}/migration/candidates/pending`, { headers: authHeaders(token) }).then(handle<SkillMigrationCandidate[]>),
  listAll: (token: string) => fetch(`${BASE}/migration/candidates`, { headers: authHeaders(token) }).then(handle<SkillMigrationCandidate[]>),
  resolveMapExisting: (token: string, id: string, existingSkillId: string) =>
    fetch(`${BASE}/migration/candidates/${id}/resolve`, { method: 'POST', headers: jsonHeaders(token), body: JSON.stringify({ action: 'MAP_EXISTING', existingSkillId }) }).then(handle<SkillMigrationCandidate>),
  resolveCreateNew: (token: string, id: string, category?: string) =>
    fetch(`${BASE}/migration/candidates/${id}/resolve`, { method: 'POST', headers: jsonHeaders(token), body: JSON.stringify({ action: 'CREATE_NEW', category }) }).then(handle<SkillMigrationCandidate>),
  resolveDiscard: (token: string, id: string) =>
    fetch(`${BASE}/migration/candidates/${id}/resolve`, { method: 'POST', headers: jsonHeaders(token), body: JSON.stringify({ action: 'DISCARD' }) }).then(handle<SkillMigrationCandidate>),
};
