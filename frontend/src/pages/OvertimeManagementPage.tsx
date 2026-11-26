import { useCallback, useEffect, useMemo, useState } from 'react';
import { Timer } from 'lucide-react';
import PageTitleBlock from '../components/PageTitleBlock';
import { useAuthStore } from '../store/authStore';
import { useToast } from '../context/ToastContext';
import { useCan } from '../lib/access';
import {
  overtimeManagementApi as api,
  type OtAdjustment, type OtCalculation, type OtDayType, type OtHistory, type OtPayrollSummary,
  type OtRateRule, type OtRunResult, type OtSettings,
} from '../api/overtimeManagement';

const input: React.CSSProperties = { background: 'var(--shell)', border: '1px solid var(--line2)', borderRadius: 6, padding: '8px 10px', color: 'var(--txt)', fontSize: 13, boxSizing: 'border-box', outline: 'none' };
const label: React.CSSProperties = { display: 'block', fontSize: 11, fontWeight: 600, color: 'var(--txt-mut)', marginBottom: 5, textTransform: 'uppercase', letterSpacing: '.06em' };
const th: React.CSSProperties = { textAlign: 'left', fontSize: 11, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.05em', padding: '8px 10px', borderBottom: '1px solid var(--line2)', whiteSpace: 'nowrap' };
const td: React.CSSProperties = { padding: '9px 10px', fontSize: 13, color: 'var(--txt)', borderBottom: '1px solid var(--line2)', whiteSpace: 'nowrap' };
const btn: React.CSSProperties = { background: 'var(--accent, #3b82f6)', color: '#fff', border: 'none', borderRadius: 6, padding: '8px 14px', fontSize: 13, fontWeight: 600, cursor: 'pointer' };
const btnGhost: React.CSSProperties = { ...btn, background: 'transparent', color: 'var(--txt)', border: '1px solid var(--line2)' };

const DAY_LABEL: Record<OtDayType, string> = { ANY: 'Any day', WEEKDAY: 'Working day', WEEKLY_OFF: 'Weekly off', HOLIDAY: 'Holiday' };
const STATUS_COLOR: Record<string, string> = { CALCULATED: '#E0A93B', APPROVED: '#3FB27F', REJECTED: '#E5534B', PENDING: '#E0A93B', APPLIED: '#3FB27F' };

const iso = (d: Date) => d.toISOString().slice(0, 10);
const hrs = (m: number) => (m / 60).toFixed(2);
const money = (n: number) => n.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 });

function Badge({ text }: { text: string }) {
  const c = STATUS_COLOR[text] ?? 'var(--txt-mut)';
  return <span style={{ color: c, border: `1px solid ${c}`, borderRadius: 10, padding: '2px 8px', fontSize: 11, fontWeight: 600 }}>{text}</span>;
}

type Tab = 'calculations' | 'adjustments' | 'rates' | 'payroll';

export default function OvertimeManagementPage() {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const can = useCan();
  const canConfigure = can('overtime', 'CONFIGURE');
  const [tab, setTab] = useState<Tab>('calculations');

  const fail = useCallback((e: unknown) => showToast('error', e instanceof Error ? e.message : 'Something went wrong'), [showToast]);

  return (
    <div>
      <PageTitleBlock card icon={Timer} style={{ marginBottom: 22 }}>
        <h1 style={{ fontFamily: 'Inter, sans-serif', fontSize: 20, fontWeight: 700, color: 'var(--txt)', margin: 0 }}>Overtime Management</h1>
        <p style={{ fontSize: 13, color: 'var(--txt-mut)', marginTop: 4 }}>
          Overtime is calculated from attendance against each employee's shift. Review it, approve it for payroll, and keep an audit trail of every change.
        </p>
      </PageTitleBlock>

      <div style={{ display: 'flex', gap: 8, marginBottom: 18, flexWrap: 'wrap' }}>
        {([['calculations', 'Calculations'], ['adjustments', 'Adjustments'], ['rates', 'Rates & Settings'], ['payroll', 'Payroll']] as [Tab, string][]).map(([k, l]) => (
          <button key={k} onClick={() => setTab(k)} style={tab === k ? btn : btnGhost}>{l}</button>
        ))}
      </div>

      {tab === 'calculations' && <CalculationsTab token={token} fail={fail} />}
      {tab === 'adjustments' && <AdjustmentsTab token={token} fail={fail} canDecide={canConfigure} />}
      {tab === 'rates' && <RatesTab token={token} fail={fail} canEdit={canConfigure} />}
      {tab === 'payroll' && <PayrollTab token={token} fail={fail} />}
    </div>
  );
}

interface TabProps { token: string; fail: (e: unknown) => void }

// ---------------------------------------------------------------- calculations
function CalculationsTab({ token, fail }: TabProps) {
  const { showToast } = useToast();
  const today = new Date();
  const [from, setFrom] = useState(iso(new Date(today.getFullYear(), today.getMonth(), 1)));
  const [to, setTo] = useState(iso(today));
  const [status, setStatus] = useState('');
  const [rows, setRows] = useState<OtCalculation[]>([]);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [loading, setLoading] = useState(false);
  const [runResult, setRunResult] = useState<OtRunResult | null>(null);
  const [history, setHistory] = useState<{ row: OtCalculation; items: OtHistory[] } | null>(null);
  const [adjusting, setAdjusting] = useState<OtCalculation | null>(null);

  const load = useCallback(() => {
    setLoading(true);
    api.list(token, from, to, status || undefined)
      .then(r => { setRows(r); setSelected(new Set()); })
      .catch(fail).finally(() => setLoading(false));
  }, [token, from, to, status, fail]);
  useEffect(load, [load]);

  const calculate = () => {
    setLoading(true);
    api.run(token, from, to).then(r => { setRunResult(r); showToast('success', `${r.created} new, ${r.updated} recalculated, ${r.skipped.length} skipped`); load(); })
      .catch(e => { fail(e); setLoading(false); });
  };

  const decide = (decision: 'APPROVE' | 'REJECT') => {
    let comment: string | undefined;
    if (decision === 'REJECT') {
      comment = window.prompt('Reason for rejecting the selected overtime?') ?? undefined;
      if (!comment?.trim()) return;
    }
    api.decide(token, [...selected], decision, comment)
      .then(r => { showToast('success', `${r.updated} record(s) updated`); load(); }).catch(fail);
  };

  const openHistory = (row: OtCalculation) => api.history(token, row.id).then(items => setHistory({ row, items })).catch(fail);

  const selectable = rows.filter(r => r.status === 'CALCULATED' && !r.hasPendingAdjustment);
  const totals = useMemo(() => ({
    minutes: rows.filter(r => r.status !== 'REJECTED').reduce((s, r) => s + r.otMinutes, 0),
    amount: rows.filter(r => r.status !== 'REJECTED').reduce((s, r) => s + r.otAmount, 0),
  }), [rows]);

  return (
    <div>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 14, alignItems: 'end', marginBottom: 16 }}>
        <div><label style={label}>From</label><input type="date" style={input} value={from} onChange={e => setFrom(e.target.value)} /></div>
        <div><label style={label}>To</label><input type="date" style={input} value={to} max={iso(today)} onChange={e => setTo(e.target.value)} /></div>
        <div><label style={label}>Status</label>
          <select style={input} value={status} onChange={e => setStatus(e.target.value)}>
            <option value="">All</option><option value="CALCULATED">Awaiting review</option><option value="APPROVED">Approved</option><option value="REJECTED">Rejected</option>
          </select>
        </div>
        <button style={btn} onClick={calculate} disabled={loading}>Calculate from attendance</button>
        <button style={btnGhost} disabled={selected.size === 0} onClick={() => decide('APPROVE')}>Approve selected ({selected.size})</button>
        <button style={btnGhost} disabled={selected.size === 0} onClick={() => decide('REJECT')}>Reject selected</button>
      </div>

      <p style={{ fontSize: 13, color: 'var(--txt-mut)', margin: '0 0 10px' }}>
        {rows.length} record(s) · {hrs(totals.minutes)} h · {money(totals.amount)} (excluding rejected)
      </p>

      {runResult && runResult.skipped.length > 0 && (
        <div style={{ border: '1px solid #E0A93B', borderRadius: 8, padding: 12, marginBottom: 16 }}>
          <strong style={{ fontSize: 13 }}>{runResult.skipped.length} record(s) excluded until corrected</strong>
          <ul style={{ margin: '8px 0 0', paddingLeft: 18, fontSize: 12, color: 'var(--txt-mut)' }}>
            {runResult.skipped.slice(0, 20).map((s, i) => <li key={i}>{s.employeeName} · {s.workDate} · {s.reason}</li>)}
            {runResult.skipped.length > 20 && <li>…and {runResult.skipped.length - 20} more</li>}
          </ul>
        </div>
      )}

      <div style={{ overflowX: 'auto' }}>
        <table style={{ width: '100%', borderCollapse: 'collapse' }}>
          <thead><tr>
            <th style={th}><input type="checkbox" checked={selectable.length > 0 && selected.size === selectable.length}
              onChange={e => setSelected(e.target.checked ? new Set(selectable.map(r => r.id)) : new Set())} /></th>
            {['Emp ID', 'Employee', 'Department', 'Shift', 'OT date', 'Day', 'Actual hrs', 'OT hrs', 'Rate × ', 'OT amount', 'Status', ''].map(h => <th key={h} style={th}>{h}</th>)}
          </tr></thead>
          <tbody>
            {rows.map(r => (
              <tr key={r.id}>
                <td style={td}>{r.status === 'CALCULATED' && !r.hasPendingAdjustment &&
                  <input type="checkbox" checked={selected.has(r.id)} onChange={e => {
                    const n = new Set(selected); if (e.target.checked) n.add(r.id); else n.delete(r.id); setSelected(n);
                  }} />}</td>
                <td style={td}>{r.employeeCode ?? '—'}</td>
                <td style={td}>{r.employeeName}</td>
                <td style={td}>{r.departmentName ?? '—'}</td>
                <td style={td}>{r.shiftName ?? '—'}</td>
                <td style={td}>{r.workDate}</td>
                <td style={td}>{DAY_LABEL[r.dayType]}</td>
                <td style={td}>{hrs(r.workedMinutes)}</td>
                <td style={td}>{hrs(r.otMinutes)}{r.adjusted ? ' *' : ''}</td>
                <td style={td}>{r.multiplier}×</td>
                <td style={td}>{money(r.otAmount)}</td>
                <td style={td}><Badge text={r.status} />{r.hasPendingAdjustment ? ' · adj. pending' : ''}{r.payrollRunId ? ' · in payroll' : ''}</td>
                <td style={td}>
                  <button style={{ ...btnGhost, padding: '4px 8px', fontSize: 12 }} onClick={() => openHistory(r)}>History</button>{' '}
                  {r.status !== 'REJECTED' && !r.payrollRunId && !r.hasPendingAdjustment &&
                    <button style={{ ...btnGhost, padding: '4px 8px', fontSize: 12 }} onClick={() => setAdjusting(r)}>Adjust</button>}
                </td>
              </tr>
            ))}
            {rows.length === 0 && <tr><td style={{ ...td, color: 'var(--txt-mut)' }} colSpan={13}>{loading ? 'Loading…' : 'No overtime records for this range. Use "Calculate from attendance".'}</td></tr>}
          </tbody>
        </table>
      </div>
      <p style={{ fontSize: 11, color: 'var(--txt-mut)' }}>* manually adjusted</p>

      {history && (
        <Modal title={`History · ${history.row.employeeName} · ${history.row.workDate}`} onClose={() => setHistory(null)}>
          <table style={{ width: '100%', borderCollapse: 'collapse' }}>
            <thead><tr>{['When', 'Event', 'OT hrs', 'Amount', 'By', 'Note'].map(h => <th key={h} style={th}>{h}</th>)}</tr></thead>
            <tbody>{history.items.map(h => (
              <tr key={h.id}>
                <td style={td}>{new Date(h.createdAt).toLocaleString()}</td><td style={td}>{h.event}</td>
                <td style={td}>{h.otMinutes == null ? '—' : hrs(h.otMinutes)}</td><td style={td}>{h.otAmount == null ? '—' : money(h.otAmount)}</td>
                <td style={td}>{h.actorName ?? '—'}</td><td style={{ ...td, whiteSpace: 'normal' }}>{h.note ?? ''}</td>
              </tr>))}</tbody>
          </table>
        </Modal>
      )}
      {adjusting && <AdjustModal token={token} row={adjusting} onClose={() => setAdjusting(null)} onDone={() => { setAdjusting(null); load(); }} fail={fail} />}
    </div>
  );
}

function AdjustModal({ token, row, onClose, onDone, fail }: { token: string; row: OtCalculation; onClose: () => void; onDone: () => void; fail: (e: unknown) => void }) {
  const { showToast } = useToast();
  const [minutes, setMinutes] = useState(String(row.otMinutes));
  const [amount, setAmount] = useState('');
  const [reason, setReason] = useState('');
  const submit = () => {
    if (!reason.trim()) { showToast('error', 'A reason is required'); return; }
    api.adjust(token, row.id, {
      otMinutes: minutes === '' ? null : Number(minutes),
      otAmount: amount === '' ? null : Number(amount),
      reason,
    }).then(a => { showToast('success', a.status === 'PENDING' ? 'Adjustment sent for approval' : 'Adjustment applied'); onDone(); }).catch(fail);
  };
  return (
    <Modal title={`Adjust OT · ${row.employeeName} · ${row.workDate}`} onClose={onClose}>
      <p style={{ fontSize: 12, color: 'var(--txt-mut)' }}>Current: {row.otMinutes} min / {money(row.otAmount)}. Leave the amount blank to recompute it from the minutes.</p>
      <div style={{ display: 'grid', gap: 12 }}>
        <div><label style={label}>OT minutes</label><input style={{ ...input, width: '100%' }} type="number" min={0} value={minutes} onChange={e => setMinutes(e.target.value)} /></div>
        <div><label style={label}>OT amount (optional override)</label><input style={{ ...input, width: '100%' }} type="number" min={0} step="0.01" value={amount} onChange={e => setAmount(e.target.value)} /></div>
        <div><label style={label}>Reason (required)</label><textarea style={{ ...input, width: '100%', minHeight: 70 }} value={reason} onChange={e => setReason(e.target.value)} /></div>
        <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}><button style={btnGhost} onClick={onClose}>Cancel</button><button style={btn} onClick={submit}>Submit adjustment</button></div>
      </div>
    </Modal>
  );
}

// ---------------------------------------------------------------- adjustments
function AdjustmentsTab({ token, fail, canDecide }: TabProps & { canDecide: boolean }) {
  const { showToast } = useToast();
  const [rows, setRows] = useState<OtAdjustment[]>([]);
  const load = useCallback(() => { api.adjustments(token).then(setRows).catch(fail); }, [token, fail]);
  useEffect(load, [load]);

  const decide = (id: string, d: 'APPROVE' | 'REJECT') => {
    const comment = d === 'REJECT' ? (window.prompt('Reason for rejecting?') ?? '') : undefined;
    if (d === 'REJECT' && !comment?.trim()) return;
    api.decideAdjustment(token, id, d, comment).then(() => { showToast('success', 'Done'); load(); }).catch(fail);
  };

  return (
    <div style={{ overflowX: 'auto' }}>
      <table style={{ width: '100%', borderCollapse: 'collapse' }}>
        <thead><tr>{['Employee', 'OT date', 'Original', 'Updated', 'Reason', 'Requested by', 'When', 'Status', ''].map(h => <th key={h} style={th}>{h}</th>)}</tr></thead>
        <tbody>
          {rows.map(a => (
            <tr key={a.id}>
              <td style={td}>{a.employeeName}</td><td style={td}>{a.workDate}</td>
              <td style={td}>{hrs(a.originalMinutes)} h / {money(a.originalAmount)}</td>
              <td style={td}>{hrs(a.newMinutes)} h / {money(a.newAmount)}</td>
              <td style={{ ...td, whiteSpace: 'normal', maxWidth: 260 }}>{a.reason}</td>
              <td style={td}>{a.requestedByName ?? '—'}</td><td style={td}>{new Date(a.requestedAt).toLocaleString()}</td>
              <td style={td}><Badge text={a.status} /></td>
              <td style={td}>{a.status === 'PENDING' && canDecide && <>
                <button style={{ ...btnGhost, padding: '4px 8px', fontSize: 12 }} onClick={() => decide(a.id, 'APPROVE')}>Approve</button>{' '}
                <button style={{ ...btnGhost, padding: '4px 8px', fontSize: 12 }} onClick={() => decide(a.id, 'REJECT')}>Reject</button></>}</td>
            </tr>
          ))}
          {rows.length === 0 && <tr><td style={{ ...td, color: 'var(--txt-mut)' }} colSpan={9}>No adjustments yet.</td></tr>}
        </tbody>
      </table>
    </div>
  );
}

// ---------------------------------------------------------------- rates & settings
function RatesTab({ token, fail, canEdit }: TabProps & { canEdit: boolean }) {
  const { showToast } = useToast();
  const [rules, setRules] = useState<OtRateRule[]>([]);
  const [settings, setSettings] = useState<OtSettings | null>(null);
  const [form, setForm] = useState({ name: '', dayType: 'WEEKDAY' as OtDayType, multiplier: '1.5', effectiveFrom: iso(new Date()), effectiveTo: '' });

  const load = useCallback(() => {
    api.rules(token).then(setRules).catch(fail);
    api.settings(token).then(setSettings).catch(fail);
  }, [token, fail]);
  useEffect(load, [load]);

  const addRule = () => {
    if (!form.name.trim()) { showToast('error', 'Name is required'); return; }
    api.createRule(token, {
      name: form.name, dayType: form.dayType, multiplier: Number(form.multiplier),
      effectiveFrom: form.effectiveFrom, effectiveTo: form.effectiveTo || null,
    }).then(() => { showToast('success', 'Rate rule added'); setForm({ ...form, name: '' }); load(); }).catch(fail);
  };
  const saveSettings = () => settings && api.updateSettings(token, settings).then(s => { setSettings(s); showToast('success', 'Settings saved'); }).catch(fail);

  const num = (k: keyof OtSettings) => (e: React.ChangeEvent<HTMLInputElement>) => settings && setSettings({ ...settings, [k]: Number(e.target.value) });
  const bool = (k: keyof OtSettings) => (e: React.ChangeEvent<HTMLInputElement>) => settings && setSettings({ ...settings, [k]: e.target.checked });

  return (
    <div style={{ display: 'grid', gap: 28 }}>
      <section>
        <h3 style={{ fontSize: 15, margin: '0 0 10px', color: 'var(--txt)' }}>Rate rules</h3>
        <p style={{ fontSize: 12, color: 'var(--txt-mut)', margin: '0 0 10px' }}>
          The most specific active rule on the OT date applies (department, then shift, then day type). OT amount = OT hours × hourly rate × multiplier.
        </p>
        <div style={{ overflowX: 'auto' }}>
          <table style={{ width: '100%', borderCollapse: 'collapse' }}>
            <thead><tr>{['Name', 'Day type', 'Department', 'Shift', 'Multiplier', 'Effective from', 'Effective to', 'Active', ''].map(h => <th key={h} style={th}>{h}</th>)}</tr></thead>
            <tbody>{rules.map(r => (
              <tr key={r.id}>
                <td style={td}>{r.name}</td><td style={td}>{DAY_LABEL[r.dayType]}</td><td style={td}>{r.departmentName ?? 'All'}</td><td style={td}>{r.shiftName ?? 'All'}</td>
                <td style={td}>{r.multiplier}×</td><td style={td}>{r.effectiveFrom}</td><td style={td}>{r.effectiveTo ?? '—'}</td><td style={td}>{r.active ? 'Yes' : 'No'}</td>
                <td style={td}>{canEdit && r.active && <button style={{ ...btnGhost, padding: '4px 8px', fontSize: 12 }}
                  onClick={() => api.deactivateRule(token, r.id).then(load).catch(fail)}>Deactivate</button>}</td>
              </tr>))}</tbody>
          </table>
        </div>
        {canEdit && (
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 12, alignItems: 'end', marginTop: 14 }}>
            <div><label style={label}>Name</label><input style={input} value={form.name} onChange={e => setForm({ ...form, name: e.target.value })} /></div>
            <div><label style={label}>Day type</label>
              <select style={input} value={form.dayType} onChange={e => setForm({ ...form, dayType: e.target.value as OtDayType })}>
                {(Object.keys(DAY_LABEL) as OtDayType[]).map(k => <option key={k} value={k}>{DAY_LABEL[k]}</option>)}
              </select></div>
            <div><label style={label}>Multiplier</label><input style={{ ...input, width: 90 }} type="number" step="0.05" min="0.05" value={form.multiplier} onChange={e => setForm({ ...form, multiplier: e.target.value })} /></div>
            <div><label style={label}>Effective from</label><input style={input} type="date" value={form.effectiveFrom} onChange={e => setForm({ ...form, effectiveFrom: e.target.value })} /></div>
            <div><label style={label}>Effective to</label><input style={input} type="date" value={form.effectiveTo} onChange={e => setForm({ ...form, effectiveTo: e.target.value })} /></div>
            <button style={btn} onClick={addRule}>Add rule</button>
          </div>
        )}
      </section>

      {settings && (
        <section>
          <h3 style={{ fontSize: 15, margin: '0 0 10px', color: 'var(--txt)' }}>Calculation & approval settings</h3>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 16, alignItems: 'end' }}>
            <div><label style={label}>Minimum OT (minutes)</label><input style={{ ...input, width: 110 }} type="number" min={0} disabled={!canEdit} value={settings.minOtMinutes} onChange={num('minOtMinutes')} /></div>
            <div><label style={label}>Round down to (minutes)</label><input style={{ ...input, width: 110 }} type="number" min={1} disabled={!canEdit} value={settings.roundingMinutes} onChange={num('roundingMinutes')} /></div>
            <div><label style={label}>Working days / month</label><input style={{ ...input, width: 110 }} type="number" min={1} step="0.5" disabled={!canEdit} value={settings.workingDaysPerMonth} onChange={num('workingDaysPerMonth')} /></div>
            <div><label style={label}>Max request (minutes)</label><input style={{ ...input, width: 110 }} type="number" min={1} disabled={!canEdit} value={settings.maxRequestMinutes} onChange={num('maxRequestMinutes')} /></div>
          </div>
          <div style={{ display: 'grid', gap: 8, marginTop: 14, fontSize: 13, color: 'var(--txt)' }}>
            <label><input type="checkbox" disabled={!canEdit} checked={settings.requireApprovedRequest} onChange={bool('requireApprovedRequest')} /> Only calculate OT when the employee has an approved OT request (capped at the requested time)</label>
            <label><input type="checkbox" disabled={!canEdit} checked={settings.allowDuplicateRequests} onChange={bool('allowDuplicateRequests')} /> Allow more than one OT request for the same date</label>
            <label><input type="checkbox" disabled={!canEdit} checked={settings.adjustmentApprovalRequired} onChange={bool('adjustmentApprovalRequired')} /> Require a second approver for OT adjustments</label>
          </div>
          {canEdit && <button style={{ ...btn, marginTop: 14 }} onClick={saveSettings}>Save settings</button>}
          <p style={{ fontSize: 12, color: 'var(--txt-mut)', marginTop: 10 }}>
            Hourly rate = (annual CTC ÷ 12) ÷ (working days per month × scheduled shift hours).
          </p>
        </section>
      )}
    </div>
  );
}

// ---------------------------------------------------------------- payroll
function PayrollTab({ token, fail }: TabProps) {
  const [runId, setRunId] = useState('');
  const [summary, setSummary] = useState<OtPayrollSummary | null>(null);
  const [open, setOpen] = useState<string | null>(null);

  const load = () => {
    if (!runId.trim()) return;
    api.payrollSummary(token, runId.trim()).then(setSummary).catch(fail);
  };

  return (
    <div>
      <div style={{ display: 'flex', gap: 12, alignItems: 'end', marginBottom: 14, flexWrap: 'wrap' }}>
        <div><label style={label}>Payroll run ID</label><input style={{ ...input, width: 320 }} value={runId} onChange={e => setRunId(e.target.value)} placeholder="Paste the run id from the run's URL" /></div>
        <button style={btn} onClick={load}>Load OT summary</button>
      </div>
      <p style={{ fontSize: 12, color: 'var(--txt-mut)' }}>
        Approved OT is added automatically as the "Over Time (OT)" earning when a run's draft is generated. Employees whose approved OT differs from their payslip OT are highlighted — regenerate the draft to pick up newly approved OT.
      </p>
      {summary && (
        <>
          <p style={{ fontSize: 13, color: 'var(--txt)' }}>
            Cycle {summary.cycleStart} → {summary.cycleEnd} · On payslips: <strong>{money(summary.totalTransferred)}</strong> · Approved in cycle: <strong>{money(summary.totalApprovedInCycle)}</strong>
          </p>
          <table style={{ width: '100%', borderCollapse: 'collapse' }}>
            <thead><tr>{['Emp ID', 'Employee', 'Approved OT', 'Payslip OT', ''].map(h => <th key={h} style={th}>{h}</th>)}</tr></thead>
            <tbody>{summary.employees.map(e => (
              <>
                <tr key={e.employeeUserId} style={e.discrepancy ? { background: 'rgba(229,83,75,.08)' } : undefined}>
                  <td style={td}>{e.employeeCode ?? '—'}</td><td style={td}>{e.employeeName}</td>
                  <td style={td}>{money(e.approvedAmount)}</td><td style={td}>{money(e.payslipAmount)}</td>
                  <td style={td}>{e.discrepancy && <span style={{ color: '#E5534B', fontWeight: 600 }}>Mismatch </span>}
                    <button style={{ ...btnGhost, padding: '4px 8px', fontSize: 12 }} onClick={() => setOpen(open === e.employeeUserId ? null : e.employeeUserId)}>Details</button></td>
                </tr>
                {open === e.employeeUserId && e.rows.map((r, i) => (
                  <tr key={`${e.employeeUserId}-${i}`}>
                    <td style={td} /><td style={{ ...td, color: 'var(--txt-mut)' }}>{r.workDate}</td>
                    <td style={td} colSpan={3}>{r.hours} h × {r.hourlyRate} × {r.multiplier} = {money(r.amount)}</td>
                  </tr>))}
              </>))}
            </tbody>
          </table>
        </>
      )}
    </div>
  );
}

function Modal({ title, onClose, children }: { title: string; onClose: () => void; children: React.ReactNode }) {
  return (
    <div style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,.5)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 1000, padding: 16 }} onClick={onClose}>
      <div style={{ background: 'var(--card, var(--shell))', border: '1px solid var(--line2)', borderRadius: 10, padding: 20, width: 'min(760px, 100%)', maxHeight: '85vh', overflow: 'auto' }} onClick={e => e.stopPropagation()}>
        <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 12 }}>
          <h3 style={{ margin: 0, fontSize: 15, color: 'var(--txt)' }}>{title}</h3>
          <button style={{ ...btnGhost, padding: '2px 8px' }} onClick={onClose}>✕</button>
        </div>
        {children}
      </div>
    </div>
  );
}
