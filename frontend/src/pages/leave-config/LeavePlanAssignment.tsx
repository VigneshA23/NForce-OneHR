/**
 * LV-01 Leave plan assignment (Organization Masters > Leave > Assignment). Employees get a plan
 * individually or in bulk (an effective-dated allocation, never overlapping), or by rule (the shared
 * auto-assignment rules, domain LEAVE_PLAN). An allocation outranks a rule, a rule outranks the org
 * default. Changing someone's plan re-credits the months from the change under the new plan.
 */
import { useCallback, useEffect, useMemo, useState } from 'react';
import { TabBar } from '../../components/TabBar';
import { TablePagination } from '../../components/TablePagination';
import { usePaged } from '../../components/usePaged';
import { useToast } from '../../context/ToastContext';
import {
  leaveConfigApi, type EmployeePlanRow, type LeavePlanSummary, type PlanAllocation,
} from '../../api/leave';
import AssignmentRulesSection from '../penalization/AssignmentRulesSection';
import {
  Badge, Dialog, DialogActions, FormError, Table, errMsg, fmtDate, hintS, inputS, labelS, primaryBtn, secondaryBtn, tdS,
} from './leaveConfigUi';
import { isoDate } from './planRules';

const SOURCE_LABEL: Record<string, string> = { ALLOCATION: 'Assigned', RULE: 'Rule', DEFAULT: 'Default' };

function sourceText(r: EmployeePlanRow): string {
  if (!r.source) return '–';
  return r.source === 'RULE' && r.ruleName ? `Rule: ${r.ruleName}` : SOURCE_LABEL[r.source];
}

// ── Assign (one or many) ───────────────────────────────────────────────────────

function AssignDialog({ employees, plans, token, onClose, onDone }: {
  employees: EmployeePlanRow[]; plans: LeavePlanSummary[]; token: string; onClose(): void; onDone(): void;
}) {
  const { showToast } = useToast();
  const active = plans.filter(p => p.status === 'ACTIVE');
  const [planId, setPlanId] = useState(active[0]?.id ?? '');
  const [from, setFrom] = useState(isoDate(new Date()));
  const [to, setTo] = useState('');
  const [conflicts, setConflicts] = useState<Record<string, PlanAllocation>>({});
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const ids = employees.map(e => e.employeeUserId);

  useEffect(() => {
    if (!from) return;
    let live = true;
    leaveConfigApi.checkPlanConflicts({ employeeUserIds: ids, effectiveFrom: from, effectiveTo: to || null }, token)
      .then(c => { if (live) setConflicts(c); }).catch(() => { if (live) setConflicts({}); });
    return () => { live = false; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [from, to, token, ids.join(',')]);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!planId) { setError('Choose a plan'); return; }
    if (to && to < from) { setError('"Until" can\'t be before "From"'); return; }
    setBusy(true);
    setError('');
    try {
      if (employees.length === 1) {
        await leaveConfigApi.allocatePlan({ employeeUserId: ids[0], planId, effectiveFrom: from, effectiveTo: to || null }, token);
        showToast('success', `${employees[0].fullName} assigned`);
      } else {
        const r = await leaveConfigApi.bulkAllocatePlan({ employeeUserIds: ids, planId, effectiveFrom: from, effectiveTo: to || null }, token);
        showToast(r.failed.length ? 'error' : 'success',
          `${r.succeededIds.length} assigned${r.failed.length ? `, ${r.failed.length} skipped (${r.failed[0].reason})` : ''}`);
      }
      onDone();
      onClose();
    } catch (err) {
      setError(errMsg(err));
      setBusy(false);
    }
  }

  const conflicting = employees.filter(e => conflicts[e.employeeUserId]);
  return (
    <Dialog title={employees.length === 1 ? `Assign a plan to ${employees[0].fullName}` : `Assign a plan to ${employees.length} employees`}
      kind="add" width={520} onClose={onClose}>
      <form onSubmit={submit}>
        <p style={hintS}>An assignment overrides rules and the default for its dates. Months already credited from "From" are re-credited under the new plan.</p>
        <div style={{ marginBottom: 14 }}>
          <label style={labelS} htmlFor="pa-plan">Leave plan *</label>
          <select id="pa-plan" style={inputS} value={planId} onChange={e => setPlanId(e.target.value)}>
            {active.map(p => <option key={p.id} value={p.id}>{p.name}{p.orgDefault ? ' (default)' : ''}</option>)}
          </select>
        </div>
        <div style={{ display: 'flex', gap: 12, marginBottom: 14 }}>
          <div style={{ flex: 1 }}>
            <label style={labelS} htmlFor="pa-from">From *</label>
            <input id="pa-from" type="date" style={inputS} value={from} onChange={e => setFrom(e.target.value)} />
          </div>
          <div style={{ flex: 1 }}>
            <label style={labelS} htmlFor="pa-to">Until</label>
            <input id="pa-to" type="date" style={inputS} value={to} min={from} onChange={e => setTo(e.target.value)} />
            <span style={{ fontSize: 11, color: 'var(--txt-dim)' }}>Blank = no end date</span>
          </div>
        </div>
        {conflicting.length > 0 && (
          <div role="alert" style={{ fontSize: 12, color: 'var(--warn, #b7791f)', marginBottom: 12, lineHeight: 1.5 }}>
            {conflicting.length === 1
              ? `${conflicting[0].fullName} already has "${conflicts[conflicting[0].employeeUserId].planName}" from ${fmtDate(conflicts[conflicting[0].employeeUserId].effectiveFrom)} — end or remove it first.`
              : `${conflicting.length} employee(s) already have an overlapping assignment and will be skipped.`}
          </div>
        )}
        <FormError error={error} />
        <DialogActions busy={busy} label="Assign" onCancel={onClose} />
      </form>
    </Dialog>
  );
}

// ── One employee's history ─────────────────────────────────────────────────────

function HistoryDialog({ employee, plans, token, canConfigure, onClose, onChanged }: {
  employee: EmployeePlanRow; plans: LeavePlanSummary[]; token: string; canConfigure: boolean; onClose(): void; onChanged(): void;
}) {
  const { showToast } = useToast();
  const [history, setHistory] = useState<PlanAllocation[] | null>(null);
  const [editing, setEditing] = useState<PlanAllocation | null>(null);
  const [form, setForm] = useState({ planId: '', from: '', to: '' });
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const load = useCallback(() => {
    leaveConfigApi.planEmployee(employee.employeeUserId, token).then(d => setHistory(d.history)).catch(e => setError(errMsg(e)));
  }, [employee.employeeUserId, token]);
  useEffect(load, [load]);

  async function act(f: () => Promise<unknown>, done: string) {
    setBusy(true);
    setError('');
    try { await f(); showToast('success', done); setEditing(null); load(); onChanged(); } catch (e) { setError(errMsg(e)); } finally { setBusy(false); }
  }

  return (
    <Dialog title={`${employee.fullName} — leave plan`} width={620} onClose={onClose}>
      <p style={hintS}>Today: <b>{employee.planName ?? 'none'}</b> ({sourceText(employee)}). Past assignments can't be changed.</p>
      <Table head={['Plan', 'From', 'Until', 'Status', '']} empty={history?.length === 0 && 'Never assigned directly.'}>
        {(history ?? []).map(a => (
          <tr key={a.id}>
            <td style={{ ...tdS, color: 'var(--txt)' }}>{a.planName}</td>
            <td style={tdS}>{fmtDate(a.effectiveFrom)}</td>
            <td style={tdS}>{a.effectiveTo ? fmtDate(a.effectiveTo) : 'No end'}</td>
            <td style={tdS}><Badge tone={a.status === 'CURRENT' ? 'ok' : a.status === 'FUTURE' ? 'info' : 'dim'}>{a.status === 'CURRENT' ? 'Current' : a.status === 'FUTURE' ? 'Upcoming' : 'Past'}</Badge></td>
            <td style={{ ...tdS, textAlign: 'right', whiteSpace: 'nowrap' }}>
              {canConfigure && a.status !== 'HISTORICAL' && <>
                <button style={{ ...secondaryBtn, marginRight: 6 }} disabled={busy}
                  onClick={() => { setEditing(a); setForm({ planId: a.planId, from: a.effectiveFrom, to: a.effectiveTo ?? '' }); }}>Edit</button>
                <button style={secondaryBtn} disabled={busy}
                  onClick={() => void act(() => leaveConfigApi.removePlanAllocation(a.id, token), a.status === 'CURRENT' ? 'Assignment ended yesterday' : 'Assignment removed')}>
                  {a.status === 'CURRENT' ? 'End' : 'Remove'}
                </button>
              </>}
            </td>
          </tr>
        ))}
      </Table>
      {editing && (
        <div style={{ display: 'flex', gap: 10, alignItems: 'flex-end', flexWrap: 'wrap', marginTop: 14 }}>
          <div style={{ flex: '1 1 160px' }}>
            <label style={labelS}>Plan</label>
            <select style={inputS} value={form.planId} onChange={e => setForm({ ...form, planId: e.target.value })}>
              {plans.filter(p => p.status === 'ACTIVE' || p.id === form.planId).map(p => <option key={p.id} value={p.id}>{p.name}</option>)}
            </select>
          </div>
          <div><label style={labelS}>From</label><input type="date" style={inputS} value={form.from} onChange={e => setForm({ ...form, from: e.target.value })} /></div>
          <div><label style={labelS}>Until</label><input type="date" style={inputS} value={form.to} onChange={e => setForm({ ...form, to: e.target.value })} /></div>
          <button style={primaryBtn(busy)} disabled={busy} onClick={() => void act(() => leaveConfigApi.updatePlanAllocation(editing.id,
            { planId: form.planId, effectiveFrom: form.from, effectiveTo: form.to || null }, token), 'Assignment updated')}>Save</button>
        </div>
      )}
      <FormError error={error} />
      <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: 14 }}>
        <button type="button" style={secondaryBtn} onClick={onClose}>Close</button>
      </div>
    </Dialog>
  );
}

// ── Employees ──────────────────────────────────────────────────────────────────

function EmployeesView({ token, canConfigure }: { token: string; canConfigure: boolean }) {
  const { showToast } = useToast();
  const [rows, setRows] = useState<EmployeePlanRow[] | null>(null);
  const [plans, setPlans] = useState<LeavePlanSummary[]>([]);
  const [error, setError] = useState('');
  const [query, setQuery] = useState('');
  const [planFilter, setPlanFilter] = useState('');
  const [sourceFilter, setSourceFilter] = useState('');
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [assigning, setAssigning] = useState<EmployeePlanRow[] | null>(null);
  const [history, setHistory] = useState<EmployeePlanRow | null>(null);

  const load = useCallback(async () => {
    try {
      const [r, p] = await Promise.all([leaveConfigApi.planEmployees(token), leaveConfigApi.plans(token)]);
      setRows(r); setPlans(p); setError('');
    } catch (e) { setError(errMsg(e)); }
  }, [token]);
  useEffect(() => { void load(); }, [load]);

  const filtered = useMemo(() => (rows ?? []).filter(r => {
    const q = query.trim().toLowerCase();
    if (q && !`${r.fullName} ${r.employeeCode ?? ''} ${r.departmentName ?? ''} ${r.locationName ?? ''}`.toLowerCase().includes(q)) return false;
    if (planFilter && r.planId !== planFilter) return false;
    if (sourceFilter && r.source !== sourceFilter) return false;
    return true;
  }), [rows, query, planFilter, sourceFilter]);
  const paged = usePaged(filtered, [query, planFilter, sourceFilter], 25);
  const chosen = (rows ?? []).filter(r => selected.has(r.employeeUserId));

  function toggle(id: string) {
    const next = new Set(selected);
    if (next.has(id)) next.delete(id); else next.add(id);
    setSelected(next);
  }

  async function endSelected() {
    try {
      const r = await leaveConfigApi.bulkRemovePlan([...selected], token);
      showToast(r.failed.length ? 'error' : 'success',
        `${r.succeededIds.length} assignment(s) ended${r.failed.length ? `, ${r.failed.length} had no current assignment` : ''}`);
      setSelected(new Set());
      await load();
    } catch (e) { showToast('error', errMsg(e)); }
  }

  const allOnPage = paged.pageRows.length > 0 && paged.pageRows.every(r => selected.has(r.employeeUserId));
  return (
    <div>
      <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap', alignItems: 'flex-end', marginBottom: 12 }}>
        <div style={{ flex: '1 1 220px', maxWidth: 320 }}>
          <label style={labelS} htmlFor="pa-q">Search</label>
          <input id="pa-q" style={inputS} value={query} placeholder="Name, code, department, location" onChange={e => setQuery(e.target.value)} />
        </div>
        <div style={{ width: 200 }}>
          <label style={labelS} htmlFor="pa-pf">Plan</label>
          <select id="pa-pf" style={inputS} value={planFilter} onChange={e => setPlanFilter(e.target.value)}>
            <option value="">All plans</option>{plans.map(p => <option key={p.id} value={p.id}>{p.name}</option>)}
          </select>
        </div>
        <div style={{ width: 160 }}>
          <label style={labelS} htmlFor="pa-sf">How</label>
          <select id="pa-sf" style={inputS} value={sourceFilter} onChange={e => setSourceFilter(e.target.value)}>
            <option value="">Any</option><option value="ALLOCATION">Assigned</option><option value="RULE">By rule</option><option value="DEFAULT">Default</option>
          </select>
        </div>
        {canConfigure && selected.size > 0 && (
          <div style={{ display: 'flex', gap: 8, marginLeft: 'auto' }}>
            <span style={{ fontSize: 12.5, color: 'var(--txt-mut)', alignSelf: 'center' }}>{selected.size} selected</span>
            <button style={primaryBtn()} onClick={() => setAssigning(chosen)}>Assign plan</button>
            <button style={secondaryBtn} onClick={() => void endSelected()}>End current assignment</button>
          </div>
        )}
      </div>
      <FormError error={error} />
      {rows === null ? <p style={hintS}>Loading…</p> : (
        <>
          <Table head={[canConfigure ? '' : ' ', 'Employee', 'Department', 'Location', 'Leave plan today', 'How', 'Upcoming', '']}
            empty={filtered.length === 0 && 'No employees match.'}>
            {paged.pageRows.map(r => (
              <tr key={r.employeeUserId}>
                <td style={{ ...tdS, width: 32 }}>
                  {canConfigure && <input type="checkbox" checked={selected.has(r.employeeUserId)} onChange={() => toggle(r.employeeUserId)}
                    aria-label={`Select ${r.fullName}`} />}
                </td>
                <td style={{ ...tdS, color: 'var(--txt)' }}>
                  {r.fullName}{r.employeeCode && <span style={{ color: 'var(--txt-dim)' }}> · {r.employeeCode}</span>}
                  {!r.active && <span style={{ marginLeft: 6 }}><Badge tone="dim">Inactive</Badge></span>}
                </td>
                <td style={tdS}>{r.departmentName ?? '–'}</td>
                <td style={tdS}>{r.locationName ?? '–'}</td>
                <td style={{ ...tdS, color: 'var(--txt)', fontWeight: 500 }}>{r.planName ?? '–'}</td>
                <td style={tdS}><Badge tone={r.source === 'ALLOCATION' ? 'info' : r.source === 'RULE' ? 'ok' : 'dim'}>{sourceText(r)}</Badge></td>
                <td style={tdS}>{r.upcomingAllocation ? `${r.upcomingAllocation.planName} from ${fmtDate(r.upcomingAllocation.effectiveFrom)}` : '–'}</td>
                <td style={{ ...tdS, textAlign: 'right', whiteSpace: 'nowrap' }}>
                  {canConfigure && <button style={{ ...secondaryBtn, marginRight: 6 }} onClick={() => setAssigning([r])}>Assign</button>}
                  <button style={secondaryBtn} onClick={() => setHistory(r)}>History</button>
                </td>
              </tr>
            ))}
          </Table>
          {canConfigure && paged.pageRows.length > 0 && (
            <button type="button" style={{ ...secondaryBtn, marginTop: 8 }} onClick={() => {
              const next = new Set(selected);
              paged.pageRows.forEach(r => (allOnPage ? next.delete(r.employeeUserId) : next.add(r.employeeUserId)));
              setSelected(next);
            }}>{allOnPage ? 'Clear this page' : 'Select this page'}</button>
          )}
          {filtered.length > 0 && <TablePagination {...paged.pagerProps} noun="employees" />}
        </>
      )}
      {assigning && (
        <AssignDialog employees={assigning} plans={plans} token={token} onClose={() => setAssigning(null)}
          onDone={() => { setSelected(new Set()); void load(); }} />
      )}
      {history && (
        <HistoryDialog employee={history} plans={plans} token={token} canConfigure={canConfigure}
          onClose={() => setHistory(null)} onChanged={() => void load()} />
      )}
    </div>
  );
}

// ── Tab ────────────────────────────────────────────────────────────────────────

export default function LeavePlanAssignment({ token, canConfigure }: { token: string; canConfigure: boolean }) {
  const [view, setView] = useState<'employees' | 'rules'>('employees');
  const loadTargets = useCallback(async () =>
    (await leaveConfigApi.plans(token)).filter(p => p.status === 'ACTIVE').map(p => ({ id: p.id, name: p.name })), [token]);
  return (
    <div>
      <p style={hintS}>
        Every employee is on exactly one leave plan: one assigned to them for a date range, else the first matching rule, else
        the organization default. New joiners with no matching rule get the default automatically.
      </p>
      <TabBar size="sm" ariaLabel="Leave plan assignment" style={{ marginBottom: 14 }} active={view} onChange={setView}
        tabs={[{ key: 'employees', label: 'Employees' }, { key: 'rules', label: 'Rules' }]} />
      {view === 'employees'
        ? <EmployeesView token={token} canConfigure={canConfigure} />
        : <AssignmentRulesSection token={token} domain="LEAVE_PLAN" loadTargets={loadTargets} canEdit={canConfigure} noun="leave plan" />}
    </div>
  );
}
