import { useState } from 'react';
import { ExternalLink, Download, IndianRupee, Wallet, Trash2, Pencil, Upload, Paperclip } from 'lucide-react';
import { type IncomeTax } from '../api/myPay';
import {
  CATEGORY_LABEL, selfTaxApi, type TaxApi, type DeclOverview, type DeclItem, type DeclCategory, type PrevGrid, type Filing, type Partner, type WindowView,
} from '../api/myTax';
import { useLoad, Status } from './MyPayPanels';
import { panelStyle, thStyle, tdStyle, inputStyle, fmtCurrency, fmtDate, SectionHead, StatTile, BtnPrimary, BtnGhost } from './payroll/payrollShared';

const muted = { fontSize: 13, color: 'var(--txt-mut)' } as const;
const right = { textAlign: 'right' } as const;
const CATEGORIES = Object.keys(CATEGORY_LABEL) as DeclCategory[];
const PROOF_LABEL: Record<DeclItem['proofStatus'], string> = { NONE: 'No proof', PENDING: 'Proof pending', ACCEPTED: 'Accepted', PARTIAL: 'Partly accepted', REJECTED: 'Rejected' };

const currentFy = () => { const n = new Date(); return n.getMonth() >= 3 ? n.getFullYear() : n.getFullYear() - 1; };
const fyLabel = (y: number) => `FY ${y}-${String(y + 1).slice(2)}`;

function FySelect({ value, onChange }: { value: number; onChange: (y: number) => void }) {
  const cur = currentFy();
  return (
    <select value={value} onChange={e => onChange(Number(e.target.value))} aria-label="Financial year" style={{ padding: '8px 10px', borderRadius: 6 }}>
      {[cur, cur - 1].map(y => <option key={y} value={y}>{fyLabel(y)}</option>)}
    </select>
  );
}

function WindowCard({ title, w, extra }: { title: string; w: WindowView; extra?: string }) {
  return (
    <div style={{ ...panelStyle, padding: '14px 18px', flex: '1 1 260px' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <strong style={{ color: 'var(--txt)' }}>{title}</strong>
        <span style={{ fontSize: 11, fontWeight: 800, color: w.open ? 'var(--ok)' : 'var(--txt-dim)' }}>{w.open ? 'OPEN' : 'CLOSED'}</span>
      </div>
      <div style={{ ...muted, marginTop: 6 }}>
        {w.closesOn ? `Window ends ${fmtDate(w.closesOn)}` : 'Window not set'}
        {w.monthly && w.closesOn && <div>Open from the 1st to the 22nd of each month until {fmtDate(w.closesOn)}.</div>}
        {extra}
      </div>
    </div>
  );
}

// ── US-F10 / F11 Declaration + proofs ───────────────────────────────────────

/** `adminMode` = Super Admin editing on someone's behalf: window checks are skipped and every edit is logged server-side. */
export function DeclarationPanel({ api, loadTax, adminMode = false }: { api: TaxApi; loadTax: (fy: number) => Promise<IncomeTax>; adminMode?: boolean }) {
  const [fy, setFy] = useState(currentFy());
  const [rev, setRev] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState<string | null>(null); // item id, or `new:<category>`
  const [proofs, setProofs] = useState<Record<string, { id: string; fileName: string }[]>>({});
  const { data, error: loadError } = useLoad<DeclOverview>(() => api.overview(fy), [api, fy, rev]);
  const tax = useLoad<IncomeTax>(() => loadTax(fy), [api, fy, rev]);

  if (!data) return <Status error={loadError} />;
  const canEdit = adminMode || data.declarationWindow.open;
  const toggleProofs = (id: string) => {
    if (proofs[id]) { setProofs(({ [id]: _hide, ...rest }) => rest); return; }
    api.proofs(id).then(list => setProofs(p => ({ ...p, [id]: list })), e => setError(e instanceof Error ? e.message : "Could not load proofs"));
  };
  const canProve = !adminMode && data.proofWindow.open && !!api.uploadProof;

  const run = async (fn: () => Promise<unknown>) => {
    setError(null);
    try { await fn(); setEditing(null); setRev(r => r + 1); } catch (e) { setError(e instanceof Error ? e.message : 'Something went wrong'); }
  };

  return (
    <>
      <SectionHead title="Declaration" action={<FySelect value={fy} onChange={setFy} />} />
      <div style={{ ...panelStyle, padding: '12px 18px', marginBottom: 16, ...muted }}>
        <strong>{fyLabel(fy)}</strong> · {data.regime === 'OLD' ? 'Old' : 'New'} Tax Regime
        {adminMode && <> · <strong>Editing on the employee's behalf. Each change is logged.</strong></>}
      </div>
      <div style={{ display: 'flex', gap: 16, flexWrap: 'wrap', marginBottom: 16 }}>
        <WindowCard title="Investment Declaration" w={data.declarationWindow} />
        <WindowCard title="Proof Submission" w={data.proofWindow} />
      </div>
      {!canEdit && <div style={{ ...panelStyle, padding: '12px 18px', marginBottom: 16, color: 'var(--warn, #b45309)', fontSize: 13 }}>Window closed. Your declarations are read-only.</div>}
      {error && <div role="alert" style={{ color: 'var(--err, #b91c1c)', fontSize: 13, marginBottom: 12 }}>{error}</div>}

      <div className="nf-pr-tiles" style={{ marginBottom: 22 }}>
        <StatTile tone="var(--brand)" icon={<IndianRupee size={20} />} label="Net Taxable Income" value={tax.data ? fmtCurrency(tax.data.netTaxableIncome) : '…'} />
        <StatTile tone="var(--warn, #b45309)" icon={<Wallet size={20} />} label="Total Tax Payable" value={tax.data ? fmtCurrency(tax.data.tax.netPayable) : '…'} />
        <StatTile tone="var(--ok)" icon={<IndianRupee size={20} />} label="Tax Already Paid" value={tax.data ? fmtCurrency(tax.data.taxPaidTillNow) : '…'} />
      </div>

      {data.sec80cCapped && (
        <div role="status" style={{ ...panelStyle, padding: '12px 18px', marginBottom: 16, color: 'var(--warn, #b45309)', fontSize: 13 }}>
          You declared {fmtCurrency(data.sec80cDeclared)} under 80C. Only {fmtCurrency(150000)} is counted.
        </div>
      )}

      {CATEGORIES.map(cat => {
        const disabled = data.disabledCategories.includes(cat);
        const items = data.items.filter(i => i.category === cat);
        return (
          <div key={cat} style={{ ...panelStyle, padding: '14px 18px', marginBottom: 14, opacity: disabled ? 0.6 : 1 }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12, alignItems: 'center' }}>
              <strong style={{ color: 'var(--txt)' }}>{CATEGORY_LABEL[cat]}</strong>
              {canEdit && !disabled && <BtnGhost onClick={() => setEditing(`new:${cat}`)}>+ Add</BtnGhost>}
            </div>
            {disabled && <div style={{ ...muted, marginTop: 6 }}>Not applicable under the New Regime.</div>}
            {items.length === 0 && !disabled && editing !== `new:${cat}` && <div style={{ ...muted, marginTop: 6 }}>Nothing declared.</div>}
            {items.map(i => editing === i.id
              ? <ItemForm key={i.id} initial={i} onCancel={() => setEditing(null)} onSave={(name, amount) => run(() => api.update(i.id, { category: cat, name, amount }, fy))} />
              : (
                <div key={i.id} style={{ display: 'flex', justifyContent: 'space-between', gap: 12, flexWrap: 'wrap', padding: '10px 0', borderTop: '1px solid var(--line)' }}>
                  <div>
                    <div style={{ color: 'var(--txt)' }}>{i.name} · {fmtCurrency(i.amount)}</div>
                    <div style={muted}>
                      {PROOF_LABEL[i.proofStatus]}{i.proofStatus === 'PARTIAL' && ` (${fmtCurrency(i.acceptedAmount)} accepted)`}
                      {i.reviewComment && ` · "${i.reviewComment}"`}
                    </div>
                    {proofs[i.id] && proofs[i.id].map(p => (
                      <button key={p.id} onClick={() => run(() => api.openProof(p.id, p.fileName))} style={{ display: 'block', background: 'none', border: 'none', color: 'var(--brand)', cursor: 'pointer', padding: 0, fontSize: 12 }}>{p.fileName}</button>
                    ))}
                  </div>
                  <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                    {i.proofCount > 0 && !adminMode && (
                      <BtnGhost onClick={() => toggleProofs(i.id)}><Paperclip size={14} /> {i.proofCount}</BtnGhost>
                    )}
                    {canProve && (
                      <label style={{ cursor: 'pointer', color: 'var(--brand)', fontSize: 13, display: 'inline-flex', alignItems: 'center', gap: 4 }}>
                        <Upload size={14} /> Upload proof
                        <input type="file" hidden onChange={e => { const f = e.target.files?.[0]; if (f) run(() => api.uploadProof!(i.id, f, fy)); e.target.value = ''; }} />
                      </label>
                    )}
                    {canEdit && <button aria-label="Edit" onClick={() => setEditing(i.id)} style={iconBtn}><Pencil size={15} /></button>}
                    {canEdit && <button aria-label="Delete" onClick={() => run(() => api.remove(i.id, fy))} style={iconBtn}><Trash2 size={15} /></button>}
                  </div>
                </div>
              ))}
            {editing === `new:${cat}` && <ItemForm onCancel={() => setEditing(null)} onSave={(name, amount) => run(() => api.add({ category: cat, name, amount }, fy))} />}
          </div>
        );
      })}

      <SectionHead title="My Declarations" />
      <div style={{ ...panelStyle, marginBottom: 20 }}>
        <table className="nf-pr-table" style={{ width: '100%', borderCollapse: 'collapse' }}>
          <thead><tr>{['Declarations', 'Amount declared', 'Proofs submitted', 'Amount rejected', 'Amount accepted'].map((h, i) => <th key={h} style={{ ...thStyle, ...(i ? right : {}) }}>{h}</th>)}</tr></thead>
          <tbody><tr>
            <td style={tdStyle}>{data.summary.count}</td>
            <td style={{ ...tdStyle, ...right }}>{fmtCurrency(data.summary.declared)}</td>
            <td style={{ ...tdStyle, ...right }}>{data.summary.proofsSubmitted}</td>
            <td style={{ ...tdStyle, ...right }}>{fmtCurrency(data.summary.rejected)}</td>
            <td style={{ ...tdStyle, ...right }}>{fmtCurrency(data.summary.accepted)}</td>
          </tr></tbody>
        </table>
      </div>

      <SectionHead title="Monthly Tax Deduction" />
      <div style={{ ...panelStyle, overflowX: 'auto', marginBottom: 20 }}>
        {tax.data
          ? <table className="nf-pr-table" style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead><tr>{['Month', 'Type', 'TDS', 'Cumulative'].map((h, i) => <th key={h} style={{ ...thStyle, ...(i > 1 ? right : {}) }}>{h}</th>)}</tr></thead>
              <tbody>{tax.data.months.map(m => (
                <tr key={m.period}><td style={tdStyle}>{m.label}</td><td style={tdStyle}>{m.actual ? 'Actual' : 'Projected'}</td>
                  <td style={{ ...tdStyle, ...right }}>{fmtCurrency(m.tds)}</td><td style={{ ...tdStyle, ...right }}>{fmtCurrency(m.cumulativeTds)}</td></tr>
              ))}</tbody>
            </table>
          : <div style={{ padding: 16 }}><Status error={tax.error} /></div>}
      </div>
    </>
  );
}

const iconBtn = { background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt-mut)', padding: 4 } as const;

function ItemForm({ initial, onSave, onCancel }: { initial?: { name: string; amount: number }; onSave: (name: string, amount: number) => void; onCancel: () => void }) {
  const [name, setName] = useState(initial?.name ?? '');
  const [amount, setAmount] = useState(initial ? String(initial.amount) : '');
  return (
    <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center', padding: '10px 0', borderTop: '1px solid var(--line)' }}>
      <input aria-label="Name" placeholder="e.g. PPF, ELSS, LIC" value={name} onChange={e => setName(e.target.value)} style={{ ...inputStyle, flex: '2 1 180px', width: 'auto' }} />
      <input aria-label="Amount" type="number" min={0} placeholder="Amount" value={amount} onChange={e => setAmount(e.target.value)} style={{ ...inputStyle, flex: '1 1 120px', width: 'auto' }} />
      <BtnPrimary onClick={() => onSave(name, Number(amount))} disabled={!name.trim() || amount === '' || Number(amount) < 0}>Save</BtnPrimary>
      <BtnGhost onClick={onCancel}>Cancel</BtnGhost>
    </div>
  );
}

// ── US-F12 Previous Employment Income ───────────────────────────────────────

const PREV_COLS = ['Gross', 'Basic', 'HRA', 'Employee PF', 'ESI', 'LWF', 'LWF Employer', 'NPS Employer', 'Professional Tax', 'Income Tax', 'Other Tax-Free', 'Other Taxable'];
const PAGE_SIZE = 6;

export function PreviousIncomePanel({ api, admin = false }: { api: TaxApi; admin?: boolean }) {
  const [fy, setFy] = useState(currentFy());
  const [rev, setRev] = useState(0);
  const [page, setPage] = useState(0);
  const [draft, setDraft] = useState<Record<string, number[]>>({});
  const [msg, setMsg] = useState<string | null>(null);
  const [help, setHelp] = useState(false);
  const { data, error } = useLoad<PrevGrid>(() => api.previous(fy), [api, fy, rev]);
  if (!data) return <Status error={error} />;

  const pages = Math.max(1, Math.ceil(data.rows.length / PAGE_SIZE));
  const rows = data.rows.slice(page * PAGE_SIZE, (page + 1) * PAGE_SIZE);
  const valuesOf = (period: string, base: number[]) => draft[period] ?? base;
  const dirty = Object.keys(draft).length > 0;

  const act = async (fn: () => Promise<PrevGrid>, ok: string) => {
    setMsg(null);
    try { await fn(); setDraft({}); setRev(r => r + 1); setMsg(ok); } catch (e) { setMsg(e instanceof Error ? e.message : 'Something went wrong'); }
  };

  return (
    <>
      <SectionHead title="Previous Employment Income" action={<FySelect value={fy} onChange={y => { setFy(y); setPage(0); setDraft({}); }} />} />
      <div style={{ ...muted, marginBottom: 12 }}>
        Enter your income and tax from your previous employer for the months before you joined.{' '}
        <button onClick={() => setHelp(h => !h)} style={{ background: 'none', border: 'none', color: 'var(--brand)', cursor: 'pointer', padding: 0, fontSize: 13 }}>How to fill this?</button>
        {help && <div style={{ marginTop: 6 }}>Copy each month from your last payslips or Form 16 Part B. Anything you submit stays "Pending approval" until a Super Admin approves it; only approved months count in your tax.</div>}
      </div>
      {!data.applicable
        ? <div style={{ ...panelStyle, padding: 24, ...muted }}>This section doesn't apply: you joined at the start of this financial year.</div>
        : (
          <>
            <div style={{ ...panelStyle, overflowX: 'auto', marginBottom: 12 }}>
              <table className="nf-pr-table" style={{ width: '100%', borderCollapse: 'collapse' }}>
                <thead><tr>{['Month', 'Status', ...PREV_COLS].map((h, i) => <th key={h} style={{ ...thStyle, ...(i > 1 ? right : {}) }}>{h}</th>)}</tr></thead>
                <tbody>
                  {rows.map(r => (
                    <tr key={r.period}>
                      <td style={tdStyle}>{r.label}</td>
                      <td style={tdStyle}>{r.status === 'PENDING' ? 'Pending approval' : r.status === 'APPROVED' ? 'Approved' : 'Not submitted'}</td>
                      {valuesOf(r.period, r.values).map((v, c) => (
                        <td key={c} style={tdStyle}>
                          <input aria-label={`${r.label} ${PREV_COLS[c]}`} type="number" min={0} value={v}
                            onChange={e => setDraft(d => { const next = [...valuesOf(r.period, r.values)]; next[c] = Number(e.target.value); return { ...d, [r.period]: next }; })}
                            style={{ ...inputStyle, width: 96, textAlign: 'right' }} />
                        </td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div style={{ display: 'flex', gap: 10, alignItems: 'center', flexWrap: 'wrap' }}>
              <BtnGhost onClick={() => setPage(p => Math.max(0, p - 1))} disabled={page === 0}>Previous</BtnGhost>
              <span style={muted}>Page {page + 1} of {pages}</span>
              <BtnGhost onClick={() => setPage(p => Math.min(pages - 1, p + 1))} disabled={page >= pages - 1}>Next</BtnGhost>
              <span style={{ flex: 1 }} />
              {admin && data.rows.some(r => r.status === 'PENDING') && <BtnGhost onClick={() => act(() => api.approvePrevious!(fy), 'Approved')}>Approve pending months</BtnGhost>}
              <BtnPrimary disabled={!dirty} onClick={() => act(() => api.savePrevious(Object.entries(draft).map(([period, values]) => ({ period, values })), fy), admin ? 'Saved and approved' : 'Submitted for approval')}>
                {admin ? 'Save' : 'Submit for approval'}
              </BtnPrimary>
            </div>
          </>
        )}
      {msg && <div role="status" style={{ ...muted, marginTop: 10 }}>{msg}</div>}
    </>
  );
}

// ── US-F13 Forms ────────────────────────────────────────────────────────────

/** Own forms (token only) or, for a Super Admin, one employee's forms (uid). */
export function FormsPanel({ token, api, admin = false }: { token: string; api: TaxApi; admin?: boolean }) {
  const [fy, setFy] = useState(currentFy());
  const [msg, setMsg] = useState<string | null>(null);
  const pub = useLoad<{ published: boolean }>(() => (admin ? Promise.resolve({ published: true }) : selfTaxApi(token).form130Published(fy)), [token, fy, admin]);
  const published = pub.data?.published ?? false;
  const go = async (fn: () => Promise<void>) => { setMsg(null); try { await fn(); } catch (e) { setMsg(e instanceof Error ? e.message : 'Download failed'); } };
  return (
    <>
      <SectionHead title="Form 130" action={<FySelect value={fy} onChange={setFy} />} />
      <div style={{ ...panelStyle, padding: '16px 20px', marginBottom: 20 }}>
        <div style={muted}>Form 130 (formerly Form 16). The PDF password is your PAN in uppercase.</div>
        <div style={{ display: 'flex', gap: 12, alignItems: 'center', marginTop: 12, flexWrap: 'wrap' }}>
          <BtnPrimary disabled={!published} onClick={() => go(() => api.form130(fy))}><Download size={14} style={{ marginRight: 6 }} />Download Form 130</BtnPrimary>
          {!published && <span style={muted}>Not yet available</span>}
          <a href="/myfinances/tax/tax-filing" style={{ color: 'var(--brand)', fontSize: 13 }}>File ITR</a>
        </div>
      </div>
      <SectionHead title="Form 124" />
      <div style={{ ...panelStyle, padding: '16px 20px' }}>
        <div style={muted}>Form 124 (formerly 12BB) lists your current-year declarations.</div>
        <div style={{ marginTop: 12 }}><BtnPrimary onClick={() => go(() => api.form124(currentFy()))}><Download size={14} style={{ marginRight: 6 }} />Download Form 124</BtnPrimary></div>
      </div>
      {msg && <div role="alert" style={{ color: 'var(--err, #b91c1c)', fontSize: 13, marginTop: 10 }}>{msg}</div>}
    </>
  );
}

// ── US-F14 Tax Filing / Tax Saving Investment ──────────────────────────────

function PartnerCard({ p }: { p: Partner }) {
  return (
    <div style={{ ...panelStyle, padding: '16px 20px', marginBottom: 14 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12, flexWrap: 'wrap' }}>
        <div>
          <strong style={{ color: 'var(--txt)' }}>{p.name}</strong>{p.featured && <span style={{ marginLeft: 8, fontSize: 11, fontWeight: 800, color: 'var(--ok)' }}>FEATURED</span>}
          {p.description && <div style={muted}>{p.description}</div>}
          {p.couponCode && <div style={{ marginTop: 6, fontSize: 13 }}>Coupon code: <strong>{p.couponCode}</strong></div>}
          {p.steps && <ol style={{ ...muted, margin: '8px 0 0', paddingLeft: 18 }}>{p.steps.split('\n').filter(Boolean).map((s, i) => <li key={i}>{s}</li>)}</ol>}
        </div>
        {/* Opens in a new tab. No employee data is passed in the link. */}
        <a href={p.url} target="_blank" rel="noopener noreferrer"><BtnPrimary><ExternalLink size={14} style={{ marginRight: 6 }} />{p.kind === 'FILING' ? 'File now' : 'Invest now'}</BtnPrimary></a>
      </div>
    </div>
  );
}

export function PartnersPanel({ kind, filing }: { kind: 'FILING' | 'SAVING'; filing: Filing | null }) {
  if (!filing) return <Status />;
  const list = kind === 'FILING' ? filing.filing : filing.saving;
  return (
    <>
      <SectionHead title={kind === 'FILING' ? 'Tax Filing' : 'Tax Saving Investment'} />
      {kind === 'FILING' && <div style={{ ...panelStyle, padding: '12px 18px', marginBottom: 16, ...muted }}>
        ITR due date: <strong>{filing.itrDueDate ? fmtDate(filing.itrDueDate) : 'not announced yet'}</strong>
      </div>}
      {list.length === 0 && <div style={{ ...panelStyle, padding: 24, ...muted }}>No partners are available right now.</div>}
      {list.map(p => <PartnerCard key={p.id} p={p} />)}
    </>
  );
}
