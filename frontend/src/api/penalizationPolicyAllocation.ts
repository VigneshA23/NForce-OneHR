import { API_ORIGIN } from './config';
const BASE = `${API_ORIGIN}/api/org/penalisation-policy-allocations`;

function authHeaders(token: string) {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` };
}

async function handle<T>(res: Response): Promise<T> {
  let body: { message?: string } = {};
  try { body = await res.json(); } catch { /* non-json */ }
  if (!res.ok) throw new Error(body.message ?? `Request failed (${res.status})`);
  return body as T;
}

async function handleEmpty(res: Response): Promise<void> {
  if (!res.ok) {
    let body: { message?: string } = {};
    try { body = await res.json(); } catch { /* non-json */ }
    throw new Error(body.message ?? `Request failed (${res.status})`);
  }
}

// ── Types (mirrors backend dto/penalization/Allocation*) ──────────────────────

export type AllocationStatus = 'CURRENT' | 'FUTURE' | 'HISTORICAL';
// ALLOCATION_REQUIRED: no allocation and no legacy assignment exist, and the REQUIRE_ALLOCATION
// fallback strategy means no org default is consulted either — resolvedPolicyId is null. Any UI
// switching on this union must handle it explicitly rather than falling through to a default case
// that assumes a policy always resolves.
export type ResolvedPolicySource = 'ALLOCATION' | 'LEGACY' | 'DEFAULT' | 'ALLOCATION_REQUIRED';

export interface AllocationDto {
  id: string;
  penalisationPolicyId: string;
  penalisationPolicyName: string;
  effectiveFrom: string;
  effectiveTo: string | null;
  status: AllocationStatus;
  createdBy: string;
  createdAt: string;
  updatedBy: string | null;
  updatedAt: string;
}

export interface EmployeeAllocationRow {
  employeeUserId: string;
  employeeCode: string;
  fullName: string;
  email: string;
  active: boolean;
  designationTitle: string | null;
  businessUnitId: string | null;
  businessUnitName: string | null;
  departmentId: string | null;
  departmentName: string | null;
  locationId: string | null;
  locationName: string | null;
  reportingManagerId: string | null;
  reportingManagerName: string | null;
  resolvedPolicyId: string | null;
  resolvedPolicyName: string | null;
  resolvedPolicySource: ResolvedPolicySource;
  currentAllocation: AllocationDto | null;
  upcomingAllocation: AllocationDto | null;
}

export interface EmployeeAllocationSearchResponse {
  content: EmployeeAllocationRow[];
  totalElements: number;
  totalPages: number;
  page: number;
  size: number;
}

export interface EmployeeAllocationDetailResponse {
  employeeUserId: string;
  employeeCode: string;
  fullName: string;
  email: string;
  active: boolean;
  designationTitle: string | null;
  businessUnitName: string | null;
  departmentName: string | null;
  locationName: string | null;
  reportingManagerId: string | null;
  reportingManagerName: string | null;
  resolvedPolicyId: string | null;
  resolvedPolicyName: string | null;
  resolvedPolicySource: ResolvedPolicySource;
  history: AllocationDto[];
}

/** Section 21: "which policy applies to employee X on date Y" — for any date, not just today. */
export interface PolicyResolutionDetail {
  employeeUserId: string;
  date: string;
  resolvedPolicyId: string | null;
  resolvedPolicyName: string | null;
  resolvedPolicySource: ResolvedPolicySource;
  /** ACTIVE / INACTIVE of the resolved policy; null when resolvedPolicyId is null. */
  policyStatus: 'ACTIVE' | 'INACTIVE' | null;
  policyVersion: number | null;
  versionEffectiveFrom: string | null;
  currentAllocation: AllocationDto | null;
  /** Populated only when resolvedPolicyId is null — why nothing resolved. */
  reason: string | null;
}

export interface AllocationBulkResult {
  succeededIds: string[];
  failed: { employeeUserId: string; reason: string }[];
}

export interface SearchFilters {
  businessUnitId?: string;
  departmentId?: string;
  locationId?: string;
  penalisationPolicyId?: string;
  search?: string;
  page?: number;
  size?: number;
  /** Returns every matching employee in one response, ignoring page/size — used by the main
   * Allocation table, which shows all employees with no pagination. The Add Employees modal
   * omits this and keeps its own paginated fetch unchanged. */
  all?: boolean;
}

function buildQuery(filters: SearchFilters): string {
  const params = new URLSearchParams();
  if (filters.businessUnitId) params.set('businessUnitId', filters.businessUnitId);
  if (filters.departmentId) params.set('departmentId', filters.departmentId);
  if (filters.locationId) params.set('locationId', filters.locationId);
  if (filters.penalisationPolicyId) params.set('penalisationPolicyId', filters.penalisationPolicyId);
  if (filters.search) params.set('search', filters.search);
  if (filters.all) {
    params.set('all', 'true');
  } else {
    params.set('page', String(filters.page ?? 0));
    params.set('size', String(filters.size ?? 25));
  }
  return params.toString();
}

export const penalizationPolicyAllocationApi = {
  searchEmployees: (token: string, filters: SearchFilters): Promise<EmployeeAllocationSearchResponse> =>
    fetch(`${BASE}/employees?${buildQuery(filters)}`, { headers: authHeaders(token) })
      .then(handle<EmployeeAllocationSearchResponse>),

  getEmployeeDetail: (token: string, employeeUserId: string): Promise<EmployeeAllocationDetailResponse> =>
    fetch(`${BASE}/employees/${employeeUserId}`, { headers: authHeaders(token) })
      .then(handle<EmployeeAllocationDetailResponse>),

  resolveFor: (token: string, employeeUserId: string, date: string): Promise<PolicyResolutionDetail> =>
    fetch(`${BASE}/employees/${employeeUserId}/resolve?date=${date}`, { headers: authHeaders(token) })
      .then(handle<PolicyResolutionDetail>),

  allocate: (token: string, payload: { employeeUserId: string; penalisationPolicyId: string; effectiveFrom: string; effectiveTo?: string | null }): Promise<AllocationDto> =>
    fetch(BASE, { method: 'POST', headers: authHeaders(token), body: JSON.stringify(payload) }).then(handle<AllocationDto>),

  update: (token: string, allocationId: string, payload: { penalisationPolicyId: string; effectiveFrom: string; effectiveTo?: string | null }): Promise<AllocationDto> =>
    fetch(`${BASE}/${allocationId}`, { method: 'PUT', headers: authHeaders(token), body: JSON.stringify(payload) }).then(handle<AllocationDto>),

  remove: (token: string, allocationId: string): Promise<void> =>
    fetch(`${BASE}/${allocationId}`, { method: 'DELETE', headers: authHeaders(token) }).then(handleEmpty),

  bulkAllocate: (token: string, payload: { employeeUserIds: string[]; penalisationPolicyId: string; effectiveFrom: string; effectiveTo?: string | null }): Promise<AllocationBulkResult> =>
    fetch(`${BASE}/bulk`, { method: 'POST', headers: authHeaders(token), body: JSON.stringify(payload) }).then(handle<AllocationBulkResult>),

  bulkRemove: (token: string, employeeUserIds: string[]): Promise<AllocationBulkResult> =>
    fetch(`${BASE}/bulk-remove`, { method: 'POST', headers: authHeaders(token), body: JSON.stringify({ employeeUserIds }) }).then(handle<AllocationBulkResult>),

  // Gap-016: pre-submit preview of the same overlap check allocate/bulkAllocate enforce at write
  // time — lets the modal warn about a conflict before the admin submits, not just after.
  checkConflicts: (token: string, payload: { employeeUserIds: string[]; effectiveFrom: string; effectiveTo?: string | null; excludeAllocationId?: string }): Promise<Record<string, AllocationDto>> =>
    fetch(`${BASE}/check-conflicts`, { method: 'POST', headers: authHeaders(token), body: JSON.stringify(payload) }).then(handle<Record<string, AllocationDto>>),
};
