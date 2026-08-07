import { API_ORIGIN } from './config';
const BASE = `${API_ORIGIN}/api/holidays`;

async function handle<T>(res: Response): Promise<T> {
  const body = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error((body as { message?: string }).message ?? `HTTP ${res.status}`);
  return body as T;
}

function authHeaders(token: string) {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` };
}

export interface HolidayRow {
  id: string;
  holidayName: string;
  holidayDate: string;
  locationId: string;
  locationName: string;
  active: boolean;
}

export const holidaysApi = {
  listForMyLocation: (token: string) =>
    fetch(`${BASE}/my-location`, { headers: authHeaders(token) }).then(r => handle<HolidayRow[]>(r)),

  listByLocation: (token: string, locationId: string) =>
    fetch(`${BASE}?locationId=${locationId}`, { headers: authHeaders(token) }).then(r => handle<HolidayRow[]>(r)),

  listAll: (token: string) =>
    fetch(BASE, { headers: authHeaders(token) }).then(r => handle<HolidayRow[]>(r)),

  createHoliday: (token: string, payload: { holidayName: string; holidayDate: string; locationId: string }) =>
    fetch(BASE, {
      method: 'POST',
      headers: authHeaders(token),
      body: JSON.stringify(payload),
    }).then(r => handle<HolidayRow>(r)),
};
