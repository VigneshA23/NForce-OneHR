import { API_ORIGIN } from './config';
const BASE = `${API_ORIGIN}/api/employees/directory`;

export interface DirectoryEntry {
  userId: string;
  employeeCode: string;
  fullName: string;
  email: string;
  departmentName: string | null;
  designationName: string | null;
  locationName: string | null;
  workMode: string;
  employmentType: string;
  active: boolean;
  managerName: string | null;
  managerEmail: string | null;
}

async function handle<T>(res: Response): Promise<T> {
  const body = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error((body as { message?: string }).message ?? 'Request failed');
  return body as T;
}

export const directoryApi = {
  list: (token: string) =>
    fetch(BASE, { headers: { Authorization: `Bearer ${token}` } })
      .then(handle<DirectoryEntry[]>),
};
