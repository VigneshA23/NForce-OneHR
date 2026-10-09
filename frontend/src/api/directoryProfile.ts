import { API_ORIGIN } from './config';
import type { ProfileData, SkillEntry, CertificateEntry, LearningEntry } from './profile';

export interface DirectoryProfileData {
  profile: ProfileData;
  // Whether the VIEWER holds HR_ADMIN/SUPER_ADMIN — use this, not a null-check on profile's own
  // sensitive fields, to decide whether to show them: an authorized viewer looking at an employee
  // who simply never filled those fields in would otherwise look identical to a redacted response.
  hasSensitiveAccess: boolean;
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

// The People Directory's "View Profile" action — org-wide (any authenticated employee can look
// up any other employee), unlike the manager-only teamGrowthApi. Sensitive self-service fields
// (DOB, personal email, address, emergency contact, passport, bank details) come back null
// unless the viewer is HR_ADMIN/SUPER_ADMIN — see ProfileService#getProfileForViewer.
export const directoryProfileApi = {
  get: (token: string, userId: string) =>
    fetch(`${API_ORIGIN}/api/directory/${userId}/profile`, { headers: { Authorization: `Bearer ${token}` } })
      .then(handle<DirectoryProfileData>),
};
