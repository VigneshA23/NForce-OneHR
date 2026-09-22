import { API_ORIGIN } from './config';
const BASE = `${API_ORIGIN}/api/employees/birthdays`;

async function handle<T>(res: Response): Promise<T> {
  const body = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error((body as { message?: string }).message ?? `HTTP ${res.status}`);
  return body as T;
}

// Deliberately no birth-year field anywhere on this type — the backend never sends one either
// (see BirthdayEntryDto).
export interface BirthdayEntry {
  userId: string;
  fullName: string;
  departmentName: string | null;
  birthdayMonth: number;
  birthdayDay: number;
  daysUntil: number;
  today: boolean;
}

export const birthdaysApi = {
  /** Org-wide birthdays today or in the next 7 days — any authenticated user. */
  upcoming: (token: string) =>
    fetch(BASE, { headers: { Authorization: `Bearer ${token}` } }).then(r => handle<BirthdayEntry[]>(r)),
};
