import { useEffect, useState } from 'react';
import { useAuthStore } from '../store/authStore';
import { dashboardApi } from '../api/dashboard';

// Employee ID lookup for screens whose records carry only an employee's name/user id.
//
// Display only: it reads the same dashboard endpoints those roles already use (org-wide for HR Admin /
// Super Admin, direct reports for everyone else) and exposes `codeFor(userId)`. It never widens who can
// see an employee: a manager is only ever given their own reports' codes, HR the organisation's, and
// anyone not in the map simply shows no ID. One fetch per session is cached and shared by every table.
let cache: { key: string; map: Map<string, string> } | null = null;
let inflight: Promise<Map<string, string>> | null = null;

function load(token: string, isHr: boolean): Promise<Map<string, string>> {
  const key = `${token}:${isHr ? 'hr' : 'mgr'}`;
  if (cache?.key === key) return Promise.resolve(cache.map);
  if (!inflight) {
    inflight = (isHr ? dashboardApi.hrDashboard(token) : dashboardApi.managerDashboard(token))
      .then(d => {
        const map = new Map<string, string>();
        for (const r of d.directReports ?? []) map.set(r.userId, r.employeeCode);
        cache = { key, map };
        return map;
      })
      .catch(() => new Map<string, string>())
      .finally(() => { inflight = null; });
  }
  return inflight;
}

export function useEmployeeCodes(): (userId: string | null | undefined) => string | null {
  const token = useAuthStore(s => s.token) ?? '';
  const role = useAuthStore(s => s.user?.role);
  const isHr = role === 'HR_ADMIN' || role === 'SUPER_ADMIN';
  const [map, setMap] = useState<Map<string, string>>(() => cache?.map ?? new Map());

  useEffect(() => {
    if (!token) return;
    let live = true;
    load(token, isHr).then(m => { if (live) setMap(m); });
    return () => { live = false; };
  }, [token, isHr]);

  return (userId) => (userId ? map.get(userId) ?? null : null);
}
