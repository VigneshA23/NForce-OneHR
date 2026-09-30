import { API_ORIGIN } from './config';
const BASE = `${API_ORIGIN}/api/employees`;

export interface DirectReport {
  userId: string;
  employeeCode: string;
  fullName: string;
  designationName: string | null;
  departmentName: string | null;
  active: boolean;
  /** Only populated on the HR (organization-wide) dashboard — null for the Manager view. */
  roleCode?: string | null;
}

export interface TeamJoiner {
  userId: string;
  employeeCode: string;
  fullName: string;
  designationName: string | null;
  departmentName: string | null;
  active: boolean;
  joinedTeamOn: string;
}

export interface ManagerDashboard {
  directReportCount: number;
  directReports: DirectReport[];
  teamJoiners: TeamJoiner[];
}

async function handle<T>(res: Response): Promise<T> {
  const body = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error((body as { message?: string }).message ?? 'Request failed');
  return body as T;
}

export const dashboardApi = {
  managerDashboard: (token: string) =>
    fetch(`${BASE}/my-reports`, { headers: { Authorization: `Bearer ${token}` } })
      .then(handle<ManagerDashboard>),

  /** HR dashboard — organization-wide equivalent of managerDashboard; every user in the org. */
  hrDashboard: (token: string) =>
    fetch(`${BASE}/org-dashboard`, { headers: { Authorization: `Bearer ${token}` } })
      .then(handle<ManagerDashboard>),
};
