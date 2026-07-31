const BASE = '/api/org/hierarchy';

export interface HierarchyNode {
  userId: string;
  fullName: string;
  designationName: string | null;
  departmentName: string | null;
  managerId: string | null;
  active: boolean;
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
};
