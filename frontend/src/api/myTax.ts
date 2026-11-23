import { API_ORIGIN } from './config';

// US-F10..F18 — Manage Tax + Super Admin finance. `selfTaxApi` hits own-data endpoints (identity from the token);
// `adminTaxApi(token, uid)` hits the same shapes under /api/admin/finances/{uid}; the rest of `financeAdminApi` is org-wide.

export type DeclCategory = 'SEC_80C' | 'OTHER_DEDUCTIONS' | 'ALLOWANCES' | 'HOUSE_PROPERTY' | 'OTHER_INCOME';
export const CATEGORY_LABEL: Record<DeclCategory, string> = {
  SEC_80C: '1.5 Lac Deductions (80C)', OTHER_DEDUCTIONS: 'Other Deductions', ALLOWANCES: 'Tax Saving Allowances',
  HOUSE_PROPERTY: 'House Property', OTHER_INCOME: 'Income & TDS/TCS from Other Sources',
};

export interface WindowView { kind: string; open: boolean; opensOn: string | null; closesOn: string | null; monthly: boolean }
export interface DeclItem {
  id: string; category: DeclCategory; name: string; amount: number; proofStatus: 'NONE' | 'PENDING' | 'ACCEPTED' | 'PARTIAL' | 'REJECTED';
  acceptedAmount: number; rejectedAmount: number; reviewComment: string | null; proofCount: number; counted: number;
}
export interface DeclOverview {
  fyStartYear: number; regime: 'OLD' | 'NEW'; declarationWindow: WindowView; proofWindow: WindowView; items: DeclItem[];
  summary: { count: number; declared: number; proofsSubmitted: number; rejected: number; accepted: number };
  sec80cDeclared: number; sec80cCapped: boolean; disabledCategories: DeclCategory[];
}
export interface DeclReq { category: DeclCategory; name: string; amount: number }
export interface ProofMeta { id: string; fileName: string }

export interface PrevRow { period: string; label: string; values: number[]; status: 'NOT_SUBMITTED' | 'PENDING' | 'APPROVED' }
export interface PrevGrid { applicable: boolean; fyStartYear: number; columns: string[]; rows: PrevRow[] }

export interface Partner { id: string; kind: 'FILING' | 'SAVING'; name: string; description: string | null; steps: string | null; url: string; couponCode: string | null; featured: boolean; enabled: boolean }
export interface Filing { fyStartYear: number; itrDueDate: string | null; filing: Partner[]; saving: Partner[] }

export interface QueueRow {
  declarationId: string; employeeId: string; employeeCode: string; employeeName: string; category: DeclCategory; name: string;
  amount: number; status: string; acceptedAmount: number; comment: string | null; proofs: ProofMeta[];
}
export interface Form130Status { fyStartYear: number; published: boolean; publishedOn: string | null; employees: number; ready: number; missingPan: number }
export interface Slab { from: number; to: number | null; rate: number }
export interface TaxRules { fyStartYear: number; regime: 'OLD' | 'NEW'; standardDeduction: number; rebateThreshold: number; rebateMax: number; cessRate: number; slabs: Slab[] }
export interface AuditRow { id: number; action: string; actor: string | null; employee: string | null; before: string | null; after: string | null; at: string }
export interface Person { id: string; code: string; name: string }
export interface FinancialDetails {
  employeeId: string; employeeCode: string; employeeName: string;
  bankAccountNumber: string | null; bankName: string | null; bankIfsc: string | null; accountHolderName: string | null;
  panNumber: string | null; uanNumber: string | null; esiNumber: string | null;
  pfEnabled: boolean; pfNumber: string | null; pfRegistrationDate: string | null; nameAsPerPf: string | null;
  esiEnabled: boolean; professionalTaxNumber: string | null;
}

async function call<T>(method: string, path: string, token: string, body?: unknown): Promise<T> {
  const res = await fetch(`${API_ORIGIN}${path}`, {
    method,
    headers: { Authorization: `Bearer ${token}`, ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}) },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  if (!res.ok) {
    let msg = `Request failed (${res.status})`;
    try { msg = (await res.json()).message ?? msg; } catch { /* non-json */ }
    throw new Error(msg);
  }
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

/** Fetches a file with the auth header and saves it. */
export async function download(path: string, token: string, fallbackName: string): Promise<void> {
  const res = await fetch(`${API_ORIGIN}${path}`, { headers: { Authorization: `Bearer ${token}` } });
  if (!res.ok) {
    let msg = `Download failed (${res.status})`;
    try { msg = (await res.json()).message ?? msg; } catch { /* non-json */ }
    throw new Error(msg);
  }
  const name = /filename="?([^";]+)"?/.exec(res.headers.get('Content-Disposition') ?? '')?.[1] ?? fallbackName;
  const url = URL.createObjectURL(await res.blob());
  const a = document.createElement('a');
  a.href = url; a.download = name; a.click();
  URL.revokeObjectURL(url);
}

const fyQ = (fy?: number) => (fy ? `?fy=${fy}` : '');

/** The operations the Declaration / Previous Income / Forms panels need. Self and admin both implement it. */
export interface TaxApi {
  overview(fy?: number): Promise<DeclOverview>;
  add(req: DeclReq, fy?: number): Promise<DeclOverview>;
  update(id: string, req: DeclReq, fy?: number): Promise<DeclOverview>;
  remove(id: string, fy?: number): Promise<DeclOverview>;
  uploadProof?(id: string, file: File, fy?: number): Promise<DeclOverview>;
  proofs(id: string): Promise<ProofMeta[]>;
  openProof(proofId: string, name: string): Promise<void>;
  previous(fy?: number): Promise<PrevGrid>;
  savePrevious(rows: { period: string; values: number[] }[], fy?: number): Promise<PrevGrid>;
  approvePrevious?(fy?: number): Promise<PrevGrid>;
  form130(fy: number): Promise<void>;
  form124(fy: number): Promise<void>;
}

export function selfTaxApi(token: string): TaxApi & { form130Published(fy: number): Promise<{ published: boolean }>; filing(fy?: number): Promise<Filing> } {
  const base = '/api/me/finances/tax';
  return {
    overview: fy => call('GET', `${base}/declarations${fyQ(fy)}`, token),
    add: (req, fy) => call('POST', `${base}/declarations${fyQ(fy)}`, token, req),
    update: (id, req, fy) => call('PUT', `${base}/declarations/${id}${fyQ(fy)}`, token, req),
    remove: (id, fy) => call('DELETE', `${base}/declarations/${id}${fyQ(fy)}`, token),
    uploadProof: async (id, file, fy) => {
      const form = new FormData(); form.append('file', file);
      const res = await fetch(`${API_ORIGIN}${base}/declarations/${id}/proofs${fyQ(fy)}`, { method: 'POST', headers: { Authorization: `Bearer ${token}` }, body: form });
      if (!res.ok) { let msg = `Upload failed (${res.status})`; try { msg = (await res.json()).message ?? msg; } catch { /* */ } throw new Error(msg); }
      return res.json();
    },
    proofs: id => call('GET', `${base}/declarations/${id}/proofs`, token),
    openProof: (proofId, name) => download(`${base}/proofs/${proofId}`, token, name),
    previous: fy => call('GET', `${base}/previous-income${fyQ(fy)}`, token),
    savePrevious: (rows, fy) => call('PUT', `${base}/previous-income${fyQ(fy)}`, token, rows),
    form130: fy => download(`${base}/form130?fy=${fy}`, token, 'Form130.pdf'),
    form124: fy => download(`${base}/form124?fy=${fy}`, token, 'Form124.pdf'),
    form130Published: fy => call('GET', `${base}/form130/status?fy=${fy}`, token),
    filing: fy => call('GET', `${base}/filing${fyQ(fy)}`, token),
  };
}

/** Super Admin acting on one employee. No proof upload (employees upload their own); edits are audited server-side. */
export function adminTaxApi(token: string, uid: string): TaxApi {
  const base = `/api/admin/finances/${uid}`;
  return {
    overview: fy => call('GET', `${base}/declarations${fyQ(fy)}`, token),
    add: (req, fy) => call('POST', `${base}/declarations${fyQ(fy)}`, token, req),
    update: (id, req, fy) => call('PUT', `${base}/declarations/${id}${fyQ(fy)}`, token, req),
    remove: (id, fy) => call('DELETE', `${base}/declarations/${id}${fyQ(fy)}`, token),
    proofs: async () => [],
    openProof: (proofId, name) => download(`/api/admin/finances/proofs/files/${proofId}`, token, name),
    previous: fy => call('GET', `${base}/previous-income${fyQ(fy)}`, token),
    savePrevious: (rows, fy) => call('PUT', `${base}/previous-income${fyQ(fy)}`, token, rows),
    approvePrevious: fy => call('POST', `${base}/previous-income/approve${fyQ(fy)}`, token),
    form130: fy => download(`${base}/form130?fy=${fy}`, token, 'Form130.pdf'),
    form124: fy => download(`${base}/form124?fy=${fy}`, token, 'Form124.pdf'),
  };
}

export const financeAdminApi = (token: string) => {
  const b = '/api/admin/finances';
  return {
    employees: (q: string) => call<Person[]>('GET', `${b}/employees?q=${encodeURIComponent(q)}`, token),
    queue: (f: { employeeId?: string; category?: string; status?: string }) => {
      const p = new URLSearchParams(); Object.entries(f).forEach(([k, v]) => v && p.set(k, v));
      return call<QueueRow[]>('GET', `${b}/proofs?${p}`, token);
    },
    openProof: (proofId: string, name: string) => download(`${b}/proofs/files/${proofId}`, token, name),
    decide: (declarationId: string, body: { decision: 'ACCEPT' | 'PARTIAL' | 'REJECT'; acceptedAmount?: number; comment?: string }) =>
      call<void>('POST', `${b}/proofs/${declarationId}/decision`, token, body),
    unlockRegime: (uid: string) => call<void>('POST', `${b}/${uid}/regime-unlock`, token),
    windows: () => call<WindowView[]>('GET', `${b}/windows`, token),
    setWindow: (kind: 'DECLARATION' | 'PROOF', body: { opensOn: string; closesOn: string; monthly: boolean }) => call<void>('PUT', `${b}/windows/${kind}`, token, body),
    setRegimeWindow: (body: { opensOn: string; closesOn: string; monthly: boolean }) => call<void>('PUT', `${b}/tax-regime-window`, token, body),
    form130Status: (fy: number) => call<Form130Status>('GET', `${b}/form130/status?fy=${fy}`, token),
    publishForm130: (fy: number) => call<Form130Status>('POST', `${b}/form130/publish?fy=${fy}`, token),
    partners: () => call<Partner[]>('GET', `${b}/partners`, token),
    savePartner: (p: Partial<Partner> & Pick<Partner, 'kind' | 'name' | 'url'>) => p.id
      ? call<Partner>('PUT', `${b}/partners/${p.id}`, token, p) : call<Partner>('POST', `${b}/partners`, token, p),
    deletePartner: (id: string) => call<void>('DELETE', `${b}/partners/${id}`, token),
    setDueDate: (fy: number, dueDate: string) => call<void>('PUT', `${b}/filing/${fy}/due-date`, token, { dueDate }),
    rules: (fy: number, regime: 'OLD' | 'NEW') => call<TaxRules>('GET', `${b}/rules?fy=${fy}&regime=${regime}`, token),
    saveRules: (r: TaxRules) => call<TaxRules>('PUT', `${b}/rules`, token, r),
    auditLog: (f: { employeeId?: string; action?: string }) => {
      const p = new URLSearchParams(); Object.entries(f).forEach(([k, v]) => v && p.set(k, v));
      return call<AuditRow[]>('GET', `${b}/audit-log?${p}`, token);
    },
    auditActions: () => call<{ actions: string[] }>('GET', `${b}/audit-log/actions`, token),
    // reuse the existing payroll endpoints for bank/statutory edits and the missing-bank list
    financialDetails: (uid: string) => call<FinancialDetails>('GET', `/api/payroll/employees/${uid}/financial-details`, token),
    // the server treats the PF/ESI switches as always-sent, so callers send the whole record back
    saveFinancialDetails: (uid: string, d: Omit<FinancialDetails, 'employeeId' | 'employeeCode' | 'employeeName'>) =>
      call<FinancialDetails>('PUT', `/api/payroll/employees/${uid}/financial-details`, token, d),
    missingBank: () => call<{ employeeId: string; employeeCode: string; employeeName: string }[]>('GET', '/api/payroll/employees/missing-bank-details', token),
    // income-tax / salary / payslips for any employee
    salaryTimeline: (uid: string) => call<import('./myPay').SalaryTimeline>('GET', `${b}/${uid}/salary-timeline`, token),
    incomeTax: (uid: string, fy?: number) => call<import('./myPay').IncomeTax>('GET', `${b}/${uid}/income-tax${fyQ(fy)}`, token),
    payslipPeriods: (uid: string) => call<string[]>('GET', `${b}/${uid}/payslips`, token),
    payslipPreview: (uid: string, period: string) => call<import('./myPay').PayslipPreview>('GET', `${b}/${uid}/payslips/${period}/preview`, token),
  };
};
