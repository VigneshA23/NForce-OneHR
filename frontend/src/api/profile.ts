const BASE = '/api/profile';

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
};
