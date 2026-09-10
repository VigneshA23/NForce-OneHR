import { API_ORIGIN } from './config';
const BASE = `${API_ORIGIN}/api/org/hierarchy`;
const CTX_BASE = `${API_ORIGIN}/api/org-hierarchy`;

export interface HierarchyNode {
  userId: string;
  fullName: string;
  designationName: string | null;
  departmentName: string | null;
  managerId: string | null;
  active: boolean;
}

export interface PersonCard {
  id: string;
  name: string;
  designation: string | null;
  department: string | null;
  initials: string;
  directReportsCount: number;
  isFocus: boolean;
  active: boolean;
  primaryRole?: string;
}

export interface BreadcrumbEntry {
  id: string;
  name: string;
}

export interface OrgContext {
  focusUser: PersonCard;
  manager: PersonCard | null;
  peers: PersonCard[];
  directReports: PersonCard[];
  breadcrumb: BreadcrumbEntry[];
}

async function handle<T>(res: Response): Promise<T> {
  const body = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error((body as { message?: string }).message ?? 'Request failed');
  return body as T;
}

export const hierarchyApi = {
  list: (token: string) =>
    fetch(BASE, { headers: { Authorization: `Bearer ${token}` } })
      .then(handle<HierarchyNode[]>),

  getContext: (token: string, userId: string) =>
    fetch(`${CTX_BASE}/context/${userId}`, { headers: { Authorization: `Bearer ${token}` } })
      .then(handle<OrgContext>),

  getRoots: (token: string) =>
    fetch(`${CTX_BASE}/roots`, { headers: { Authorization: `Bearer ${token}` } })
      .then(handle<PersonCard[]>),
};
