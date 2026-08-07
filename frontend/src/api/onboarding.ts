import type { EmployeeRecord } from './employees';

const BASE = '/api/onboarding';

function authHeaders(token: string) {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` };
}

async function handle<T>(res: Response): Promise<T> {
  const body = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error((body as { message?: string }).message ?? `HTTP ${res.status}`);
  return body as T;
}

export interface OnboardingItem {
  id: string | null;
  itemKey: string;
  label: string;
  category: 'PRE_BOARDING' | 'SETUP' | 'DOCUMENTS';
  auto: boolean;
  source: string | null;
  dueDate: string | null;
  done: boolean;
  doneAt: string | null;
  doneByName: string | null;
  meta: string | null;
}

export interface DocumentsBreakdown {
  documentTypeName: string;
  status: 'VERIFIED' | 'PENDING_VERIFICATION' | 'REJECTED' | 'MISSING';
}

export interface TimelineEntry {
  at: string;
  text: string;
  meta: string;
}

export type OnboardingStatus = 'ON_TRACK' | 'ATTENTION' | 'OVERDUE' | 'COMPLETE';

export interface OnboardingSummary {
  checklistId: string;
  employeeUserId: string;
  employeeName: string;
  employeeCode: string;
  departmentName: string | null;
  designationName: string | null;
  joiningDate: string;
  archived: boolean;
  status: OnboardingStatus;
  totalItems: number;
  doneItems: number;
  nextDueLabel: string | null;
  nextDueDate: string | null;
  completedDate: string | null;
  durationDays: number | null;
}

export interface OnboardingDetail {
  checklistId: string;
  employeeUserId: string;
  employeeName: string;
  employeeCode: string;
  departmentName: string | null;
  designationName: string | null;
  locationName: string | null;
  managerName: string | null;
  joiningDate: string;
  archived: boolean;
  status: OnboardingStatus;
  completedAt: string | null;
  totalItems: number;
  doneItems: number;
  preBoarding: OnboardingItem[];
  setup: OnboardingItem[];
  documentsItem: OnboardingItem;
  documentsBreakdown: DocumentsBreakdown[];
  timeline: TimelineEntry[];
}

export interface StartOnboardingPayload {
  employeeUserId?: string;
  fullName?: string;
  email?: string;
  employeeCode?: string;
  departmentId?: string;
  designationId?: string;
  locationId?: string;
  employmentType?: string;
  workMode?: string;
  joiningDate?: string;
  managerId?: string;
}

export const onboardingApi = {
  queue: (token: string) =>
    fetch(BASE, { headers: authHeaders(token) }).then(r => handle<OnboardingSummary[]>(r)),

  eligibleEmployees: (token: string) =>
    fetch(`${BASE}/eligible-employees`, { headers: authHeaders(token) }).then(r => handle<EmployeeRecord[]>(r)),

  start: (payload: StartOnboardingPayload, token: string) =>
    fetch(BASE, { method: 'POST', headers: authHeaders(token), body: JSON.stringify(payload) }).then(r => handle<OnboardingDetail>(r)),

  detail: (checklistId: string, token: string) =>
    fetch(`${BASE}/${checklistId}`, { headers: authHeaders(token) }).then(r => handle<OnboardingDetail>(r)),

  toggleItem: (checklistId: string, itemId: string, token: string) =>
    fetch(`${BASE}/${checklistId}/items/${itemId}`, { method: 'PATCH', headers: authHeaders(token) }).then(r => handle<OnboardingDetail>(r)),
};
