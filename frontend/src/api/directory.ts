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

  /** Colleagues who currently share the caller's manager — My Team: Peers view (ONEHR-73). */
  myPeers: (token: string) =>
    fetch(`${API_ORIGIN}/api/employees/my-peers`, { headers: { Authorization: `Bearer ${token}` } })
      .then(handle<DirectoryEntry[]>),

  /** The caller's own current reporting manager, or null if unassigned. */
  myManager: (token: string) =>
    fetch(`${API_ORIGIN}/api/employees/my-manager`, { headers: { Authorization: `Bearer ${token}` } })
      .then((res) => (res.status === 204 ? null : handle<{ userId: string; fullName: string; email: string } | null>(res))),
};
