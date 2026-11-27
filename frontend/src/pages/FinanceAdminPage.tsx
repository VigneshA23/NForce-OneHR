import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { Wallet } from 'lucide-react';
import PageTitleBlock from '../components/PageTitleBlock';
import { TabBar, type TabDef } from '../components/TabBar';
import { useAuthStore } from '../store/authStore';
import { MySalaryPanel, MyPayslipsPanel, MyIncomeTaxPanel, useLoad, Status } from './MyPayPanels';
import { DeclarationPanel, PreviousIncomePanel, FormsPanel } from './MyTaxPanels';
import { adminTaxApi, financeAdminApi, CATEGORY_LABEL, type FinancialDetails, type QueueRow, type Partner, type TaxRules, type Slab, type DeclCategory } from '../api/myTax';
import { panelStyle, thStyle, tdStyle, inputStyle, labelStyle, fmtCurrency, fmtDate, fmtDateTime, SectionHead, BtnPrimary, BtnGhost, EmployeePicker } from './payroll/payrollShared';

// US-F15 / F11 / F17 / F18 - Super Admin finance console. The server gates every call on payroll:CONFIGURE:ALL.

const muted = { fontSize: 13, color: 'var(--txt-mut)' } as const;
const TOP: TabDef<string>[] = [
  { key: 'employee', label: 'Employee Finances' }, { key: 'proofs', label: 'Proof Queue' },
  { key: 'config', label: 'Configuration' }, { key: 'audit', label: 'Audit Log' },
];
const EMP_TABS: TabDef<string>[] = [
  { key: 'summary', label: 'Bank & Statutory' }, { key: 'salary', label: 'My Salary' }, { key: 'payslips', label: 'Payslips' },
  { key: 'income-tax', label: 'Income Tax' }, { key: 'declaration', label: 'Declarations' }, { key: 'previous', label: 'Previous Income' }, { key: 'forms', label: 'Forms' },
];
const IFSC = /^[A-Za-z]{4}0[A-Za-z0-9]{6}$/;

function Field({ label, children }: { label: string; children: ReactNode }) {
  return <label style={{ display: 'block' }}><span style={labelStyle}>{label}</span>{children}</label>;
}

export default function FinanceAdminPage() {
  const token = useAuthStore(s => s.token)!;
  const { userId } = useParams();
  const navigate = useNavigate();
  const api = useMemo(() => financeAdminApi(token), [token]);
  const [tab, setTab] = useState('employee');
  return (
    <div style={{ padding: '24px 28px' }}>
      <PageTitleBlock icon={Wallet} card>
        <h1 style={{ margin: 0, fontSize: 22, fontWeight: 800, color: 'var(--txt)' }}>Employee Finances</h1>
        <p style={{ margin: '2px 0 0', fontSize: 13, color: 'var(--txt-mut)' }}>Super Admin view of pay, tax and finance settings. Every change is logged.</p>
      </PageTitleBlock>
      <TabBar tabs={TOP} active={tab} onChange={setTab} ariaLabel="Finance admin sections" style={{ marginBottom: 16 }} />
      {tab === 'employee' && <EmployeeView token={token} api={api} userId={userId} onPick={id => navigate(`/finances/${id}`)} />}
      {tab === 'proofs' && <ProofQueue api={api} />}
      {tab === 'config' && <Configuration api={api} />}
      {tab === 'audit' && <AuditLog api={api} />}
    </div>
  );
}

// ── employee view ───────────────────────────────────────────────────────────

function EmployeeView({ token, api, userId, onPick }: { token: string; api: ReturnType<typeof financeAdminApi>; userId?: string; onPick: (id: string) => void }) {
  const [sub, setSub] = useState('summary');
  const people = useLoad(() => api.employees(''), [api]);
  const missing = useLoad(() => api.missingBank(), [api]);
  const options = (people.data ?? []).map(p => ({ id: p.id, label: `${p.name} (${p.code})` }));
  return (
    <>
      <div style={{ maxWidth: 420, marginBottom: 16 }}>
        <EmployeePicker value={userId ?? ''} onChange={onPick} options={options} />
      </div>
      {!userId && (
        <div style={{ ...panelStyle, padding: '16px 20px' }}>
          <strong style={{ color: 'var(--txt)' }}>Missing bank details</strong>
          {missing.data?.length === 0 && <div style={muted}>Everyone has bank details on file.</div>}
          {missing.error && <div style={muted}>{missing.error}</div>}
          {missing.data?.map(m => (
            <div key={m.employeeId} style={{ padding: '6px 0', borderTop: '1px solid var(--line)' }}>
              <button onClick={() => onPick(m.employeeId)} style={{ background: 'none', border: 'none', color: 'var(--brand)', cursor: 'pointer', padding: 0 }}>{m.employeeName} ({m.employeeCode})</button>
            </div>
          ))}
        </div>
      )}
      {userId && (
        <>
          <TabBar tabs={EMP_TABS} active={sub} onChange={setSub} size="sm" className="nf-boxedtabs" style={{ marginBottom: 16 }} />
          <EmployeeSection key={userId + sub} token={token} api={api} uid={userId} sub={sub} />
        </>
      )}
    </>
  );
}

function EmployeeSection({ token, api, uid, sub }: { token: string; api: ReturnType<typeof financeAdminApi>; uid: string; sub: string }) {
  const taxApi = useMemo(() => adminTaxApi(token, uid), [token, uid]);
  const [period, setPeriod] = useState<string | null>(null);
  const periods = useLoad(() => api.payslipPeriods(uid), [api, uid]);
  if (sub === 'summary') return <BankStatutory api={api} uid={uid} />;
  if (sub === 'salary') return <MySalaryPanel token={token} uid={uid} />;
  if (sub === 'income-tax') return <MyIncomeTaxPanel token={token} uid={uid} />;
  if (sub === 'payslips') return <MyPayslipsPanel token={token} uid={uid} periods={periods.data ?? []} selected={period} downloading={null} onSelect={setPeriod} onDownload={() => {}} />;
  if (sub === 'declaration') return <DeclarationPanel api={taxApi} adminMode loadTax={fy => api.incomeTax(uid, fy)} />;
  if (sub === 'previous') return <PreviousIncomePanel api={taxApi} admin />;
  return <FormsPanel token={token} api={taxApi} admin />;
}

function BankStatutory({ api, uid }: { api: ReturnType<typeof financeAdminApi>; uid: string }) {
  const [rev, setRev] = useState(0);
  const { data, error } = useLoad<FinancialDetails>(() => api.financialDetails(uid), [api, uid, rev]);
  const [f, setF] = useState<FinancialDetails | null>(null);
  const [msg, setMsg] = useState<string | null>(null);
  useEffect(() => setF(data ?? null), [data]);
  if (!f) return <Status error={error} />;
  const set = <K extends keyof FinancialDetails>(k: K, v: FinancialDetails[K]) => setF({ ...f, [k]: v });
  const text = (k: keyof FinancialDetails, label: string) => (
    <Field label={label}><input style={inputStyle} value={(f[k] as string | null) ?? ''} onChange={e => set(k, e.target.value as never)} /></Field>
  );
  const badIfsc = !!f.bankIfsc && !IFSC.test(f.bankIfsc);
  const save = async () => {
    setMsg(null);
    try {
      const { employeeId: _a, employeeCode: _b, employeeName: _c, ...rest } = f;
      await api.saveFinancialDetails(uid, rest);
      setMsg('Saved. The change is logged.'); setRev(r => r + 1);
    } catch (e) { setMsg(e instanceof Error ? e.message : 'Save failed'); }
  };
  return (
    <div style={{ ...panelStyle, padding: '18px 20px' }}>
      <SectionHead title={`${f.employeeName} (${f.employeeCode})`} />
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: 14 }}>
        {text('bankName', 'Bank name')}{text('bankAccountNumber', 'Account number')}
        <Field label="IFSC">
          <input style={inputStyle} value={f.bankIfsc ?? ''} onChange={e => set('bankIfsc', e.target.value.toUpperCase())} aria-invalid={badIfsc} />
          {badIfsc && <span style={{ color: 'var(--err, #b91c1c)', fontSize: 12 }}>11 characters, and the 5th must be 0</span>}
        </Field>
        {text('accountHolderName', 'Name on account')}{text('panNumber', 'PAN')}{text('uanNumber', 'UAN')}
        {text('pfNumber', 'PF number')}{text('nameAsPerPf', 'Name as per PF')}{text('esiNumber', 'ESI number')}{text('professionalTaxNumber', 'PT number')}
        <Field label="PF join date"><input type="date" style={inputStyle} value={f.pfRegistrationDate ?? ''} onChange={e => set('pfRegistrationDate', e.target.value || null)} /></Field>
        <Field label="PF enabled"><input type="checkbox" checked={f.pfEnabled} onChange={e => set('pfEnabled', e.target.checked)} /></Field>
        <Field label="ESI enabled"><input type="checkbox" checked={f.esiEnabled} onChange={e => set('esiEnabled', e.target.checked)} /></Field>
      </div>
      <div style={{ display: 'flex', gap: 10, marginTop: 16, flexWrap: 'wrap' }}>
        <BtnPrimary onClick={save} disabled={badIfsc}>Save</BtnPrimary>
        <BtnGhost onClick={async () => { try { await api.unlockRegime(uid); setMsg('Regime change unlocked for this employee (one time).'); } catch (e) { setMsg(e instanceof Error ? e.message : 'Failed'); } }}>Unlock tax regime change</BtnGhost>
      </div>
      {msg && <div role="status" style={{ ...muted, marginTop: 10 }}>{msg}</div>}
    </div>
  );
}

// ── proof queue (F11) ───────────────────────────────────────────────────────

function ProofQueue({ api }: { api: ReturnType<typeof financeAdminApi> }) {
  const [emp, setEmp] = useState('');
  const [category, setCategory] = useState('');
  const [status, setStatus] = useState('PENDING');
  const [rev, setRev] = useState(0);
  const people = useLoad(() => api.employees(''), [api]);
  const { data, error } = useLoad<QueueRow[]>(() => api.queue({ employeeId: emp || undefined, category, status }), [api, emp, category, status, rev]);
  return (
    <>
      <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', marginBottom: 16, alignItems: 'flex-end' }}>
        <div style={{ minWidth: 260 }}><EmployeePicker value={emp} onChange={setEmp} options={(people.data ?? []).map(p => ({ id: p.id, label: `${p.name} (${p.code})` }))} /></div>
        <select value={category} onChange={e => setCategory(e.target.value)} aria-label="Category" style={{ padding: 8, borderRadius: 6 }}>
          <option value="">All categories</option>
          {(Object.keys(CATEGORY_LABEL) as DeclCategory[]).map(c => <option key={c} value={c}>{CATEGORY_LABEL[c]}</option>)}
        </select>
        <select value={status} onChange={e => setStatus(e.target.value)} aria-label="Status" style={{ padding: 8, borderRadius: 6 }}>
          {['', 'PENDING', 'ACCEPTED', 'PARTIAL', 'REJECTED'].map(s => <option key={s} value={s}>{s || 'All statuses'}</option>)}
        </select>
        {emp && <BtnGhost onClick={() => setEmp('')}>Clear employee</BtnGhost>}
      </div>
      {!data ? <Status error={error} /> : data.length === 0 ? <div style={{ ...panelStyle, padding: 24, ...muted }}>No proofs match.</div>
        : data.map(r => <QueueCard key={r.declarationId} r={r} api={api} onDone={() => setRev(x => x + 1)} />)}
    </>
  );
}

function QueueCard({ r, api, onDone }: { r: QueueRow; api: ReturnType<typeof financeAdminApi>; onDone: () => void }) {
  const [comment, setComment] = useState('');
  const [amount, setAmount] = useState('');
  const [err, setErr] = useState<string | null>(null);
  const decide = async (decision: 'ACCEPT' | 'PARTIAL' | 'REJECT') => {
    setErr(null);
    try { await api.decide(r.declarationId, { decision, acceptedAmount: amount ? Number(amount) : undefined, comment }); onDone(); }
    catch (e) { setErr(e instanceof Error ? e.message : 'Failed'); }
  };
  return (
    <div style={{ ...panelStyle, padding: '14px 18px', marginBottom: 12 }}>
      <div style={{ color: 'var(--txt)' }}><strong>{r.employeeName}</strong> ({r.employeeCode}) · {CATEGORY_LABEL[r.category]} · {r.name} · {fmtCurrency(r.amount)}</div>
      <div style={muted}>Status: {r.status}{r.status === 'PARTIAL' && ` (${fmtCurrency(r.acceptedAmount)} accepted)`}{r.comment && ` · "${r.comment}"`}</div>
      <div style={{ margin: '6px 0' }}>
        {r.proofs.map(p => <button key={p.id} onClick={() => api.openProof(p.id, p.fileName)} style={{ display: 'block', background: 'none', border: 'none', color: 'var(--brand)', cursor: 'pointer', padding: 0, fontSize: 13 }}>{p.fileName}</button>)}
      </div>
      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>
        <input aria-label="Comment" placeholder="Comment (required unless accepting in full)" value={comment} onChange={e => setComment(e.target.value)} style={{ ...inputStyle, flex: '2 1 220px', width: 'auto' }} />
        <input aria-label="Accepted amount" type="number" placeholder="Accepted amount (partial)" value={amount} onChange={e => setAmount(e.target.value)} style={{ ...inputStyle, flex: '1 1 140px', width: 'auto' }} />
        <BtnPrimary onClick={() => decide('ACCEPT')}>Accept</BtnPrimary>
        <BtnGhost onClick={() => decide('PARTIAL')}>Accept partly</BtnGhost>
        <BtnGhost onClick={() => decide('REJECT')}>Reject</BtnGhost>
      </div>
      {err && <div role="alert" style={{ color: 'var(--err, #b91c1c)', fontSize: 13, marginTop: 6 }}>{err}</div>}
    </div>
  );
}

// ── configuration (F17 + F13 publish + F14 partners) ────────────────────────

const today = () => new Date().toISOString().slice(0, 10);
const curFy = () => { const n = new Date(); return n.getMonth() >= 3 ? n.getFullYear() : n.getFullYear() - 1; };

function Configuration({ api }: { api: ReturnType<typeof financeAdminApi> }) {
  return (
    <>
      <WindowsCard api={api} />
      <FormAndDueDate api={api} />
      <PartnersCard api={api} />
      <RulesCard api={api} />
    </>
  );
}

function Card({ title, children }: { title: string; children: ReactNode }) {
  return <div style={{ ...panelStyle, padding: '16px 20px', marginBottom: 18 }}><SectionHead title={title} />{children}</div>;
}

function WindowsCard({ api }: { api: ReturnType<typeof financeAdminApi> }) {
  const [rev, setRev] = useState(0);
  const [msg, setMsg] = useState<string | null>(null);
  const { data } = useLoad(() => api.windows(), [api, rev]);
  const [form, setForm] = useState<Record<string, { opensOn: string; closesOn: string; monthly: boolean }>>({
    DECLARATION: { opensOn: today(), closesOn: today(), monthly: true }, PROOF: { opensOn: today(), closesOn: today(), monthly: false }, REGIME: { opensOn: today(), closesOn: today(), monthly: false },
  });
  useEffect(() => { data?.forEach(w => w.opensOn && w.closesOn && setForm(f => ({ ...f, [w.kind]: { opensOn: w.opensOn!, closesOn: w.closesOn!, monthly: w.monthly } }))); }, [data]);
  const save = async (kind: 'DECLARATION' | 'PROOF' | 'REGIME') => {
    setMsg(null);
    try { await (kind === 'REGIME' ? api.setRegimeWindow(form.REGIME) : api.setWindow(kind, form[kind])); setMsg('Saved'); setRev(r => r + 1); }
    catch (e) { setMsg(e instanceof Error ? e.message : 'Failed'); }
  };
  return (
    <Card title="Windows">
      {(['DECLARATION', 'PROOF', 'REGIME'] as const).map(k => (
        <div key={k} style={{ display: 'flex', gap: 12, alignItems: 'flex-end', flexWrap: 'wrap', marginBottom: 12 }}>
          <strong style={{ width: 170, color: 'var(--txt)' }}>{k === 'DECLARATION' ? 'Investment Declaration' : k === 'PROOF' ? 'Proof Submission' : 'Tax Regime Change'}</strong>
          <Field label="Opens"><input type="date" style={inputStyle} value={form[k].opensOn} onChange={e => setForm(f => ({ ...f, [k]: { ...f[k], opensOn: e.target.value } }))} /></Field>
          <Field label="Closes"><input type="date" style={inputStyle} value={form[k].closesOn} onChange={e => setForm(f => ({ ...f, [k]: { ...f[k], closesOn: e.target.value } }))} /></Field>
          {k !== 'REGIME' && <label style={{ fontSize: 13 }}><input type="checkbox" checked={form[k].monthly} onChange={e => setForm(f => ({ ...f, [k]: { ...f[k], monthly: e.target.checked } }))} /> Only 1st to 22nd of each month</label>}
          <BtnPrimary onClick={() => save(k)}>Save</BtnPrimary>
        </div>
      ))}
      {msg && <div role="status" style={muted}>{msg}</div>}
    </Card>
  );
}

function FormAndDueDate({ api }: { api: ReturnType<typeof financeAdminApi> }) {
  const [fy, setFy] = useState(curFy());
  const [rev, setRev] = useState(0);
  const [due, setDue] = useState('');
  const [msg, setMsg] = useState<string | null>(null);
  const st = useLoad(() => api.form130Status(fy), [api, fy, rev]);
  const saved = useLoad(() => api.getDueDate(fy), [api, fy, rev]);
  useEffect(() => { if (saved.data) setDue(saved.data.dueDate ?? ''); }, [saved.data]);
  const act = async (fn: () => Promise<unknown>, ok: string) => { setMsg(null); try { await fn(); setMsg(ok); setRev(r => r + 1); } catch (e) { setMsg(e instanceof Error ? e.message : 'Failed'); } };
  return (
    <Card title="Form 130 and ITR due date">
      <select value={fy} onChange={e => setFy(Number(e.target.value))} aria-label="Financial year" style={{ padding: 8, borderRadius: 6, marginBottom: 12 }}>
        {[curFy(), curFy() - 1].map(y => <option key={y} value={y}>FY {y}-{String(y + 1).slice(2)}</option>)}
      </select>
      {st.data && (
        <div style={{ ...muted, marginBottom: 10 }}>
          {st.data.published ? `Published on ${fmtDate(st.data.publishedOn)}.` : 'Not published.'} {st.data.ready} of {st.data.employees} employees can download; {st.data.missingPan} have no PAN on file and cannot (their forms can't be locked).
        </div>
      )}
      <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', alignItems: 'flex-end' }}>
        <BtnPrimary onClick={() => act(() => api.publishForm130(fy), 'Published. Employees were notified.')}>{st.data?.published ? 'Publish again and notify' : 'Publish Form 130 and notify'}</BtnPrimary>
        <Field label="ITR due date"><input type="date" style={inputStyle} value={due} onChange={e => setDue(e.target.value)} /></Field>
        <BtnGhost onClick={() => act(() => api.setDueDate(fy, due), 'Due date saved')} disabled={!due}>Save due date</BtnGhost>
      </div>
      {msg && <div role="status" style={{ ...muted, marginTop: 10 }}>{msg}</div>}
    </Card>
  );
}

const blankPartner = { kind: 'FILING', name: '', url: 'https://', description: '', steps: '', couponCode: '', featured: false, enabled: true } as const;

function PartnersCard({ api }: { api: ReturnType<typeof financeAdminApi> }) {
  const [rev, setRev] = useState(0);
  const [edit, setEdit] = useState<Partial<Partner> | null>(null);
  const [msg, setMsg] = useState<string | null>(null);
  const { data } = useLoad<Partner[]>(() => api.partners(), [api, rev]);
  const run = async (fn: () => Promise<unknown>) => { setMsg(null); try { await fn(); setEdit(null); setRev(r => r + 1); } catch (e) { setMsg(e instanceof Error ? e.message : 'Failed'); } };
  return (
    <Card title="Tax filing and tax saving partners">
      {data?.length === 0 && <div style={muted}>No partners yet. Employees see no partner tabs until you add and enable one.</div>}
      {data?.map(p => (
        <div key={p.id} style={{ display: 'flex', justifyContent: 'space-between', gap: 12, padding: '8px 0', borderTop: '1px solid var(--line)' }}>
          <div style={{ color: 'var(--txt)' }}>{p.name} <span style={muted}>· {p.kind === 'FILING' ? 'Filing' : 'Saving'}{p.couponCode ? ` · ${p.couponCode}` : ''}</span></div>
          <div style={{ display: 'flex', gap: 8 }}>
            <BtnGhost onClick={() => run(() => api.savePartner({ ...p, enabled: !p.enabled }))}>{p.enabled ? 'Turn off' : 'Turn on'}</BtnGhost>
            <BtnGhost onClick={() => setEdit(p)}>Edit</BtnGhost>
            <BtnGhost onClick={() => run(() => api.deletePartner(p.id))}>Delete</BtnGhost>
          </div>
        </div>
      ))}
      {edit ? (
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: 12, marginTop: 14 }}>
          <Field label="Type"><select style={inputStyle} value={edit.kind} onChange={e => setEdit({ ...edit, kind: e.target.value as Partner['kind'] })}><option value="FILING">Tax filing</option><option value="SAVING">Tax saving</option></select></Field>
          <Field label="Name"><input style={inputStyle} value={edit.name ?? ''} onChange={e => setEdit({ ...edit, name: e.target.value })} /></Field>
          <Field label="Link (https)"><input style={inputStyle} value={edit.url ?? ''} onChange={e => setEdit({ ...edit, url: e.target.value })} /></Field>
          <Field label="Coupon code"><input style={inputStyle} value={edit.couponCode ?? ''} onChange={e => setEdit({ ...edit, couponCode: e.target.value })} /></Field>
          <Field label="Description"><input style={inputStyle} value={edit.description ?? ''} onChange={e => setEdit({ ...edit, description: e.target.value })} /></Field>
          <Field label="Steps (one per line)"><textarea style={inputStyle} rows={3} value={edit.steps ?? ''} onChange={e => setEdit({ ...edit, steps: e.target.value })} /></Field>
          <label style={{ fontSize: 13 }}><input type="checkbox" checked={!!edit.featured} onChange={e => setEdit({ ...edit, featured: e.target.checked })} /> Featured</label>
          <label style={{ fontSize: 13 }}><input type="checkbox" checked={edit.enabled !== false} onChange={e => setEdit({ ...edit, enabled: e.target.checked })} /> Enabled</label>
          <div style={{ display: 'flex', gap: 8 }}>
            <BtnPrimary onClick={() => run(() => api.savePartner(edit as Partner))}>Save partner</BtnPrimary>
            <BtnGhost onClick={() => setEdit(null)}>Cancel</BtnGhost>
          </div>
        </div>
      ) : <div style={{ marginTop: 12 }}><BtnGhost onClick={() => setEdit({ ...blankPartner })}>+ Add partner</BtnGhost></div>}
      {msg && <div role="alert" style={{ color: 'var(--err, #b91c1c)', fontSize: 13, marginTop: 8 }}>{msg}</div>}
    </Card>
  );
}

function RulesCard({ api }: { api: ReturnType<typeof financeAdminApi> }) {
  const [fy, setFy] = useState(curFy());
  const [regime, setRegime] = useState<'OLD' | 'NEW'>('NEW');
  const { data, error } = useLoad<TaxRules>(() => api.rules(fy, regime), [api, fy, regime]);
  const [r, setR] = useState<TaxRules | null>(null);
  const [msg, setMsg] = useState<string | null>(null);
  useEffect(() => setR(data ?? null), [data]);
  const setSlab = (i: number, patch: Partial<Slab>) => r && setR({ ...r, slabs: r.slabs.map((s, x) => (x === i ? { ...s, ...patch } : s)) });
  const num = (v: string) => (v === '' ? 0 : Number(v));
  return (
    <Card title="Tax slabs and rebate">
      <div style={{ display: 'flex', gap: 12, marginBottom: 12 }}>
        <select value={fy} onChange={e => setFy(Number(e.target.value))} aria-label="Financial year" style={{ padding: 8, borderRadius: 6 }}>
          {[curFy() + 1, curFy(), curFy() - 1].map(y => <option key={y} value={y}>FY {y}-{String(y + 1).slice(2)}</option>)}
        </select>
        <select value={regime} onChange={e => setRegime(e.target.value as 'OLD' | 'NEW')} aria-label="Regime" style={{ padding: 8, borderRadius: 6 }}>
          <option value="NEW">New regime</option><option value="OLD">Old regime</option>
        </select>
      </div>
      {!r ? <Status error={error} /> : (
        <>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: 12, marginBottom: 12 }}>
            <Field label="Standard deduction"><input type="number" style={inputStyle} value={r.standardDeduction} onChange={e => setR({ ...r, standardDeduction: num(e.target.value) })} /></Field>
            <Field label="87A rebate up to income"><input type="number" style={inputStyle} value={r.rebateThreshold} onChange={e => setR({ ...r, rebateThreshold: num(e.target.value) })} /></Field>
            <Field label="87A maximum rebate"><input type="number" style={inputStyle} value={r.rebateMax} onChange={e => setR({ ...r, rebateMax: num(e.target.value) })} /></Field>
            <Field label="Cess (0.04 = 4%)"><input type="number" step="0.01" style={inputStyle} value={r.cessRate} onChange={e => setR({ ...r, cessRate: num(e.target.value) })} /></Field>
          </div>
          <table style={{ width: '100%', borderCollapse: 'collapse', marginBottom: 10 }}>
            <thead><tr>{['From', 'To (blank = no limit)', 'Rate (0.05 = 5%)', ''].map(h => <th key={h} style={thStyle}>{h}</th>)}</tr></thead>
            <tbody>{r.slabs.map((s, i) => (
              <tr key={i}>
                <td style={tdStyle}><input type="number" style={inputStyle} value={s.from} onChange={e => setSlab(i, { from: num(e.target.value) })} /></td>
                <td style={tdStyle}><input type="number" style={inputStyle} value={s.to ?? ''} onChange={e => setSlab(i, { to: e.target.value === '' ? null : Number(e.target.value) })} /></td>
                <td style={tdStyle}><input type="number" step="0.01" style={inputStyle} value={s.rate} onChange={e => setSlab(i, { rate: num(e.target.value) })} /></td>
                <td style={tdStyle}><BtnGhost onClick={() => setR({ ...r, slabs: r.slabs.filter((_, x) => x !== i) })}>Remove</BtnGhost></td>
              </tr>
            ))}</tbody>
          </table>
          <div style={{ display: 'flex', gap: 10 }}>
            <BtnGhost onClick={() => setR({ ...r, slabs: [...r.slabs, { from: r.slabs.at(-1)?.to ?? 0, to: null, rate: 0 }] })}>+ Add slab</BtnGhost>
            <BtnPrimary onClick={async () => { setMsg(null); try { setR(await api.saveRules(r)); setMsg('Saved. Applies to the next calculation.'); } catch (e) { setMsg(e instanceof Error ? e.message : 'Failed'); } }}>Save rules</BtnPrimary>
          </div>
          <div style={{ ...muted, marginTop: 8 }}>Slabs must start at 0 and join up; only the last slab has no upper limit. Surcharge bands are not editable here.</div>
          {msg && <div role="status" style={{ ...muted, marginTop: 6 }}>{msg}</div>}
        </>
      )}
    </Card>
  );
}

// ── audit log (F18) ─────────────────────────────────────────────────────────

function AuditLog({ api }: { api: ReturnType<typeof financeAdminApi> }) {
  const [action, setAction] = useState('');
  const [emp, setEmp] = useState('');
  const actions = useLoad(() => api.auditActions(), [api]);
  const people = useLoad(() => api.employees(''), [api]);
  const { data, error } = useLoad(() => api.auditLog({ action, employeeId: emp || undefined }), [api, action, emp]);
  return (
    <>
      <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', marginBottom: 16 }}>
        <select value={action} onChange={e => setAction(e.target.value)} aria-label="Action" style={{ padding: 8, borderRadius: 6 }}>
          <option value="">All finance actions</option>
          {actions.data?.actions.map(a => <option key={a} value={a}>{a.replace(/_/g, ' ').toLowerCase()}</option>)}
        </select>
        <div style={{ minWidth: 260 }}><EmployeePicker value={emp} onChange={setEmp} options={(people.data ?? []).map(p => ({ id: p.id, label: `${p.name} (${p.code})` }))} /></div>
        {emp && <BtnGhost onClick={() => setEmp('')}>Clear employee</BtnGhost>}
      </div>
      <div style={{ ...panelStyle, overflowX: 'auto' }}>
        <table style={{ width: '100%', borderCollapse: 'collapse' }}>
          <thead><tr>{['When', 'Action', 'By', 'Employee', 'Change'].map(h => <th key={h} style={thStyle}>{h}</th>)}</tr></thead>
          <tbody>
            {data?.map(r => (
              <tr key={r.id}>
                <td style={tdStyle}>{fmtDateTime(r.at)}</td><td style={tdStyle}>{r.action.replace(/_/g, ' ').toLowerCase()}</td>
                <td style={tdStyle}>{r.actor ?? '-'}</td><td style={tdStyle}>{r.employee ?? '-'}</td>
                <td style={{ ...tdStyle, fontFamily: 'monospace', fontSize: 12 }}>{r.after ?? ''}</td>
              </tr>
            ))}
            {data?.length === 0 && <tr><td style={tdStyle} colSpan={5}>No entries.</td></tr>}
          </tbody>
        </table>
        {!data && <div style={{ padding: 16 }}><Status error={error} /></div>}
      </div>
    </>
  );
}
