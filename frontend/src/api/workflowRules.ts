import { API_ORIGIN } from './config';

const BASE = `${API_ORIGIN}/api/approval-rules`;

function authHeaders(token: string) {
  return { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` };
}

async function handle<T>(res: Response): Promise<T> {
  let body: { message?: string } = {};
  try { body = await res.json(); } catch { /* non-json */ }
  if (!res.ok) throw new Error((body as any).message ?? `Request failed (${res.status})`);
  return body as T;
}

export interface ApprovalRule {
  id: string;
  ruleName: string;
  requestType: string;
  conditionField: string;
  operator: string;
  conditionValue: string;
  approvalStages: string[];
  active: boolean;
  createdByName: string;
  createdAt: string;
  updatedByName: string | null;
  updatedAt: string;
}

export interface ApprovalRuleMetadata {
  requestTypes: string[];
  conditionFieldsByRequestType: Record<string, string[]>;
  operators: string[];
  approvalRoles: string[];
}

export interface ApprovalRulePayload {
  ruleName: string;
  requestType: string;
  conditionField: string;
  operator: string;
  conditionValue: string;
  approvalStages: string[];
}

export interface ApprovalRulePreviewPayload extends ApprovalRulePayload {
  sampleValue?: number | null;
}

export interface ApprovalRulePreview {
  conditionField: string;
  operator: string;
  conditionValue: string;
  sampleValue: number;
  conditionExpression: string;
  conditionResult: boolean;
  currentRouting: string[];
  newRouting: string[];
}

export const workflowRulesApi = {
  metadata: (token: string) =>
    fetch(`${BASE}/metadata`, { headers: authHeaders(token) }).then(handle<ApprovalRuleMetadata>),

  listAll: (token: string) =>
    fetch(BASE, { headers: authHeaders(token) }).then(handle<ApprovalRule[]>),

  getById: (id: string, token: string) =>
    fetch(`${BASE}/${id}`, { headers: authHeaders(token) }).then(handle<ApprovalRule>),

  create: (payload: ApprovalRulePayload, token: string) =>
    fetch(BASE, { method: 'POST', headers: authHeaders(token), body: JSON.stringify(payload) }).then(handle<ApprovalRule>),

  update: (id: string, payload: ApprovalRulePayload, token: string) =>
    fetch(`${BASE}/${id}`, { method: 'PUT', headers: authHeaders(token), body: JSON.stringify(payload) }).then(handle<ApprovalRule>),

  delete: (id: string, token: string) =>
    fetch(`${BASE}/${id}`, { method: 'DELETE', headers: authHeaders(token) }).then(res => {
      if (!res.ok) return handle(res);
      return undefined;
    }),

  activate: (id: string, token: string) =>
    fetch(`${BASE}/${id}/activate`, { method: 'POST', headers: authHeaders(token) }).then(handle<ApprovalRule>),

  deactivate: (id: string, token: string) =>
    fetch(`${BASE}/${id}/deactivate`, { method: 'POST', headers: authHeaders(token) }).then(handle<ApprovalRule>),

  preview: (payload: ApprovalRulePreviewPayload, token: string) =>
    fetch(`${BASE}/preview`, { method: 'POST', headers: authHeaders(token), body: JSON.stringify(payload) }).then(handle<ApprovalRulePreview>),
};
