const BASE_DOCS = '/api/documents';
const BASE_TYPES = '/api/doc-types';

function authHeaders(token: string) {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` };
}

function authOnly(token: string) {
  return { Authorization: `Bearer ${token}` };
}

async function handle<T>(res: Response): Promise<T> {
  let body: { message?: string } = {};
  try { body = await res.json(); } catch { /* non-json */ }
  if (!res.ok) throw new Error((body as any).message ?? `Request failed (${res.status})`);
  return body as T;
}

// ── Types ──────────────────────────────────────────────────

export interface DocumentType {
  id: number;
  name: string;
  requiresVerification: boolean;
  requiresExpiryDate: boolean;
  applicableEmploymentTypes: string | null;
  applicableLocations: string | null;
  active: boolean;
  createdAt: string;
  usageCount: number;
}

export interface EmployeeDocument {
  id: string;
  employeeUserId: string;
  employeeName: string | null;
  documentTypeId: number;
  documentTypeName: string;
  requiresVerification: boolean;
  requiresExpiryDate: boolean;
  fileName: string;
  fileUrl: string;
  issueDate: string | null;
  expiryDate: string | null;
  status: 'PENDING_VERIFICATION' | 'VERIFIED' | 'REJECTED';
  verifiedBy: string | null;
  verifiedAt: string | null;
  rejectionReason: string | null;
  uploadedAt: string;
  updatedAt: string;
}

export interface RequiredDocument {
  documentTypeId: number;
  documentTypeName: string;
  requiresVerification: boolean;
  requiresExpiryDate: boolean;
  uploaded: boolean;
  status: string | null;
  expiringSoon: boolean;
}

export interface ComplianceSummary {
  totalRequired: number;
  uploaded: number;
  verified: number;
  pendingVerification: number;
  rejected: number;
  missing: number;
  expiringSoon: number;
  pendingPolicies: number;
}

export interface DocumentAdminKpi {
  pendingVerification: number;
  employeesWithPending: number;
  expiringWithin30Days: number;
  totalDocuments: number;
}

export interface MissingDocument {
  employeeUserId: string;
  employeeName: string;
  documentTypeId: number;
  documentTypeName: string;
}

// ── Document Types ─────────────────────────────────────────

export async function listActiveDocTypes(token: string): Promise<DocumentType[]> {
  return handle(await fetch(`${BASE_TYPES}/active`, { headers: authHeaders(token) }));
}

export async function listAllDocTypes(token: string): Promise<DocumentType[]> {
  return handle(await fetch(BASE_TYPES, { headers: authHeaders(token) }));
}

export async function createDocType(token: string, body: {
  name: string; requiresVerification: boolean; requiresExpiryDate: boolean;
  applicableEmploymentTypes?: string | null; applicableLocations?: string | null;
}): Promise<DocumentType> {
  return handle(await fetch(BASE_TYPES, { method: 'POST', headers: authHeaders(token), body: JSON.stringify(body) }));
}

export async function updateDocType(token: string, id: number, body: {
  name?: string; requiresVerification?: boolean; requiresExpiryDate?: boolean;
  applicableEmploymentTypes?: string | null; applicableLocations?: string | null;
}): Promise<DocumentType> {
  return handle(await fetch(`${BASE_TYPES}/${id}`, { method: 'PATCH', headers: authHeaders(token), body: JSON.stringify(body) }));
}

export async function toggleDocTypeActive(token: string, id: number): Promise<void> {
  await handle(await fetch(`${BASE_TYPES}/${id}/toggle-active`, { method: 'POST', headers: authHeaders(token) }));
}

export async function deleteDocType(token: string, id: number): Promise<void> {
  const res = await fetch(`${BASE_TYPES}/${id}`, { method: 'DELETE', headers: authHeaders(token) });
  if (!res.ok) {
    let body: { message?: string } = {};
    try { body = await res.json(); } catch { /* */ }
    throw new Error((body as any).message ?? `Delete failed (${res.status})`);
  }
}

// ── Employee Documents ─────────────────────────────────────

export async function myDocuments(token: string): Promise<EmployeeDocument[]> {
  return handle(await fetch(`${BASE_DOCS}/my`, { headers: authHeaders(token) }));
}

export async function myRequiredDocuments(token: string): Promise<RequiredDocument[]> {
  return handle(await fetch(`${BASE_DOCS}/my/required`, { headers: authHeaders(token) }));
}

export async function myCompliance(token: string): Promise<ComplianceSummary> {
  return handle(await fetch(`${BASE_DOCS}/my/compliance`, { headers: authHeaders(token) }));
}

export async function uploadDocument(token: string, params: {
  documentTypeId: number;
  file: File;
  issueDate?: string | null;
  expiryDate?: string | null;
}): Promise<EmployeeDocument> {
  const form = new FormData();
  form.append('documentTypeId', String(params.documentTypeId));
  form.append('file', params.file);
  if (params.issueDate) form.append('issueDate', params.issueDate);
  if (params.expiryDate) form.append('expiryDate', params.expiryDate);
  return handle(await fetch(`${BASE_DOCS}/my/upload`, { method: 'POST', headers: authOnly(token), body: form }));
}

// ── HR/SA Admin ────────────────────────────────────────────

export async function listAllDocuments(token: string): Promise<EmployeeDocument[]> {
  return handle(await fetch(BASE_DOCS, { headers: authHeaders(token) }));
}

export async function listPendingDocuments(token: string): Promise<EmployeeDocument[]> {
  return handle(await fetch(`${BASE_DOCS}/pending`, { headers: authHeaders(token) }));
}

export async function verifyDocument(token: string, id: string, action: 'VERIFY' | 'REJECT', rejectionReason?: string): Promise<EmployeeDocument> {
  return handle(await fetch(`${BASE_DOCS}/${id}/verify`, {
    method: 'POST', headers: authHeaders(token),
    body: JSON.stringify({ action, rejectionReason }),
  }));
}

export async function getAdminKpis(token: string): Promise<DocumentAdminKpi> {
  return handle(await fetch(`${BASE_DOCS}/kpis`, { headers: authHeaders(token) }));
}

export function documentFileUrl(id: string): string {
  return `${BASE_DOCS}/${id}/file`;
}

export async function fetchDocumentFile(token: string, id: string): Promise<string> {
  const res = await fetch(`${BASE_DOCS}/${id}/file`, { headers: authOnly(token) });
  if (!res.ok) throw new Error(`File fetch failed (${res.status})`);
  const blob = await res.blob();
  return URL.createObjectURL(blob);
}

export async function listMissingDocuments(token: string): Promise<MissingDocument[]> {
  return handle(await fetch(`${BASE_DOCS}/missing`, { headers: authHeaders(token) }));
}

export async function remindMissingDocument(token: string, employeeUserId: string, documentTypeId: number): Promise<void> {
  const res = await fetch(`${BASE_DOCS}/remind/${employeeUserId}/${documentTypeId}`, { method: 'POST', headers: authOnly(token) });
  if (!res.ok) throw new Error(`Remind failed (${res.status})`);
}
