import { useEffect, useState } from 'react';
import { History, Upload, Wallet, TrendingUp, TrendingDown } from 'lucide-react';
import { PersonAvatar, PersonCellById } from '../../components/PersonCell';
import { TabBar, type TabDef } from '../../components/TabBar';
import { useAuthStore } from '../../store/authStore';
import { useToast } from '../../context/ToastContext';
import { payrollApi, type SalaryStructureResponse, type EmployeeCompensationResponse, type PayGroupResponse } from '../../api/payroll';
import { auditApi, type AuditLogEntry } from '../../api/audit';
import type { EmployeeRecord } from '../../api/employees';
import {
  panelStyle, thStyle, tdStyle, fmtCurrency, fmtDate, fmtDateTime, EmptyRow, FormRow, labelStyle,
  inputStyle, BtnPrimary, BtnGhost, SectionHead, EmployeePicker, FullScreenTask, FilterBar, useEmployeeDirectory,
  WithFaqPanel, type FaqItem,
} from './payrollShared';
import { SalaryImportWizard } from './SalaryImportWizard';
import { FinancialDetailsImportWizard } from './FinancialDetailsImportWizard';
import { BonusImportWizard } from './BonusImportWizard';

/** Keka's "Revise Salary" full-screen task (§3.11), reused here for first-time assignment too —
 * same payrollApi.assignCompensation call either way, just framed as "Assign" when there's no
 * current compensation yet vs. "Revise" when there is. The "or Percentage Change" input is pure
 * client-side arithmetic over the same single `ctc` field the request actually sends - not a new
 * business concept, just a second way to arrive at the same number. */
export function ReviseCompensationScreen({ employee, current, structures, payGroups, onClose, onSaved }: {
  employee: EmployeeRecord; current: EmployeeCompensationResponse | null; structures: SalaryStructureResponse[];
  payGroups: PayGroupResponse[]; onClose: () => void; onSaved: (c: EmployeeCompensationResponse) => void;
}) {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const [payGroupId, setPayGroupId] = useState(current?.payGroupId ?? payGroups[0]?.id ?? '');
  const [structureId, setStructureId] = useState(current?.structureId ?? structures[0]?.id ?? '');
  const [mode, setMode] = useState<'amount' | 'percent'>('amount');
  const [amount, setAmount] = useState(current ? String(current.ctc) : '');
  const [percent, setPercent] = useState('');
  const [effectiveFrom, setEffectiveFrom] = useState(() => new Date().toISOString().slice(0, 10));
  const [submitting, setSubmitting] = useState(false);

  const newCtc = mode === 'amount'
    ? Number(amount) || 0
    : current ? Math.round(current.ctc * (1 + (Number(percent) || 0) / 100)) : 0;

  const valid = !!payGroupId && !!structureId && newCtc > 0 && !!effectiveFrom;

  async function submit() {
    if (!valid) return;
    setSubmitting(true);
    try {
      const comp = await payrollApi.assignCompensation({ employeeId: employee.userId, payGroupId, structureId, ctc: newCtc, effectiveFrom }, token);
      onSaved(comp);
      showToast('success', current ? 'Compensation revised' : 'Compensation assigned');
      onClose();
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Failed to save compensation');
    } finally {
      setSubmitting(false);
    }
  }

  const legalEntity = payGroups.find(pg => pg.id === payGroupId)?.legalEntity;
  const change = current ? newCtc - current.ctc : null;
  const changePct = current && current.ctc > 0 ? ((newCtc - current.ctc) / current.ctc) * 100 : null;

  return (
    <FullScreenTask
      title={current ? 'Revise Salary' : 'Assign Compensation'}
      subtitle={current ? 'Set a new CTC for this employee from a chosen date.' : 'Assign a salary structure and CTC to this employee.'}
      onClose={onClose}
      actions={(
        <>
          <BtnGhost onClick={onClose}>Cancel</BtnGhost>
          <BtnPrimary onClick={submit} disabled={!valid || submitting}>{submitting ? 'Saving…' : current ? 'Revise' : 'Assign'}</BtnPrimary>
        </>
      )}
    >
      {/* Employee strip */}
      <section className="nf-pr-empstrip">
        <PersonAvatar userId={employee.userId} name={employee.fullName} size={48} />
        <div style={{ minWidth: 0 }}>
          <div style={{ fontSize: 16, fontWeight: 800, color: 'var(--txt)', overflowWrap: 'anywhere' }}>{employee.fullName}</div>
          <div className="nf-pr-empstrip-role">{employee.designationName ?? '-'}</div>
        </div>
        <div className="nf-pr-empstrip-facts">
          <div><div style={labelStyle}>Date of joining</div><div className="nf-pr-empstrip-v">{fmtDate(employee.joiningDate)}</div></div>
          <div><div style={labelStyle}>Legal entity</div><div className="nf-pr-empstrip-v">{legalEntity ?? '-'}</div></div>
          <div><div style={labelStyle}>Department</div><div className="nf-pr-empstrip-v">{employee.departmentName ?? '-'}</div></div>
          <div><div style={labelStyle}>Current CTC</div><div className="nf-pr-empstrip-v" style={{ fontWeight: 800 }}>{current ? fmtCurrency(current.ctc) : 'Not set'}</div></div>
        </div>
      </section>

      <div className="nf-pr-edwrap">
        <div className="nf-pr-ed nf-pr-ed--comp">
          {/* New compensation details */}
          <section className="nf-pr-edcard">
            <header className="nf-pr-edcard-head">
              <span className="nf-pr-edcard-ico"><Wallet size={16} /></span>
              <div>
                <h3 className="nf-pr-edcard-title">New compensation details</h3>
                <p className="nf-pr-edcard-sub">Choose the pay group and structure, then set the new CTC</p>
              </div>
            </header>
            <div className="nf-pr-edcard-body">
              <div className="nf-pr-2col">
                <FormRow>
                  <label style={labelStyle}>Pay group</label>
                  <select value={payGroupId} onChange={e => setPayGroupId(e.target.value)} style={inputStyle}>
                    {payGroups.map(pg => <option key={pg.id} value={pg.id}>{pg.name}</option>)}
                  </select>
                </FormRow>
                <FormRow>
                  <label style={labelStyle}>Salary structure</label>
                  <select value={structureId} onChange={e => setStructureId(e.target.value)} style={inputStyle}>
                    {structures.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}
                  </select>
                </FormRow>
              </div>

              <FormRow>
                <label style={labelStyle}>How do you want to set the new CTC?</label>
                <div className="nf-pr-chips" role="radiogroup" aria-label="CTC entry mode">
                  <button type="button" role="radio" aria-checked={mode === 'amount'} className={`nf-pr-chip${mode === 'amount' ? ' is-on' : ''}`} onClick={() => setMode('amount')}>Enter an amount</button>
                  <button type="button" role="radio" aria-checked={mode === 'percent'} disabled={!current} className={`nf-pr-chip${mode === 'percent' ? ' is-on' : ''}`} onClick={() => setMode('percent')} title={!current ? 'Not available without a current compensation to change from' : undefined}>Change by a percentage</button>
                </div>
              </FormRow>

              {mode === 'amount' ? (
                <FormRow>
                  <label style={labelStyle}>New annual CTC</label>
                  <input type="number" min={0} value={amount} onChange={e => setAmount(e.target.value)} style={inputStyle} placeholder="e.g. 1200000" />
                </FormRow>
              ) : (
                <FormRow>
                  <label style={labelStyle}>Percentage change</label>
                  <input type="number" step="0.1" value={percent} disabled={!current} onChange={e => setPercent(e.target.value)} style={inputStyle} placeholder="e.g. 10" />
                </FormRow>
              )}

              <FormRow>
                <label style={labelStyle}>Effective from</label>
                <input type="date" value={effectiveFrom} onChange={e => setEffectiveFrom(e.target.value)} style={inputStyle} />
              </FormRow>
            </div>
          </section>

          {/* Compensation summary */}
          <section className="nf-pr-edcard">
            <header className="nf-pr-edcard-head">
              <span className="nf-pr-edcard-ico">{change != null && change < 0 ? <TrendingDown size={16} /> : <TrendingUp size={16} />}</span>
              <div>
                <h3 className="nf-pr-edcard-title">Compensation summary</h3>
                <p className="nf-pr-edcard-sub">Effective from {effectiveFrom ? fmtDate(effectiveFrom) : '-'}</p>
              </div>
            </header>
            <div className="nf-pr-edcard-body">
              <div className="nf-pr-sumrow">
                <span style={labelStyle}>Current CTC</span>
                <span className="nf-pr-sumrow-v">{current ? fmtCurrency(current.ctc) : '-'}</span>
              </div>
              <div className="nf-pr-sumrow is-new">
                <span style={labelStyle}>New CTC</span>
                <span className="nf-pr-sumrow-v is-big">{newCtc > 0 ? fmtCurrency(newCtc) : '-'}</span>
              </div>
              {change != null && changePct != null && newCtc > 0 && (
                <div className={`nf-pr-delta ${change >= 0 ? 'is-up' : 'is-down'}`}>
                  {change >= 0 ? 'Increase of ' : 'Decrease of '}{fmtCurrency(Math.abs(change))} ({changePct >= 0 ? '+' : ''}{changePct.toFixed(1)}%)
                </div>
              )}
            </div>
          </section>
        </div>
      </div>
    </FullScreenTask>
  );
}

/** The "All Changes" view - every compensation row ever created, across every employee, newest
 * first. "When" always comes straight from the row's own createdAt (reliable). "Changed By" is a
 * best-effort client-side join against GET /api/audit-logs?action=EMPLOYEE_COMPENSATION_ASSIGNED
 * by targetId - that's already the one place actor+timestamp is tracked for this action (see
 * EmployeeCompensationService#listAllHistory's own comment), rather than duplicating it onto this
 * response. It can come back blank for a row a non-Super-Admin viewer didn't personally create —
 * the audit module scopes non-Super-Admins to their own actions only, same as everywhere else audit
 * data is shown in this app - or for a row predating this action's audit coverage. */
export function AllChangesHistory() {
  const token = useAuthStore(s => s.token)!;
  const { nameFor } = useEmployeeDirectory();
  const [rows, setRows] = useState<EmployeeCompensationResponse[]>([]);
  const [auditByTargetId, setAuditByTargetId] = useState<Map<string, AuditLogEntry>>(new Map());
  const [search, setSearch] = useState('');

  useEffect(() => {
    payrollApi.listAllCompensationHistory(token).then(setRows).catch(() => {});
    auditApi.exportAll({ action: 'EMPLOYEE_COMPENSATION_ASSIGNED' }, token)
      .then(entries => setAuditByTargetId(new Map(entries.filter(e => e.targetId).map(e => [e.targetId as string, e]))))
      .catch(() => {});
  }, [token]);

  const filtered = rows.filter(r => nameFor(r.employeeId).toLowerCase().includes(search.trim().toLowerCase()));

  return (
    <div>
      <div style={{ fontSize: 12, color: 'var(--txt-dim)', marginBottom: 10 }}>
        Every compensation assignment or revision, across every employee. "Changed by" is only as
        complete as this account's own audit-history visibility - it may be blank for changes
        another admin made.
      </div>
      <FilterBar search={search} onSearchChange={setSearch} />
      <div style={panelStyle}>
        <table className="nf-pr-center nf-pr-table" style={{ width: '100%', borderCollapse: 'collapse' }}>
          <thead><tr>{['Employee', 'Structure', 'CTC', 'Effective From', 'Effective To', 'Changed By', 'When'].map(h => <th key={h} style={thStyle}>{h}</th>)}</tr></thead>
          <tbody>
            {filtered.map(r => {
              const audit = auditByTargetId.get(r.id);
              return (
                <tr key={r.id}>
                  <td style={tdStyle}><PersonCellById userId={r.employeeId} name={nameFor(r.employeeId)} /></td>
                  <td style={tdStyle}>{r.structureName}</td>
                  <td style={tdStyle}>{fmtCurrency(r.ctc)}</td>
                  <td style={tdStyle}>{fmtDate(r.effectiveFrom)}</td>
                  <td style={tdStyle}>{r.effectiveTo ? fmtDate(r.effectiveTo) : 'Current'}</td>
                  <td style={tdStyle}>{audit?.actorName ?? '-'}</td>
                  <td style={tdStyle}>{fmtDateTime(r.createdAt)}</td>
                </tr>
              );
            })}
            {filtered.length === 0 && (
              <EmptyRow cols={7} msg={rows.length === 0 ? 'No compensation changes recorded yet.' : 'No matches for your search.'} />
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}

type ViewMode = 'by-employee' | 'all-changes';
const VIEW_MODES: TabDef<ViewMode>[] = [
  { key: 'by-employee', label: 'By Employee' },
  { key: 'all-changes', label: 'All Changes', icon: <History size={14} /> },
];

const FAQ_ITEMS: FaqItem[] = [
  { q: 'What’s the difference between "Assign" and "Revise" Compensation?', a: 'Same screen, same action underneath - it’s labeled "Assign" when the employee has no compensation yet, and "Revise" once they do. Both create a new compensation row effective from the date you set.' },
  { q: 'Does revising someone’s compensation change past payslips?', a: 'No. A new compensation row only affects payslips generated for cycles on or after its effective date; anything already generated stays exactly as it was.' },
  { q: 'Where do I see every change made across all employees?', a: 'Switch to the "All Changes" view at the top - it lists every compensation revision org-wide with who made it and when, pulled from the audit log.' },
  { q: 'What are the three Import buttons for?', a: 'Bulk-upload an Excel file instead of entering rows one by one: Salaries (new compensation per employee), Financial Info (bank/PAN/UAN), and Bonuses (one-off ad-hoc payments). Each walks through Upload > Match Columns > Preview before writing anything.' },
  { q: 'Why does the employee picker clear itself after I save?', a: 'On purpose - so you don’t accidentally start revising the same person again. Search and pick the next employee from empty each time.' },
];

export function EmployeeCompensationTab({ structures, payGroups }: { structures: SalaryStructureResponse[]; payGroups: PayGroupResponse[] }) {
  const token = useAuthStore(s => s.token)!;
  const { employees } = useEmployeeDirectory();
  const [view, setView] = useState<ViewMode>('by-employee');
  const [employeeId, setEmployeeId] = useState('');
  const [current, setCurrent] = useState<EmployeeCompensationResponse | null>(null);
  const [history, setHistory] = useState<EmployeeCompensationResponse[]>([]);
  const [showRevise, setShowRevise] = useState(false);
  const [showImport, setShowImport] = useState(false);
  const [showFinancialImport, setShowFinancialImport] = useState(false);
  const [showBonusImport, setShowBonusImport] = useState(false);
  const employee: EmployeeRecord | null = employees.find(e => e.userId === employeeId) ?? null;

  useEffect(() => {
    if (!employeeId) { setCurrent(null); setHistory([]); return; }
    payrollApi.getCurrentCompensation(employeeId, token).then(setCurrent).catch(() => setCurrent(null));
    payrollApi.getCompensationHistory(employeeId, token).then(setHistory).catch(() => setHistory([]));
  }, [employeeId, token]);

  // Resets the picker back to empty after a successful save, rather than leaving the
  // just-revised employee selected - explicitly asked for, so picking a different employee next
  // doesn't start from whatever was left over from the last one.
  function onSaved() {
    setEmployeeId('');
  }

  return (
    <WithFaqPanel items={FAQ_ITEMS} title="Employee Compensation Help">
    <div>
      <TabBar
        tabs={VIEW_MODES} active={view} onChange={setView} className="nf-boxedtabs" ariaLabel="Employee compensation view" style={{ marginBottom: 18 }}
        trailing={(
          <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap' }}>
            <BtnGhost onClick={() => setShowFinancialImport(true)}><Upload size={14} style={{ marginRight: 6, verticalAlign: 'text-bottom' }} />Import Financial Info</BtnGhost>
            <BtnGhost onClick={() => setShowBonusImport(true)}><Upload size={14} style={{ marginRight: 6, verticalAlign: 'text-bottom' }} />Import Bonuses</BtnGhost>
            <BtnGhost onClick={() => setShowImport(true)}><Upload size={14} style={{ marginRight: 6, verticalAlign: 'text-bottom' }} />Import Salaries</BtnGhost>
          </div>
        )}
      />

      {view === 'all-changes' && <AllChangesHistory />}

      {view === 'by-employee' && (
        <>
          <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 14, padding: '18px 22px', marginBottom: 20 }}>
            <SectionHead title="Employee Compensation" />
            <div style={{ maxWidth: 360, marginBottom: 14 }}>
              <EmployeePicker value={employeeId} onChange={setEmployeeId} options={employees.map(e => ({ id: e.userId, label: `${e.fullName} (${e.departmentName ?? 'No dept'})` }))} />
            </div>
            {employeeId && (
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 14 }}>
                <div style={{ fontSize: 13, color: 'var(--txt-mut)' }}>
                  {current
                    ? <>Current: <strong style={{ color: 'var(--txt)' }}>{current.structureName}</strong>, {fmtCurrency(current.ctc)} CTC, effective {fmtDate(current.effectiveFrom)}</>
                    : 'No compensation assigned yet.'}
                </div>
                <BtnPrimary onClick={() => setShowRevise(true)} disabled={!employee}>
                  {current ? 'Revise Compensation' : 'Assign Compensation'}
                </BtnPrimary>
              </div>
            )}
          </div>

          {employeeId && (
            <div>
              <SectionHead title="Compensation History" action={<History size={15} style={{ color: 'var(--txt-dim)' }} />} />
              <div style={panelStyle}>
                <table className="nf-pr-center nf-pr-table" style={{ width: '100%', borderCollapse: 'collapse' }}>
                  <thead><tr>{['Structure', 'CTC', 'Effective From', 'Effective To'].map(h => <th key={h} style={thStyle}>{h}</th>)}</tr></thead>
                  <tbody>
                    {history.map(h => (
                      <tr key={h.id}>
                        <td style={tdStyle}>{h.structureName}</td>
                        <td style={tdStyle}>{fmtCurrency(h.ctc)}</td>
                        <td style={tdStyle}>{fmtDate(h.effectiveFrom)}</td>
                        <td style={tdStyle}>{h.effectiveTo ? fmtDate(h.effectiveTo) : 'Current'}</td>
                      </tr>
                    ))}
                    {history.length === 0 && <EmptyRow cols={4} msg="No compensation history for this employee yet." />}
                  </tbody>
                </table>
              </div>
            </div>
          )}

          {showRevise && employee && (
            <ReviseCompensationScreen
              employee={employee} current={current} structures={structures} payGroups={payGroups}
              onClose={() => setShowRevise(false)} onSaved={onSaved}
            />
          )}
        </>
      )}

      {showImport && (
        <SalaryImportWizard
          payGroups={payGroups} structures={structures}
          onClose={() => setShowImport(false)}
          onImported={() => { if (employeeId) { payrollApi.getCurrentCompensation(employeeId, token).then(setCurrent).catch(() => {}); } }}
        />
      )}

      {showFinancialImport && (
        <FinancialDetailsImportWizard onClose={() => setShowFinancialImport(false)} onImported={() => {}} />
      )}

      {showBonusImport && (
        <BonusImportWizard payGroups={payGroups} onClose={() => setShowBonusImport(false)} onImported={() => {}} />
      )}
    </div>
    </WithFaqPanel>
  );
}
