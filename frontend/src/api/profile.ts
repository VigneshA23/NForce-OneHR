import { API_ORIGIN } from './config';
const BASE = `${API_ORIGIN}/api/profile`;

export interface ProfileData {
  userId: string;
  email: string;
  fullName: string;
  role: string;
  photoDataUrl: string | null;
  // My Profile page header background — independent of photoDataUrl (the round avatar). Null
  // means no custom cover has been uploaded, so the page falls back to its existing theme-color
  // banner image.
  coverDataUrl: string | null;
  phone: string | null;
  dateOfBirth: string | null;
  gender: string | null;
  personalEmail: string | null;
  address: string | null;
  emergencyContactName: string | null;
  emergencyContactPhone: string | null;
  workMode: string;
  employeeCode: string;
  departmentName: string | null;
  designationName: string | null;
  locationName: string | null;
  employmentType: string;
  joiningDate: string;
  managerName: string | null;
  managerEmail: string | null;
  active: boolean;
  hasEmployeeRecord: boolean;

  // ESS "My Profile" redesign — self-service editable
  firstName: string | null;
  middleName: string | null;
  lastName: string | null;
  preferredName: string | null;
  bio: string | null;
  maritalStatus: string | null;
  emergencyContactRelationship: string | null;
  permanentAddress: string | null;      // "address" stays Current Address
  passportNumber: string | null;
  passportExpiry: string | null;
  bankAccountNumber: string | null;     // masked, e.g. "•••• •••• 5591"
  bankName: string | null;
  bankIfsc: string | null;
  nationalId: string | null;            // masked

  // Job tab — read-only, HR-managed
  jobCode: string | null;
  probationEndDate: string | null;
  confirmationDate: string | null;
  businessUnitName: string | null;
  shiftName: string | null;
  weeklyOffPolicyName: string | null;
  attendancePenalizationPolicyName: string | null;

  // Attendance indicator
  attendanceStatus: 'IN' | 'OUT';
}

export interface UpdateProfilePayload {
  phone?: string;
  dateOfBirth?: string;
  // Omitting dateOfBirth (or sending it as null) is indistinguishable, on the backend, from
  // "this save didn't touch the date" — there's no JSON representation of "empty date" the way
  // there is for an empty string. Set this true (and omit dateOfBirth) to explicitly clear it.
  clearDateOfBirth?: boolean;
  gender?: string;
  personalEmail?: string;
  address?: string;
  emergencyContactName?: string;
  emergencyContactPhone?: string;
  workMode?: string;

  firstName?: string;
  middleName?: string;
  lastName?: string;
  preferredName?: string;
  bio?: string;
  maritalStatus?: string;
  emergencyContactRelationship?: string;
  permanentAddress?: string;
  passportNumber?: string;
  passportExpiry?: string;
  // Same reasoning as clearDateOfBirth above.
  clearPassportExpiry?: boolean;
  bankAccountNumber?: string;
  bankName?: string;
  bankIfsc?: string;
  nationalId?: string;
}

export interface EducationEntry {
  id: string;
  institutionName: string;
  degree: string;
  fieldOfStudy: string | null;
  startDate: string | null;
  endDate: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface EducationPayload {
  institutionName: string;
  degree: string;
  fieldOfStudy?: string;
  startDate?: string;
  endDate?: string;
}

export interface ProfileTimelineEvent {
  type: string;
  date: string;
  // Full-precision moment behind `date` — use this (not `date`) when ordering events, so
  // same-day changes still sort correctly relative to each other.
  timestamp: string;
  description: string;
}

async function handle<T>(res: Response): Promise<T> {
  let body: { message?: string } = {};
  try { body = await res.json(); } catch { /* non-JSON */ }
  if (!res.ok) throw new Error((body as { message?: string }).message ?? 'Request failed');
  return body as T;
}

export const profileApi = {
  get: (token: string) =>
    fetch(BASE, { headers: { Authorization: `Bearer ${token}` } })
      .then(handle<ProfileData>),

  update: (token: string, payload: UpdateProfilePayload) =>
    fetch(BASE, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: JSON.stringify(payload),
    }).then(handle<ProfileData>),

  uploadPhoto: (token: string, file: File) => {
    const form = new FormData();
    form.append('file', file);
    return fetch(`${BASE}/photo`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}` },
      body: form,
    }).then(handle<ProfileData>);
  },

  removePhoto: (token: string) =>
    fetch(`${BASE}/photo`, {
      method: 'DELETE',
      headers: { Authorization: `Bearer ${token}` },
    }).then(handle<ProfileData>),

  uploadCover: (token: string, file: File) => {
    const form = new FormData();
    form.append('file', file);
    return fetch(`${BASE}/cover`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}` },
      body: form,
    }).then(handle<ProfileData>);
  },

  removeCover: (token: string) =>
    fetch(`${BASE}/cover`, {
      method: 'DELETE',
      headers: { Authorization: `Bearer ${token}` },
    }).then(handle<ProfileData>),

  setAvatar: (token: string, avatarUrl: string) =>
    fetch(`${BASE}/avatar`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: JSON.stringify({ avatarUrl }),
    }).then(handle<ProfileData>),

  getTimeline: (token: string) =>
    fetch(`${BASE}/timeline`, { headers: { Authorization: `Bearer ${token}` } })
      .then(handle<ProfileTimelineEvent[]>),
};

export const profileEducationApi = {
  list: (token: string) =>
    fetch(`${BASE}/education`, { headers: { Authorization: `Bearer ${token}` } })
      .then(handle<EducationEntry[]>),

  create: (token: string, payload: EducationPayload) =>
    fetch(`${BASE}/education`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: JSON.stringify(payload),
    }).then(handle<EducationEntry>),

  update: (token: string, id: string, payload: EducationPayload) =>
    fetch(`${BASE}/education/${id}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: JSON.stringify(payload),
    }).then(handle<EducationEntry>),

  remove: (token: string, id: string) =>
    fetch(`${BASE}/education/${id}`, {
      method: 'DELETE',
      headers: { Authorization: `Bearer ${token}` },
    }).then(res => {
      if (!res.ok) throw new Error('Failed to delete education entry');
    }),
};

export type ProficiencyLevel = 'Beginner' | 'Intermediate' | 'Advanced' | 'Expert';
export const PROFICIENCY_LEVELS: ProficiencyLevel[] = ['Beginner', 'Intermediate', 'Advanced', 'Expert'];

export interface SkillEntry {
  id: string;
  skillName: string;
  proficiencyLevel: ProficiencyLevel;
  createdAt: string;
  updatedAt: string;
}

export interface SkillPayload {
  skillName: string;
  proficiencyLevel: ProficiencyLevel;
}

export const profileSkillsApi = {
  list: (token: string) =>
    fetch(`${BASE}/skills`, { headers: { Authorization: `Bearer ${token}` } })
      .then(handle<SkillEntry[]>),

  create: (token: string, payload: SkillPayload) =>
    fetch(`${BASE}/skills`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: JSON.stringify(payload),
    }).then(handle<SkillEntry>),

  update: (token: string, id: string, payload: SkillPayload) =>
    fetch(`${BASE}/skills/${id}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: JSON.stringify(payload),
    }).then(handle<SkillEntry>),

  remove: (token: string, id: string) =>
    fetch(`${BASE}/skills/${id}`, {
      method: 'DELETE',
      headers: { Authorization: `Bearer ${token}` },
    }).then(res => {
      if (!res.ok) throw new Error('Failed to delete skill');
    }),
};

export type LearningType =
  | 'Course' | 'Certification' | 'Self Learning' | 'Workshop'
  | 'Training' | 'Conference' | 'Internal Training' | 'Other';
export const LEARNING_TYPES: LearningType[] = [
  'Course', 'Certification', 'Self Learning', 'Workshop',
  'Training', 'Conference', 'Internal Training', 'Other',
];

export type LearningStatus = 'Planned' | 'In Progress' | 'Completed';
export const LEARNING_STATUSES: LearningStatus[] = ['Planned', 'In Progress', 'Completed'];

export type VerificationStatus = 'Self Reported' | 'Pending Verification' | 'Verified';

export interface LearningEntry {
  id: string;
  title: string;
  description: string | null;
  learningType: LearningType;
  status: LearningStatus;
  provider: string | null;
  startDate: string | null;
  completedDate: string | null;
  skillsDeveloped: string[];
  certificateName: string | null;
  certificateIssueDate: string | null;
  certificateExpiryDate: string | null;
  certificateUrl: string | null;
  verificationStatus: VerificationStatus;
  notes: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface LearningPayload {
  title: string;
  description?: string;
  learningType: LearningType;
  status: LearningStatus;
  provider?: string;
  startDate?: string;
  completedDate?: string;
  skillsDeveloped?: string[];
  certificateName?: string;
  certificateIssueDate?: string;
  certificateExpiryDate?: string;
  certificateUrl?: string;
  notes?: string;
}

export interface CertificateEntry {
  id: string;
  name: string;
  issuingOrganization: string | null;
  credentialId: string | null;
  credentialUrl: string | null;
  issueDate: string | null;
  expiryDate: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface CertificatePayload {
  name: string;
  issuingOrganization?: string;
  credentialId?: string;
  credentialUrl?: string;
  issueDate?: string;
  expiryDate?: string;
}

// Standalone certificates — separate from the certificate fields embedded on a Learning entry,
// for certifications earned outside any logged "Learning & Development" item.
export const profileCertificatesApi = {
  list: (token: string) =>
    fetch(`${BASE}/certificates`, { headers: { Authorization: `Bearer ${token}` } })
      .then(handle<CertificateEntry[]>),

  create: (token: string, payload: CertificatePayload) =>
    fetch(`${BASE}/certificates`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: JSON.stringify(payload),
    }).then(handle<CertificateEntry>),

  update: (token: string, id: string, payload: CertificatePayload) =>
    fetch(`${BASE}/certificates/${id}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: JSON.stringify(payload),
    }).then(handle<CertificateEntry>),

  remove: (token: string, id: string) =>
    fetch(`${BASE}/certificates/${id}`, {
      method: 'DELETE',
      headers: { Authorization: `Bearer ${token}` },
    }).then(res => {
      if (!res.ok) throw new Error('Failed to delete certificate');
    }),
};

// "Learning & Development" entries — self-service growth tracking (phase 1). Manager/lead
// visibility into this is an explicit later phase, not implemented here.
export const profileLearningApi = {
  list: (token: string) =>
    fetch(`${BASE}/learning`, { headers: { Authorization: `Bearer ${token}` } })
      .then(handle<LearningEntry[]>),

  create: (token: string, payload: LearningPayload) =>
    fetch(`${BASE}/learning`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: JSON.stringify(payload),
    }).then(handle<LearningEntry>),

  update: (token: string, id: string, payload: LearningPayload) =>
    fetch(`${BASE}/learning/${id}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
      body: JSON.stringify(payload),
    }).then(handle<LearningEntry>),

  remove: (token: string, id: string) =>
    fetch(`${BASE}/learning/${id}`, {
      method: 'DELETE',
      headers: { Authorization: `Bearer ${token}` },
    }).then(res => {
      if (!res.ok) throw new Error('Failed to delete learning entry');
    }),
};
