import { API_ORIGIN } from './config';
const BASE = `${API_ORIGIN}/api/employees/my-reports`;

export interface DirectReport {
  userId: string;
  employeeCode: string;
  fullName: string;
  designationName: string | null;
  departmentName: string | null;
  active: boolean;
}

export interface ManagerDashboard {
  directReportCount: number;
  directReports: DirectReport[];
}

async function handle<T>(res: Response): Promise<T> {
  const body = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error((body as { message?: string }).message ?? 'Request failed');
  return body as T;
}

export const dashboardApi = {
  managerDashboard: (token: string) =>
    fetch(BASE, { headers: { Authorization: `Bearer ${token}` } })
      .then(handle<ManagerDashboard>),
};
