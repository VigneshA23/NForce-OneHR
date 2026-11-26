import { useCallback, useEffect, useMemo, useState } from 'react';
import { CalendarCheck } from 'lucide-react';
import PageTitleBlock from '../components/PageTitleBlock';
import { useAuthStore } from '../store/authStore';
import { useToast } from '../context/ToastContext';
import { useCan } from '../lib/access';
import {
  compOffApi as api,
  type CompOffCredit, type CompOffDetectResult, type CompOffEvent, type CompOffReport, type CompOffRules,
  type CompOffSettings, type ReportFilter,
} from '../api/compOff';

const input: React.CSSProperties = { background: 'var(--shell)', border: '1px solid var(--line2)', borderRadius: 6, padding: '8px 10px', color: 'var(--txt)', fontSize: 13, boxSizing: 'border-box', outline: 'none' };
const label: React.CSSProperties = { display: 'block', fontSize: 11, fontWeight: 600, color: 'var(--txt-mut)', marginBottom: 5, textTransform: 'uppercase', letterSpacing: '.06em' };
const th: React.CSSProperties = { textAlign: 'left', fontSize: 11, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.05em', padding: '8px 10px', borderBottom: '1px solid var(--line2)', whiteSpace: 'nowrap' };
const td: React.CSSProperties = { padding: '9px 10px', fontSize: 13, color: 'var(--txt)', borderBottom: '1px solid var(--line2)', whiteSpace: 'nowrap' };
const btn: React.CSSProperties = { background: 'var(--accent, #3b82f6)', color: '#fff', border: 'none', borderRadius: 6, padding: '8px 14px', fontSize: 13, fontWeight: 600, cursor: 'pointer' };
const btnGhost: React.CSSProperties = { ...btn, background: 'transparent', color: 'var(--txt)', border: '1px solid var(--line2)' };
const card: React.CSSProperties = { border: '1px solid var(--line2)', borderRadius: 10, padding: 16 };
const note: React.CSSProperties = { fontSize: 13, color: 'var(--txt-mut)', margin: '0 0 12px' };
const COLOR: Record<string, string> = { AVAILABLE: '#3FB27F', PENDING_APPROVAL: '#E0A93B', REJECTED: '#E5534B', EXPIRED: '#8A8F98' };

const iso = (d: Date) => d.toISOString().slice(0, 10);
const num = (n: number) => Number(n.toFixed(2)).toString();

function Badge({ text }: { text: string }) {
  const c = COLOR[text] ?? 'var(--txt-mut)';
  return <span style={{ color: c, border: `1px solid ${c}`, borderRadius: 10, padding: '2px 8px', fontSize: 11, fontWeight: 600 }}>{text.replace('_', ' ')}</span>;
}

type Tab = 'summary' | 'approvals' | 'credits' | 'settings';
interface TabProps { token: string; fail: (e: unknown) => void }

export default function CompOffManagementPage() {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const can = useCan();
  const canApprove = can('leave', 'APPROVE', 'TEAM');
  const canConfigure = can('leave', 'CONFIGURE');
  const canSetPolicy = can('compensation_policy', 'CONFIGURE');
  const [tab, setTab] = useState<Tab>('summary');
  const fail = useCallback((e: unknown) => showToast('error', e instanceof Error ? e.message : 'Something went wrong'), [showToast]);

  const tabs: [Tab, string][] = [['summary', 'Summary & reports']];
  if (canApprove) tabs.push(['approvals', 'Credit approvals']);
  tabs.push(['credits', canConfigure ? 'Credits & adjustments' : 'Credits']);
  tabs.push(['settings', canSetPolicy ? 'Policy & rules' : 'Rules']);

  return (
    <div>
      <PageTitleBlock card icon={CalendarCheck} style={{ marginBottom: 22 }}>
        <h1 style={{ fontFamily: 'Inter, sans-serif', fontSize: 20, fontWeight: 700, color: 'var(--txt)', margin: 0 }}>Comp-Off Management</h1>
        <p style={{ fontSize: 13, color: 'var(--txt-mut)', marginTop: 4 }}>
          Monitor earned, available, utilized, pending and expired Comp-Off, approve credits, correct balances and set the policy.
        </p>
      </PageTitleBlock>

      <div style={{ display: 'flex', gap: 8, marginBottom: 18, flexWrap: 'wrap' }}>
        {tabs.map(([k, l]) => <button key={k} onClick={() => setTab(k)} style={tab === k ? btn : btnGhost}>{l}</button>)}
      </div>

      {tab === 'summary' && <SummaryTab token={token} fail={fail} />}
      {tab === 'approvals' && <ApprovalsTab token={token} fail={fail} />}
      {tab === 'credits' && <CreditsTab token={token} fail={fail} canAdjust={canConfigure} />}
      {tab === 'settings' && <SettingsTab token={token} fail={fail} canRules={canConfigure} canPolicy={canSetPolicy} />}
    </div>
  );
}

// ---------------------------------------------------------------- summary & reports
function SummaryTab({ token, fail }: TabProps) {
  const [filter, setFilter] = useState<ReportFilter>({});
  const [report, setReport] = useState<CompOffReport | null>(null);
  // Dropdown options come from the unfiltered view so narrowing a filter never hides the other choices.
  const [options, setOptions] = useState<CompOffReport | null>(null);

  const load = useCallback(() => {
    api.report(token, filter).then(r => { setReport(r); setOptions(o => o ?? (Object.values(filter).some(Boolean) ? o : r)); }).catch(fail);
  }, [token, filter, fail]);
  useEffect(load, [load]);
  useEffect(() => { api.report(token, {}).then(setOptions).catch(() => undefined); }, [token]);

  const set = (k: keyof ReportFilter) => (e: React.ChangeEvent<HTMLSelectElement | HTMLInputElement>) =>
    setFilter(f => ({ ...f, [k]: e.target.value || undefined }));

  const departments = useMemo(() => {
    const m = new Map<string, string>();
    options?.employees.forEach(e => e.departmentId && m.set(e.departmentId, e.departmentName));
    return [...m];
  }, [options]);
  const managers = useMemo(() => {
    const m = new Map<string, string>();
    options?.employees.forEach(e => e.managerUserId && e.managerName && m.set(e.managerUserId, e.managerName));
    return [...m];
  }, [options]);

  return (
    <div>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 14, alignItems: 'end', marginBottom: 16 }}>
        <div><label style={label}>Employee</label>
          <select style={input} value={filter.employeeId ?? ''} onChange={set('employeeId')}>
            <option value="">All</option>
            {options?.employees.map(e => <option key={e.employeeUserId} value={e.employeeUserId}>{e.employeeName}</option>)}
          </select></div>
        <div><label style={label}>Department</label>
          <select style={input} value={filter.departmentId ?? ''} onChange={set('departmentId')}>
            <option value="">All</option>{departments.map(([id, name]) => <option key={id} value={id}>{name}</option>)}
          </select></div>
        <div><label style={label}>Manager</label>
          <select style={input} value={filter.managerId ?? ''} onChange={set('managerId')}>
            <option value="">All</option>{managers.map(([id, name]) => <option key={id} value={id}>{name}</option>)}
          </select></div>
        <div><label style={label}>Earned from</label><input type="date" style={input} value={filter.from ?? ''} onChange={set('from')} /></div>
        <div><label style={label}>Earned to</label><input type="date" style={input} value={filter.to ?? ''} max={iso(new Date())} onChange={set('to')} /></div>
        <button style={btnGhost} onClick={() => setFilter({})}>Clear</button>
        <button style={btn} onClick={() => api.exportXlsx(token, filter).catch(fail)}>Export to Excel</button>
      </div>

      <h2 style={h2}>By department</h2>
      <Table head={['Department', 'Employees', 'Earned', 'Available', 'Utilized', 'Pending', 'Expired', 'Deducted']}
        rows={(report?.departments ?? []).map(d => [d.departmentName, d.employees, num(d.earned), num(d.available), num(d.utilized), num(d.pending), num(d.expired), num(d.adjusted)])}
        empty="No Comp-Off recorded for these filters." />

      <h2 style={{ ...h2, marginTop: 24 }}>By employee</h2>
      <Table head={['Code', 'Employee', 'Department', 'Manager', 'Earned', 'Available', 'Utilized', 'Pending', 'Expired', 'Deducted']}
        rows={(report?.employees ?? []).map(e => [e.employeeCode ?? '—', e.employeeName, e.departmentName, e.managerName ?? '—', num(e.earned), num(e.available), num(e.utilized), num(e.pending), num(e.expired), num(e.adjusted)])}
        empty="No Comp-Off recorded for these filters." />
    </div>
  );
}

const h2: React.CSSProperties = { fontSize: 15, fontWeight: 700, color: 'var(--txt)', margin: '0 0 10px' };

function Table({ head, rows, empty }: { head: string[]; rows: (string | number)[][]; empty: string }) {
  if (rows.length === 0) return <p style={note}>{empty}</p>;
  return (
    <div style={{ overflowX: 'auto' }}>
      <table style={{ borderCollapse: 'collapse', width: '100%' }}>
        <thead><tr>{head.map(h => <th key={h} style={th}>{h}</th>)}</tr></thead>
        <tbody>{rows.map((r, i) => <tr key={i}>{r.map((c, j) => <td key={j} style={td}>{c}</td>)}</tr>)}</tbody>
      </table>
    </div>
  );
}

// ---------------------------------------------------------------- approvals
function ApprovalsTab({ token, fail }: TabProps) {
  const { showToast } = useToast();
  const [rows, setRows] = useState<CompOffCredit[]>([]);
  const load = useCallback(() => { api.approvals(token).then(setRows).catch(fail); }, [token, fail]);
  useEffect(load, [load]);

  const decide = (c: CompOffCredit, decision: 'APPROVE' | 'REJECT') => {
    let comment: string | undefined;
    if (decision === 'REJECT') {
      comment = window.prompt('Reason for rejecting this Comp-Off credit?') ?? undefined;
      if (!comment?.trim()) return;
    }
    api.decide(token, c.id, decision, comment).then(() => { showToast('success', decision === 'APPROVE' ? 'Credit approved' : 'Credit rejected'); load(); }).catch(fail);
  };

  return (
    <div>
      <p style={note}>Comp-Off earned by your team that needs approval before it can be used. Leave requests that spend Comp-Off are approved in the Approval Center like any other leave.</p>
      {rows.length === 0 ? <p style={note}>Nothing waiting for approval.</p> : (
        <table style={{ borderCollapse: 'collapse', width: '100%' }}>
          <thead><tr>{['Employee', 'Department', 'Worked on', 'Credit', 'Reason', ''].map(h => <th key={h} style={th}>{h}</th>)}</tr></thead>
          <tbody>{rows.map(c => (
            <tr key={c.id}>
              <td style={td}>{c.employeeName}</td><td style={td}>{c.departmentName ?? '—'}</td><td style={td}>{c.workDate ?? '—'}</td>
              <td style={td}>{num(c.creditDays)} day</td><td style={{ ...td, whiteSpace: 'normal' }}>{c.reason ?? '—'}</td>
              <td style={{ ...td, display: 'flex', gap: 8 }}>
                <button style={btn} onClick={() => decide(c, 'APPROVE')}>Approve</button>
                <button style={btnGhost} onClick={() => decide(c, 'REJECT')}>Reject</button>
              </td>
            </tr>
          ))}</tbody>
        </table>
      )}
    </div>
  );
}

// ---------------------------------------------------------------- credits, adjustments, audit
function CreditsTab({ token, fail, canAdjust }: TabProps & { canAdjust: boolean }) {
  const { showToast } = useToast();
  const today = new Date();
  const [from, setFrom] = useState(iso(new Date(today.getFullYear(), today.getMonth() - 2, 1)));
  const [to, setTo] = useState(iso(today));
  const [credits, setCredits] = useState<CompOffCredit[]>([]);
  const [detect, setDetect] = useState<CompOffDetectResult | null>(null);
  const [audit, setAudit] = useState<{ name: string; items: CompOffEvent[] } | null>(null);
  const [adj, setAdj] = useState<{ employeeUserId: string; days: string; reason: string }>({ employeeUserId: '', days: '', reason: '' });
  const [employees, setEmployees] = useState<{ id: string; name: string }[]>([]);

  const load = useCallback(() => {
    api.credits(token, { from, to }).then(setCredits).catch(fail);
  }, [token, from, to, fail]);
  useEffect(load, [load]);
  useEffect(() => {
    api.report(token, {}).then(r => setEmployees(r.employees.map(e => ({ id: e.employeeUserId, name: e.employeeName })))).catch(() => undefined);
  }, [token]);

  const runDetect = () => api.detect(token, from, to)
    .then(r => { setDetect(r); showToast('success', `${r.created} eligible day(s) found, ${r.autoCredited} credited automatically`); load(); }).catch(fail);

  const submitAdjust = (e: React.FormEvent) => {
    e.preventDefault();
    const d = Number(adj.days);
    if (!adj.employeeUserId || !Number.isFinite(d) || d === 0 || !adj.reason.trim()) { showToast('error', 'Pick an employee, a non-zero number of days and a reason'); return; }
    api.adjust(token, adj.employeeUserId, d, adj.reason.trim())
      .then(() => { showToast('success', 'Balance adjusted'); setAdj({ employeeUserId: '', days: '', reason: '' }); load(); }).catch(fail);
  };

  return (
    <div>
      {canAdjust && (
        <form onSubmit={submitAdjust} style={{ ...card, display: 'flex', flexWrap: 'wrap', gap: 14, alignItems: 'end', marginBottom: 20 }}>
          <div><label style={label}>Employee</label>
            <select style={input} value={adj.employeeUserId} onChange={e => setAdj(a => ({ ...a, employeeUserId: e.target.value }))}>
              <option value="">Select…</option>{employees.map(e => <option key={e.id} value={e.id}>{e.name}</option>)}
            </select></div>
          <div><label style={label}>Days (+ add / − deduct)</label><input style={{ ...input, width: 110 }} type="number" step="0.5" value={adj.days} onChange={e => setAdj(a => ({ ...a, days: e.target.value }))} /></div>
          <div style={{ flex: '1 1 220px' }}><label style={label}>Reason (audited)</label><input style={{ ...input, width: '100%' }} value={adj.reason} onChange={e => setAdj(a => ({ ...a, reason: e.target.value }))} /></div>
          <button type="submit" style={btn}>Apply adjustment</button>
          <p style={{ ...note, width: '100%', margin: 0 }}>Employees without any credit yet are not in this list — detect their eligible days below first.</p>
        </form>
      )}

      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 14, alignItems: 'end', marginBottom: 14 }}>
        <div><label style={label}>Earned from</label><input type="date" style={input} value={from} onChange={e => setFrom(e.target.value)} /></div>
        <div><label style={label}>Earned to</label><input type="date" style={input} value={to} max={iso(today)} onChange={e => setTo(e.target.value)} /></div>
        {canAdjust && <button style={btnGhost} onClick={runDetect}>Detect eligible days from attendance</button>}
      </div>

      {detect && detect.skipped.length > 0 && (
        <div style={{ border: '1px solid #E0A93B', borderRadius: 8, padding: 12, marginBottom: 16 }}>
          <strong style={{ fontSize: 13 }}>{detect.skipped.length} record(s) excluded until corrected</strong>
          <ul style={{ margin: '8px 0 0', paddingLeft: 18, fontSize: 12, color: 'var(--txt-mut)' }}>
            {detect.skipped.slice(0, 20).map((s, i) => <li key={i}>{s.employeeName} · {s.workDate} · {s.reason}</li>)}
          </ul>
        </div>
      )}

      {credits.length === 0 ? <p style={note}>No credits in this range.</p> : (
        <div style={{ overflowX: 'auto' }}>
          <table style={{ borderCollapse: 'collapse', width: '100%' }}>
            <thead><tr>{['Employee', 'Department', 'Source', 'Worked on', 'Earned on', 'Valid until', 'Credit', 'Used', 'Expired', 'Remaining', 'Status', ''].map(h => <th key={h} style={th}>{h}</th>)}</tr></thead>
            <tbody>{credits.map(c => (
              <tr key={c.id}>
                <td style={td}>{c.employeeName}</td><td style={td}>{c.departmentName ?? '—'}</td>
                <td style={td}>{c.source === 'MANUAL' ? 'Adjustment' : 'Worked'}</td>
                <td style={td}>{c.workDate ?? '—'}</td><td style={td}>{c.earnedOn}</td><td style={td}>{c.expiresOn}</td>
                <td style={td}>{num(c.creditDays)}</td><td style={td}>{num(c.usedDays + c.deductedDays)}</td><td style={td}>{num(c.expiredDays)}</td>
                <td style={td}>{c.status === 'AVAILABLE' ? num(c.remainingDays) : '—'}</td>
                <td style={td}><Badge text={c.status} /></td>
                <td style={td}><button style={btnGhost} onClick={() => api.history(token, c.employeeUserId).then(items => setAudit({ name: c.employeeName, items })).catch(fail)}>Audit</button></td>
              </tr>
            ))}</tbody>
          </table>
        </div>
      )}

      {audit && (
        <div role="dialog" aria-modal="true" onClick={() => setAudit(null)}
          style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,.5)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 1000, padding: 16 }}>
          <div onClick={e => e.stopPropagation()} style={{ background: 'var(--shell)', border: '1px solid var(--line2)', borderRadius: 10, padding: 20, width: 'min(720px, 100%)', maxHeight: '80vh', overflow: 'auto' }}>
            <h2 style={h2}>Audit history — {audit.name}</h2>
            <Table head={['When', 'Event', 'Days', 'Note', 'By']}
              rows={audit.items.map(i => [i.createdAt.replace('T', ' ').slice(0, 16), i.event.replace(/_/g, ' ').toLowerCase(), i.days == null ? '—' : num(i.days), i.note ?? '—', i.actorName ?? '—'])}
              empty="No history." />
            <div style={{ marginTop: 14 }}><button style={btnGhost} onClick={() => setAudit(null)}>Close</button></div>
          </div>
        </div>
      )}
    </div>
  );
}

// ---------------------------------------------------------------- policy & rules
function SettingsTab({ token, fail, canRules, canPolicy }: TabProps & { canRules: boolean; canPolicy: boolean }) {
  const { showToast } = useToast();
  const [s, setS] = useState<CompOffSettings | null>(null);
  const load = useCallback(() => { api.settings(token).then(setS).catch(fail); }, [token, fail]);
  useEffect(load, [load]);
  if (!s) return <p style={note}>Loading…</p>;

  const mode = s.overtimeEnabled && s.compOffEnabled ? 'BOTH' : s.compOffEnabled ? 'COMP_OFF' : 'OT';
  const setMode = (m: string) => {
    const ot = m !== 'COMP_OFF';
    const co = m !== 'OT';
    api.updatePolicy(token, ot, co).then(r => { setS(r); showToast('success', 'Company policy updated'); }).catch(fail);
  };

  const patch = (p: Partial<CompOffRules>) => setS(prev => prev ? { ...prev, ...p } : prev);
  const saveRules = (e: React.FormEvent) => {
    e.preventDefault();
    const { overtimeEnabled: _o, compOffEnabled: _c, updatedAt: _u, ...rules } = s;
    api.updateRules(token, rules).then(r => { setS(r); showToast('success', 'Comp-Off rules saved'); }).catch(fail);
  };

  const check = (k: keyof CompOffRules, text: string) => (
    <label style={{ display: 'flex', gap: 8, alignItems: 'center', fontSize: 13, color: 'var(--txt)' }}>
      <input type="checkbox" disabled={!canRules} checked={Boolean(s[k])} onChange={e => patch({ [k]: e.target.checked })} /> {text}
    </label>
  );
  const numField = (k: keyof CompOffRules, text: string, unit: string) => (
    <div><label style={label}>{text}</label>
      <input type="number" min={0} disabled={!canRules} style={{ ...input, width: 120 }} value={Number(s[k])} onChange={e => patch({ [k]: Number(e.target.value) })} />
      <span style={{ fontSize: 12, color: 'var(--txt-mut)', marginLeft: 6 }}>{unit}</span></div>
  );

  return (
    <div style={{ display: 'grid', gap: 22, maxWidth: 760 }}>
      <section style={card}>
        <h2 style={h2}>Company policy for extra working hours</h2>
        <p style={note}>
          Choose how eligible extra hours are compensated. Overtime keeps its existing calculation and approval flow unchanged.
          With both on, each employee picks Overtime or Comp-Off per eligible day.
        </p>
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
          {([['OT', 'Overtime only'], ['COMP_OFF', 'Comp-Off only'], ['BOTH', 'Both (employee chooses)']] as [string, string][]).map(([k, l]) => (
            <button key={k} disabled={!canPolicy} onClick={() => mode !== k && setMode(k)} style={mode === k ? btn : btnGhost}>{l}</button>
          ))}
        </div>
        {!canPolicy && <p style={{ ...note, marginTop: 10, marginBottom: 0 }}>Only a Super Admin can change the company policy.</p>}
      </section>

      <form onSubmit={saveRules} style={card}>
        <h2 style={h2}>Comp-Off eligibility and validity</h2>
        <div style={{ display: 'grid', gap: 10, marginBottom: 16 }}>
          {check('eligibleWeeklyOff', 'Work on a weekly off earns Comp-Off')}
          {check('eligibleHoliday', 'Work on a holiday earns Comp-Off')}
          {check('includeWeekdayExtra', 'Extra hours beyond the shift on a working day also count')}
          {check('requireCreditApproval', 'New credits need manager / HR approval before they can be used')}
          {check('allowPartialDay', 'Allow half-day Comp-Off leave')}
        </div>
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 18, marginBottom: 16 }}>
          {numField('minMinutesHalfDay', 'Minimum for ½ day', 'minutes')}
          {numField('minMinutesFullDay', 'Minimum for 1 day', 'minutes')}
          {numField('validityDays', 'Credit validity', 'days')}
          {numField('expiryNoticeDays', 'Expiry reminder', 'days before')}
        </div>
        <p style={note}>
          Credit formula: eligible minutes ≥ full-day minimum earns 1 day; ≥ half-day minimum earns ½ day; below that, nothing.
          Unused credit expires automatically when its validity ends.
        </p>
        {canRules && <button type="submit" style={btn}>Save rules</button>}
      </form>
    </div>
  );
}
