import { API_ORIGIN } from './config';
const BASE = `${API_ORIGIN}/api/assets`;

function authHeaders(token: string) {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` };
}

async function handle<T>(res: Response): Promise<T> {
  let body: { message?: string } = {};
  try { body = await res.json(); } catch { /* non-json */ }
  if (!res.ok) throw new Error((body as any).message ?? `Request failed (${res.status})`);
  return body as T;
}

export interface AssetCategory { id: number; name: string; }

export interface AssetResponse {
  id: number;
  assetTag: string;
  categoryId: number;
  categoryName: string;
  brand: string | null;
  model: string | null;
  serialNumber: string | null;
  purchaseDate: string | null;
  purchaseCost: number | null;
  warrantyExpiry: string | null;
  condition: string;
  status: string;
  locationId: string | null;
  locationName: string | null;
  currentCustodianName: string | null;
  currentCustodianUserId: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface AssetAssignmentResponse {
  id: number;
  assetId: number;
  assetTag: string | null;
  categoryName: string | null;
  brand: string | null;
  model: string | null;
  condition: string | null;
  employeeUserId: string;
  employeeName: string;
  effectiveFrom: string;
  effectiveTo: string | null;
  acknowledgedAt: string | null;
  returnCondition: string | null;
}

export interface AssetRequestResponse {
  id: number;
  employeeUserId: string;
  employeeName: string;
  categoryId: number;
  categoryName: string;
  reason: string;
  status: string;
  managerDecidedByName: string | null;
  managerDecidedAt: string | null;
  fulfilledByName: string | null;
  fulfilledAt: string | null;
  rejectionReason: string | null;
  createdAt: string;
}

export interface AssetTileEmployee {
  assignedCount: number;
  assignedSubtitle: string;
  openRequestCount: number;
}

export interface AssetTileManager {
  teamAssignedCount: number;
  teamMemberCount: number;
  overdueReturnCount: number;
  pendingRequestCount: number;
}

export interface AssetTileHR {
  totalAssigned: number;
  available: number;
  overdueReturns: number;
  pendingFulfillment: number;
}

export const assetsApi = {
  categories: (token?: string) =>
    fetch(`${BASE}/categories`, token ? { headers: authHeaders(token) } : {}).then(handle<AssetCategory[]>),

  myAssignments: (token: string) =>
    fetch(`${BASE}/my-assignments`, { headers: authHeaders(token) }).then(handle<AssetAssignmentResponse[]>),

  acknowledge: (id: number, token: string) =>
    fetch(`${BASE}/assignments/${id}/acknowledge`, { method: 'POST', headers: authHeaders(token) }).then(handle<AssetAssignmentResponse>),

  submitRequest: (payload: { categoryId: number; reason: string }, token: string) =>
    fetch(`${BASE}/requests`, { method: 'POST', headers: authHeaders(token), body: JSON.stringify(payload) }).then(handle<AssetRequestResponse>),

  myRequests: (token: string) =>
    fetch(`${BASE}/requests/mine`, { headers: authHeaders(token) }).then(handle<AssetRequestResponse[]>),

  pendingApprovals: (token: string) =>
    fetch(`${BASE}/requests/pending`, { headers: authHeaders(token) }).then(handle<AssetRequestResponse[]>),

  approveRequest: (id: number, token: string) =>
    fetch(`${BASE}/requests/${id}/approve`, { method: 'POST', headers: authHeaders(token) }).then(handle<AssetRequestResponse>),

  rejectRequest: (id: number, reason: string, token: string) =>
    fetch(`${BASE}/requests/${id}/reject`, { method: 'POST', headers: authHeaders(token), body: JSON.stringify({ reason }) }).then(handle<AssetRequestResponse>),

  // HR: inventory
  listAll: (token: string) =>
    fetch(`${BASE}`, { headers: authHeaders(token) }).then(handle<AssetResponse[]>),

  createAsset: (payload: {
    assetTag: string; categoryId: number; brand?: string; model?: string;
    serialNumber?: string; purchaseDate?: string; purchaseCost?: number;
    warrantyExpiry?: string; condition?: string; locationId?: string;
  }, token: string) =>
    fetch(`${BASE}`, { method: 'POST', headers: authHeaders(token), body: JSON.stringify(payload) }).then(handle<AssetResponse>),

  assignAsset: (assetId: number, employeeUserId: string, token: string) =>
    fetch(`${BASE}/${assetId}/assign`, { method: 'POST', headers: authHeaders(token), body: JSON.stringify({ employeeUserId }) }).then(handle<AssetResponse>),

  reassignAsset: (assetId: number, employeeUserId: string, token: string) =>
    fetch(`${BASE}/${assetId}/reassign`, { method: 'POST', headers: authHeaders(token), body: JSON.stringify({ employeeUserId }) }).then(handle<AssetResponse>),

  markReturned: (assetId: number, returnCondition: string, token: string) =>
    fetch(`${BASE}/${assetId}/mark-returned`, { method: 'POST', headers: authHeaders(token), body: JSON.stringify({ returnCondition }) }).then(handle<AssetResponse>),

  retireAsset: (assetId: number, token: string) =>
    fetch(`${BASE}/${assetId}/retire`, { method: 'POST', headers: authHeaders(token) }).then(handle<AssetResponse>),

  availableByCategory: (categoryId: number, token: string) =>
    fetch(`${BASE}/available-by-category/${categoryId}`, { headers: authHeaders(token) }).then(handle<AssetResponse[]>),

  fulfillRequest: (requestId: number, assetId: number, token: string) =>
    fetch(`${BASE}/requests/${requestId}/fulfill`, { method: 'POST', headers: authHeaders(token), body: JSON.stringify({ assetId }) }).then(handle<AssetRequestResponse>),

  // Manager: team view
  teamAssignments: (token: string) =>
    fetch(`${BASE}/team-assignments`, { headers: authHeaders(token) }).then(handle<AssetAssignmentResponse[]>),

  teamRequests: (token: string) =>
    fetch(`${BASE}/requests/team`, { headers: authHeaders(token) }).then(handle<AssetRequestResponse[]>),

  // Tiles
  employeeTiles: (token: string) =>
    fetch(`${BASE}/tiles/employee`, { headers: authHeaders(token) }).then(handle<AssetTileEmployee>),

  managerTiles: (token: string) =>
    fetch(`${BASE}/tiles/manager`, { headers: authHeaders(token) }).then(handle<AssetTileManager>),

  hrTiles: (token: string) =>
    fetch(`${BASE}/tiles/hr`, { headers: authHeaders(token) }).then(handle<AssetTileHR>),
};
