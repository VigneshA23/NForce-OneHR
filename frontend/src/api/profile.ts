import { API_ORIGIN } from './config';
const BASE = `${API_ORIGIN}/api/profile`;

export interface ProfileData {
  userId: string;
  email: string;
  fullName: string;
  role: string;
  photoDataUrl: string | null;
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
