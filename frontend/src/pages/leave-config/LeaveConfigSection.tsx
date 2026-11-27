import { useCallback, useEffect, useMemo, useState } from 'react';
import { TabBar } from '../../components/TabBar';
import { KebabMenu, type KebabItem } from '../../components/KebabMenu';
import { usePaged } from '../../components/usePaged';
import { TablePagination } from '../../components/TablePagination';
import { useCan } from '../../lib/access';
import { useToast } from '../../context/ToastContext';
import {
  leaveConfigApi,
  type AccrualCredit, type AccrualPreview, type CarryForwardTransfer, type DirectoryEmployee,
  type LeaveBalance, type LeaveType, type LeaveTypeClassification,
  type LeaveJobRun, type OpeningBalanceUploadRow, type OpeningBalanceValidation,
} from '../../api/leave';
import { orgApi } from '../../api/org';
import * as XLSX from 'xlsx';
import { autoSizeColumns } from '../../lib/xlsxColumns';
import {
  OPENING_BALANCE_HEADERS, OPENING_BALANCE_INSTRUCTIONS, OPENING_BALANCE_SHEET, errorReportRows, parseUploadRows, templateSheetRows,
} from './openingBalances';
import { type NamedOption } from './AppliesTo';
import {
  Badge, ConfirmDialog, CURRENT_YEAR, Dialog, DialogActions, FormError, MONTHS, Table, days, errMsg, fmtDate, frequencyLabel,
  hintS, inputS, labelS, primaryBtn, secondaryBtn, tdS, type Confirm,
} from './leaveConfigUi';
import LeavePlansArea from './LeavePlansArea';
import LeavePlanAssignment from './LeavePlanAssignment';
import LeaveExitsArea from './LeaveExitsArea';

/**
 * Organization Masters / Organization Structure > Leave (V310, V314, LV-01). Areas: Leave Types (the
 * catalogue), Leave Plans (each plan's types with their quota, crediting, carry-forward and "applies to",
 * versioned by effective date), Assignment (who is on which plan: explicit allocations and rules),
 * Balances (crediting, an employee's balances, adjustments, opening balances) and Year-End. Everything is
 * gated by leave_configuration on the server; the buttons here only follow the same permission: VIEW
 * reads, CONFIGURE changes policy, EDIT adjusts a balance.
 */

type Area = 'types' | 'plans' | 'assignment' | 'balances' | 'yearend' | 'exits';

/** Departments, locations and employees for the "applies to" editor and summaries. */
export interface Lookups { departments: NamedOption[]; locations: NamedOption[]; employees: DirectoryEmployee[]; grades: string[] }

// ── Leave type dialog ──────────────────────────────────────────────────────────

function LeaveTypeDialog({ editRow, token, onClose, onSaved }: {
  editRow?: LeaveType; token: string; onClose(): void; onSaved(saved: LeaveType): void;
}) {
  const isEdit = !!editRow;
  const isSystem = !!editRow?.systemKey;
  const [name, setName] = useState(editRow?.name ?? '');
  const [code, setCode] = useState(editRow?.code ?? '');
  const [classification, setClassification] = useState<LeaveTypeClassification>(editRow?.classification ?? 'PAID');
  const [basis, setBasis] = useState<'ANNUAL' | 'PER_OCCURRENCE'>(editRow?.entitlementBasis === 'PER_OCCURRENCE' ? 'PER_OCCURRENCE' : 'ANNUAL');
  const [windowMonths, setWindowMonths] = useState(String(editRow?.occurrenceWindowMonths ?? 12));
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const referenced = (editRow?.referenceCount ?? 0) > 0;

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!name.trim()) { setError('Name is required'); return; }
    if (!isEdit && !/^[A-Za-z0-9_]{1,20}$/.test(code.trim())) { setError('Code is required: letters, digits or underscores, up to 20'); return; }
    const monthsN = Number(windowMonths);
    if (classification === 'PAID' && basis === 'PER_OCCURRENCE' && (!Number.isInteger(monthsN) || monthsN < 1)) {
      setError('Occurrence window must be a whole number of months'); return;
    }
    setError('');
    setBusy(true);
    try {
      const perOccurrence = classification === 'PAID' && basis === 'PER_OCCURRENCE';
      const saved = isEdit && editRow
        ? await leaveConfigApi.updateType(editRow.id, {
            name: name.trim(),
            classification,
            ...(classification === 'PAID' && !referenced ? { entitlementBasis: basis } : {}),
            ...(perOccurrence ? { occurrenceWindowMonths: monthsN } : {}),
          }, token)
        : await leaveConfigApi.createType({
            code: code.trim(), name: name.trim(), classification,
            ...(classification === 'PAID' ? { entitlementBasis: basis } : {}),
            ...(perOccurrence ? { occurrenceWindowMonths: monthsN } : {}),
          }, token);
      onSaved(saved);
      onClose();
    } catch (err) {
      setError(errMsg(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Dialog title={isEdit ? 'Edit Leave Type' : 'Add Leave Type'} kind={isEdit ? 'edit' : 'add'} width={520} onClose={onClose}>
      <form onSubmit={submit}>
        <div style={{ marginBottom: 14 }}>
          <label style={labelS} htmlFor="lt-name">Leave Type Name *</label>
          <input id="lt-name" style={inputS} value={name} onChange={e => setName(e.target.value)} maxLength={50} autoFocus />
        </div>
        <div style={{ marginBottom: 14 }}>
          <label style={labelS} htmlFor="lt-code">Code {isEdit ? '(cannot change)' : '*'}</label>
          <input id="lt-code" style={{ ...inputS, opacity: isEdit ? 0.6 : 1 }} value={code} onChange={e => setCode(e.target.value.toUpperCase())}
            maxLength={20} disabled={isEdit} placeholder="e.g. STUDY" />
        </div>
        <fieldset style={{ border: 'none', padding: 0, margin: '0 0 14px' }}>
          <legend style={labelS}>Classification *</legend>
          <div style={{ display: 'flex', gap: 20, flexWrap: 'wrap' }}>
            {(['PAID', 'UNPAID'] as const).map(c => (
              <label key={c} style={{ display: 'flex', alignItems: 'center', gap: 7, fontSize: 13, cursor: isSystem ? 'default' : 'pointer' }}>
                <input type="radio" name="lt-classification" checked={classification === c} disabled={isSystem || referenced} onChange={() => setClassification(c)} />
                {c === 'PAID' ? 'Paid' : 'Unpaid (unlimited, never uses paid balances)'}
              </label>
            ))}
          </div>
          {isSystem && <p style={{ ...hintS, margin: '6px 0 0', fontSize: 11.5 }}>Built-in type: it can be renamed, but not reclassified.</p>}
          {!isSystem && referenced && <p style={{ ...hintS, margin: '6px 0 0', fontSize: 11.5 }}>Already in use, so it can no longer switch between paid and unpaid.</p>}
        </fieldset>
        {classification === 'PAID' && (
          <>
            <div style={{ marginBottom: 14 }}>
              <label style={labelS} htmlFor="lt-basis">Entitlement basis</label>
              <select id="lt-basis" style={inputS} value={basis} disabled={referenced || isSystem}
                onChange={e => setBasis(e.target.value as 'ANNUAL' | 'PER_OCCURRENCE')}>
                <option value="ANNUAL">Per leave year (own balance, can carry forward)</option>
                <option value="PER_OCCURRENCE">Per eligible occurrence (e.g. Maternity)</option>
              </select>
              {referenced && <p style={{ ...hintS, margin: '6px 0 0', fontSize: 11.5 }}>Already in use, so the basis can no longer change.</p>}
            </div>
            {basis === 'PER_OCCURRENCE' && (
              <div style={{ marginBottom: 14 }}>
                <label style={labelS} htmlFor="lt-window">Occurrence window (months) *</label>
                <input id="lt-window" type="number" min={1} max={120} style={inputS} value={windowMonths} onChange={e => setWindowMonths(e.target.value)} />
                <p style={{ ...hintS, margin: '6px 0 0', fontSize: 11.5 }}>
                  Every request starting within this many months of the occurrence's first approved day shares the same entitlement.
                </p>
              </div>
            )}
          </>
        )}
        <p style={{ ...hintS, fontSize: 11.5 }}>
          Days, crediting, carry-forward and who it applies to are set per leave plan (Leave Plans tab). A new type is not
          offered to anyone until a plan includes it.
        </p>
        <FormError error={error} />
        <DialogActions busy={busy} label={isEdit ? 'Save Changes' : 'Add'} onCancel={onClose} />
      </form>
    </Dialog>
  );
}

// ── Area 1: Leave Types ────────────────────────────────────────────────────────

function LeaveTypesArea({ types, token, canConfigure, reload }: {
  types: LeaveType[]; token: string; canConfigure: boolean; reload(): Promise<void>;
}) {
  const { showToast } = useToast();
  const [dialog, setDialog] = useState<{ row?: LeaveType; key: number } | null>(null);
  const [confirm, setConfirm] = useState<Confirm | null>(null);

  function toggle(t: LeaveType) {
    setConfirm({
      title: t.active ? `Deactivate "${t.name}"` : `Reactivate "${t.name}"`,
      body: t.active
        ? `"${t.name}" will no longer be offered for new leave requests. Existing requests, balances and history keep showing it unchanged.`
        : `"${t.name}" will be offered for new leave requests again.`,
      confirmLabel: t.active ? 'Deactivate' : 'Reactivate',
      danger: t.active,
      onConfirm: async () => {
        await leaveConfigApi.setActive(t.id, !t.active, token);
        showToast('success', `"${t.name}" ${t.active ? 'deactivated' : 'reactivated'}`);
        await reload();
      },
    });
  }

  function remove(t: LeaveType) {
    const refs = t.referenceCount ?? 0;
    if (refs > 0) {
      setConfirm(t.active ? {
        title: `Cannot Delete "${t.name}"`,
        body: `"${t.name}" is used by ${refs} leave record(s) (requests, balances or history), so it can't be deleted. Deactivate it instead to stop new requests while keeping that history.`,
        confirmLabel: 'Deactivate instead',
        danger: true,
        onConfirm: async () => {
          await leaveConfigApi.setActive(t.id, false, token);
          showToast('success', `"${t.name}" deactivated`);
          await reload();
        },
      } : {
        title: `Cannot Delete "${t.name}"`,
        body: `"${t.name}" is used by ${refs} leave record(s), so it can't be deleted. It is already inactive and can't be chosen for new requests.`,
      });
      return;
    }
    setConfirm({
      title: `Delete "${t.name}"`,
      body: `"${t.name}" will be permanently deleted and removed from every leave plan. Nothing references it yet.`,
      confirmLabel: 'Delete',
      danger: true,
      onConfirm: async () => {
        await leaveConfigApi.deleteType(t.id, token);
        showToast('success', `"${t.name}" deleted`);
        await reload();
      },
    });
  }

  function items(t: LeaveType): KebabItem[] {
    if (!canConfigure) return [];
    if (t.systemKey) return [{ label: 'Edit', onClick: () => setDialog({ row: t, key: Date.now() }) }];
    return [
      { label: 'Edit', onClick: () => setDialog({ row: t, key: Date.now() }) },
      { label: t.active ? 'Deactivate' : 'Reactivate', onClick: () => toggle(t), dividerBefore: true },
      { label: 'Delete', danger: true, onClick: () => remove(t) },
    ];
  }

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 12, flexWrap: 'wrap', marginBottom: 12 }}>
        <p style={{ ...hintS, margin: 0, maxWidth: 640 }}>
          The catalogue of leave types. Paid Leave and Unpaid Leave are built in: they can be renamed but not removed. Every
          paid type keeps its own balance; unpaid types are unlimited. How many days, how they're credited, carry-forward and
          who a type applies to are set in each leave plan. Types already used can be deactivated but not deleted.
        </p>
        {canConfigure && <button style={primaryBtn()} onClick={() => setDialog({ key: Date.now() })}>Add Leave Type</button>}
      </div>
      <Table head={['Leave Type', 'Code', 'Classification', 'Basis', 'In plans', 'Usage', 'Status', '']}
        empty={types.length === 0 && 'No leave types configured yet. Add one to start collecting leave requests.'}>
        {types.map(t => (
          <tr key={t.id}>
            <td style={{ ...tdS, color: 'var(--txt)', fontWeight: 500 }}>
              {t.name}{t.systemKey && <span style={{ marginLeft: 8 }}><Badge tone="info">Built-in</Badge></span>}
            </td>
            <td style={{ ...tdS, fontFamily: 'var(--font-mono, monospace)', fontSize: 12 }}>{t.code}</td>
            <td style={tdS}><Badge tone={t.classification === 'PAID' ? 'ok' : 'warn'}>{t.classification === 'PAID' ? 'Paid' : 'Unpaid'}</Badge></td>
            <td style={tdS}>{t.classification === 'UNPAID' ? 'Unlimited' : t.entitlementBasis === 'PER_OCCURRENCE' ? 'Per occurrence' : 'Per leave year'}</td>
            <td style={{ ...tdS, maxWidth: 260 }}>
              {(t.planNames ?? []).length > 0 ? t.planNames!.join(', ')
                : <span title="Nobody can apply for it until a leave plan includes it"><Badge tone="warn">Not in any plan</Badge></span>}
            </td>
            <td style={tdS}>{t.referenceCount ?? 0}</td>
            <td style={tdS}><Badge tone={t.active ? 'ok' : 'dim'}>{t.active ? 'Active' : 'Inactive'}</Badge></td>
            <td style={{ ...tdS, textAlign: 'right', width: 52 }}><KebabMenu items={items(t)} /></td>
          </tr>
        ))}
      </Table>
      {dialog && (
        <LeaveTypeDialog key={dialog.key} editRow={dialog.row} token={token} onClose={() => setDialog(null)}
          onSaved={saved => { showToast('success', dialog.row ? 'Leave type updated' : `"${saved.name}" created`); void reload(); }} />
      )}
      {confirm && <ConfirmDialog {...confirm} onClose={() => setConfirm(null)} />}
    </div>
  );
}

function AdjustDialog({ balance, employeeName, token, onClose, onSaved }: {
  balance: LeaveBalance; employeeName: string; token: string; onClose(): void; onSaved(): void;
}) {
  const [entitlement, setEntitlement] = useState(String(balance.totalDays ?? 0));
  const [carried, setCarried] = useState(String(balance.carriedForwardDays ?? 0));
  const [reason, setReason] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    const ent = Number(entitlement), cf = Number(carried);
    if (entitlement.trim() === '' || isNaN(ent) || ent < 0) { setError('Credited days must be zero or more'); return; }
    if (carried.trim() === '' || isNaN(cf) || cf < 0) { setError('Carried-forward days must be zero or more'); return; }
    if (!reason.trim()) { setError('A reason is required — it is recorded in the audit log'); return; }
    setBusy(true);
    setError('');
    try {
      await leaveConfigApi.adjustBalance(balance.balanceId!, { entitlementDays: ent, carriedForwardDays: cf, reason: reason.trim() }, token);
      onSaved();
      onClose();
    } catch (err) {
      setError(errMsg(err));
      setBusy(false);
    }
  }

  return (
    <Dialog title={`Adjust ${balance.leaveTypeName} — ${balance.year}`} width={460} onClose={onClose}>
      <form onSubmit={submit}>
        <p style={hintS}>
          {employeeName}. Used so far: {days(balance.usedDays)} day(s). Credited days can be set below what is used (the employee then owes
          days). Monthly credits still to come are added on top. The previous and new values, your reason and your name are recorded in the audit log.
        </p>
        <div style={{ display: 'flex', gap: 12, marginBottom: 14, flexWrap: 'wrap' }}>
          <div style={{ flex: '1 1 140px' }}>
            <label style={labelS} htmlFor="adj-ent">Credited this year (days)</label>
            <input id="adj-ent" type="number" min={0} max={999} step={0.25} style={inputS} value={entitlement} onChange={e => setEntitlement(e.target.value)} />
          </div>
          <div style={{ flex: '1 1 140px' }}>
            <label style={labelS} htmlFor="adj-cf">Carried forward (days)</label>
            <input id="adj-cf" type="number" min={0} max={999} step={0.25} style={inputS} value={carried} onChange={e => setCarried(e.target.value)} />
          </div>
        </div>
        <div style={{ marginBottom: 16 }}>
          <label style={labelS} htmlFor="adj-reason">Reason *</label>
          <textarea id="adj-reason" style={{ ...inputS, minHeight: 70, resize: 'vertical' }} maxLength={500} value={reason} onChange={e => setReason(e.target.value)} />
        </div>
        <FormError error={error} />
        <DialogActions busy={busy} label="Save Adjustment" onCancel={onClose} />
      </form>
    </Dialog>
  );
}

function CreditHistoryDialog({ balance, employeeName, token, onClose }: {
  balance: LeaveBalance; employeeName: string; token: string; onClose(): void;
}) {
  const [rows, setRows] = useState<AccrualCredit[] | null>(null);
  const [error, setError] = useState('');
  useEffect(() => {
    leaveConfigApi.creditHistory(balance.balanceId!, token).then(setRows).catch(e => setError(errMsg(e)));
  }, [balance.balanceId, token]);
  const migrated = rows?.some(r => r.source === 'MIGRATION');
  return (
    <Dialog title={`${balance.leaveTypeName} credits — ${balance.year}`} width={460} onClose={onClose}>
      <p style={hintS}>{employeeName}. {frequencyLabel(balance.creditFrequency)} crediting.</p>
      <FormError error={error} />
      {rows === null ? <p style={hintS}>Loading…</p> : migrated ? (
        <p style={hintS}>
          This balance existed before monthly crediting was introduced, so the year counts as fully credited:
          {' '}{days(balance.totalDays)} day(s), the old leave types added together.
        </p>
      ) : (
        <Table head={['Month', 'Days', 'Credited on']} empty={rows.length === 0 && 'Nothing credited yet.'}>
          {rows.map(r => (
            <tr key={r.month}>
              <td style={tdS}>{MONTHS[r.month - 1]} {r.year}</td>
              <td style={{ ...tdS, color: 'var(--txt)' }}>
                {r.source === 'PAUSED' ? <Badge tone="warn">Paused</Badge> : days(r.days)}
              </td>
              <td style={tdS}>{fmtDate(r.creditedAt)}</td>
            </tr>
          ))}
        </Table>
      )}
      {balance.nextCreditDate && (
        <p style={{ ...hintS, marginTop: 12 }}>
          Next: {days(balance.nextCreditDays)} day(s) on {fmtDate(balance.nextCreditDate)}; {days(balance.upcomingCreditDays)} still to come this year.
        </p>
      )}
      <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: 8 }}>
        <button type="button" style={secondaryBtn} onClick={onClose}>Close</button>
      </div>
    </Dialog>
  );
}

function EmployeeBalances({ token, canEdit }: { token: string; canEdit: boolean }) {
  const { showToast } = useToast();
  const [employees, setEmployees] = useState<DirectoryEmployee[]>([]);
  const [query, setQuery] = useState('');
  const [selected, setSelected] = useState<DirectoryEmployee | null>(null);
  const [year, setYear] = useState(CURRENT_YEAR);
  const [rows, setRows] = useState<LeaveBalance[] | null>(null);
  const [error, setError] = useState('');
  const [adjusting, setAdjusting] = useState<LeaveBalance | null>(null);
  const [credits, setCredits] = useState<LeaveBalance | null>(null);

  useEffect(() => {
    leaveConfigApi.employees(token).then(setEmployees).catch(e => setError(errMsg(e)));
  }, [token]);

  const load = useCallback(async () => {
    if (!selected) return;
    setError('');
    try { setRows(await leaveConfigApi.employeeBalances(selected.userId, year, token)); } catch (e) { setError(errMsg(e)); }
  }, [selected, year, token]);
  useEffect(() => { void load(); }, [load]);

  const label = (e: DirectoryEmployee) => (e.employeeCode ? `${e.name} (${e.employeeCode})` : e.name);

  return (
    <div style={{ marginTop: 28 }}>
      <h2 style={{ margin: '0 0 4px', fontSize: 15, fontWeight: 700 }}>Employee balances</h2>
      <p style={hintS}>Look up one employee's balances for a leave year and correct them if needed.</p>
      <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', marginBottom: 14, alignItems: 'flex-end' }}>
        <div style={{ flex: '1 1 260px', maxWidth: 420 }}>
          <label style={labelS} htmlFor="bal-emp">Employee</label>
          <input id="bal-emp" style={inputS} list="nf-leave-employees" value={query} placeholder="Search by name or code"
            onChange={e => {
              setQuery(e.target.value);
              const match = employees.find(x => label(x) === e.target.value);
              setSelected(match ?? null);
              if (!match) setRows(null);
            }} />
          <datalist id="nf-leave-employees">{employees.map(e => <option key={e.userId} value={label(e)} />)}</datalist>
        </div>
        <div style={{ width: 120 }}>
          <label style={labelS} htmlFor="bal-year">Leave year</label>
          <select id="bal-year" style={inputS} value={year} onChange={e => setYear(Number(e.target.value))}>
            {[CURRENT_YEAR - 2, CURRENT_YEAR - 1, CURRENT_YEAR, CURRENT_YEAR + 1].map(y => <option key={y} value={y}>{y}</option>)}
          </select>
        </div>
      </div>
      <FormError error={error} />
      {selected && rows && (
        <Table head={['Leave Type', 'Credited', 'Carried forward', 'Used', 'Pending', 'Balance', 'Can still apply', '']}
          empty={rows.length === 0 && 'No leave types apply.'}>
          {rows.map(b => (
            <tr key={b.leaveTypeCode}>
              <td style={{ ...tdS, color: 'var(--txt)', fontWeight: 500 }}>
                {b.leaveTypeName}
                {b.leaveTypeActive === false && (
                  <span style={{ marginLeft: 8 }} title="Deactivated: hidden from the employee, who can't apply for it">
                    <Badge tone="dim">Inactive</Badge>
                  </span>
                )}
              </td>
              {b.unlimited ? (
                <td style={tdS} colSpan={6}><Badge tone="info">Unlimited</Badge></td>
              ) : (
                <>
                  <td style={tdS}>
                    {days(b.totalDays)}{b.entitlementBasis === 'PER_OCCURRENCE' ? ' / occurrence' : ''}
                    {b.entitlementBasis === 'ANNUAL' && b.annualQuotaDays != null && (
                      <div style={{ fontSize: 11, color: 'var(--txt-dim)' }}>
                        of {days(b.annualQuotaDays)} · {frequencyLabel(b.creditFrequency)}
                        {b.nextCreditDate ? ` · next ${days(b.nextCreditDays)} on ${fmtDate(b.nextCreditDate)}` : ''}
                      </div>
                    )}
                  </td>
                  <td style={tdS}>
                    {b.entitlementBasis === 'ANNUAL' ? (
                      <>
                        {days(b.carriedForwardDays)}
                        {(b.carriedForwardDays ?? 0) > 0 && (
                          <div style={{ fontSize: 11, color: 'var(--txt-dim)' }}>
                            {days(b.carriedForwardRemainingDays)} left{b.carriedForwardExpiresOn ? ` · expires ${fmtDate(b.carriedForwardExpiresOn)}` : ''}
                            {(b.carriedForwardExpiredDays ?? 0) > 0 ? ` · ${days(b.carriedForwardExpiredDays)} expired` : ''}
                          </div>
                        )}
                      </>
                    ) : '–'}
                  </td>
                  <td style={tdS}>{days(b.usedDays)}</td>
                  <td style={tdS}>{days(b.pendingDays)}</td>
                  <td style={{ ...tdS, fontWeight: 600, color: (b.remainingDays ?? 0) < 0 ? 'var(--warn, #b7791f)' : 'var(--txt)' }}>
                    {days(b.remainingDays)}
                    {(b.remainingDays ?? 0) < 0 && <div style={{ fontSize: 11, fontWeight: 400 }}>taken in advance</div>}
                  </td>
                  <td style={tdS}>{b.entitlementBasis === 'ANNUAL' ? days(b.availableToApplyDays) : days(b.remainingDays)}</td>
                </>
              )}
              <td style={{ ...tdS, textAlign: 'right', whiteSpace: 'nowrap' }}>
                {b.balanceId && <button style={{ ...secondaryBtn, marginRight: canEdit ? 6 : 0 }} onClick={() => setCredits(b)}>Credits</button>}
                {canEdit && b.balanceId && <button style={secondaryBtn} onClick={() => setAdjusting(b)}>Adjust</button>}
              </td>
            </tr>
          ))}
        </Table>
      )}
      {adjusting && selected && (
        <AdjustDialog balance={adjusting} employeeName={label(selected)} token={token} onClose={() => setAdjusting(null)}
          onSaved={() => { showToast('success', 'Balance adjusted'); void load(); }} />
      )}
      {credits && selected && (
        <CreditHistoryDialog balance={credits} employeeName={label(selected)} token={token} onClose={() => setCredits(null)} />
      )}
    </div>
  );
}

// ── Balances ───────────────────────────────────────────────────────────────────

function BalancesArea({ token, canConfigure, canEdit }: { token: string; canConfigure: boolean; canEdit: boolean }) {
  return (
    <div>
      <p style={hintS}>
        Balances are credited from each employee's leave plan: a type's yearly quota all at once on 1 January or a twelfth
        on the 1st of each month. With monthly crediting employees may apply for up to the whole year's quota in advance.
        Per-occurrence leave such as Maternity is granted once per eligible occurrence; unpaid leave is unlimited.
      </p>
      <CreditingRun token={token} canConfigure={canConfigure} />
      <EmployeeBalances token={token} canEdit={canEdit} />
      {canEdit && <OpeningBalanceUpload token={token} />}
    </div>
  );
}

/**
 * YE-07: the last run of a nightly leave job, from its audit entry — so on 1 January an admin can
 * confirm the jobs ran and see which employees (if any) failed.
 */
function useLastRun(token: string, job: 'accrual' | 'yearEnd') {
  const [run, setRun] = useState<LeaveJobRun | null | undefined>(undefined);
  const reload = useCallback(() => {
    leaveConfigApi.jobs(token).then(r => setRun(r[job] ?? null)).catch(() => setRun(null));
  }, [token, job]);
  useEffect(() => { reload(); }, [reload]);
  return { run, reload };
}

function LastRun({ run, summary }: { run: LeaveJobRun | null | undefined; summary: (d: LeaveJobRun['details']) => string }) {
  if (run === undefined) return null;
  if (run === null) return <p style={{ ...hintS, margin: '6px 0 0' }}>Last run: never.</p>;
  const failed = run.details.failedEmployees ?? [];
  return (
    <p style={{ ...hintS, margin: '6px 0 0' }} data-testid="leave-job-last-run">
      Last run: {new Date(run.ranAt).toLocaleString(undefined, { day: 'numeric', month: 'short', year: 'numeric', hour: 'numeric', minute: '2-digit' })}
      {' '}by {run.actorName ?? 'the nightly job'} · {summary(run.details)}
      {failed.length > 0
        ? <span style={{ color: 'var(--risk)', fontWeight: 600 }}> · failed for {failed.join(', ')} (retried on the next run)</span>
        : ' · no failures'}
    </p>
  );
}

function CreditingRun({ token, canConfigure }: { token: string; canConfigure: boolean }) {
  const { showToast } = useToast();
  const [busy, setBusy] = useState(false);
  const lastRun = useLastRun(token, 'accrual');
  const [asOf, setAsOf] = useState(() => new Date().toLocaleDateString('en-CA'));
  const [previewing, setPreviewing] = useState(false);
  const [previewError, setPreviewError] = useState('');
  const [result, setResult] = useState<AccrualPreview | null>(null);
  async function preview() {
    setPreviewing(true); setPreviewError('');
    try {
      setResult(await leaveConfigApi.previewAccrual(token, asOf || undefined));
    } catch (e) {
      setPreviewError(errMsg(e));
    } finally {
      setPreviewing(false);
    }
  }
  async function run() {
    setBusy(true);
    try {
      const r = await leaveConfigApi.runAccrual(token);
      showToast(r.failures > 0 ? 'error' : 'success',
        `${r.creditsAdded} employee balance(s) credited${r.creditsPaused ? `, ${r.creditsPaused} month(s) paused` : ''}${r.failures > 0 ? `, ${r.failures} failed (${(r.failedEmployees ?? []).join(', ')}) — run again to retry` : ''}`);
      lastRun.reload();
    } catch (e) {
      showToast('error', errMsg(e));
    } finally {
      setBusy(false);
    }
  }
  return (
    <div style={{ marginTop: 28 }}>
      <h2 style={{ margin: '0 0 4px', fontSize: 15, fontWeight: 700 }}>Crediting</h2>
      <div style={{ display: 'flex', gap: 12, alignItems: 'flex-start', justifyContent: 'space-between', flexWrap: 'wrap' }}>
        <p style={{ ...hintS, maxWidth: 640 }}>
          Runs automatically every night: whatever is due is credited, once, on the date each plan says (the start or the
          end of its month, quarter, half-year or year). A credit whose pause applies is recorded as paused instead.
          Nothing more is credited after an employee's last working day, or for a type that no longer applies to them.
        </p>
        <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
          <input type="date" style={{ ...inputS, width: 150 }} value={asOf} onChange={e => setAsOf(e.target.value)}
            aria-label="Preview crediting as of" />
          <button style={secondaryBtn} disabled={previewing} onClick={preview}>{previewing ? 'Checking…' : 'Preview'}</button>
          {canConfigure && <button style={primaryBtn(busy)} disabled={busy} onClick={run}>{busy ? 'Crediting…' : 'Run crediting now'}</button>}
        </div>
      </div>
      <LastRun run={lastRun.run} summary={d => `${Number(d.creditsAdded ?? 0)} balance(s) credited for ${d.year ?? ''}, ${Number(d.employeesChecked ?? 0)} checked`
        + (Number(d.creditsPaused ?? 0) > 0 ? `, ${Number(d.creditsPaused)} month(s) paused` : '')} />
      <FormError error={previewError} />
      {result && <CreditingPreview preview={result} onClose={() => setResult(null)} />}
    </div>
  );
}

/** LV-03 dry run: what a crediting run on a date would post, credited or paused. Nothing is written. */
function CreditingPreview({ preview, onClose }: { preview: AccrualPreview; onClose(): void }) {
  const paged = usePaged(preview.lines, [preview]);
  const periodOf = (m: number[]) => m.length === 1 ? MONTHS[m[0] - 1] : `${MONTHS[m[0] - 1]}–${MONTHS[m[m.length - 1] - 1]}`;
  return (
    <div style={{ marginTop: 14 }} data-testid="accrual-preview">
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', gap: 12, flexWrap: 'wrap' }}>
        <p style={{ ...hintS, margin: '0 0 8px' }}>
          Dry run for {fmtDate(preview.asOf)}: {days(preview.creditDays)} day(s) would be credited
          {preview.paused > 0 ? `, ${preview.paused} credit(s) paused` : ''}, for {preview.employees} employee(s). Nothing has been posted.
        </p>
        <button type="button" style={secondaryBtn} onClick={onClose}>Close preview</button>
      </div>
      <Table head={['Employee', 'Leave type', 'Period', 'Days', 'Credit date', 'Result']}
        empty={preview.lines.length === 0 && 'Nothing is due: every credit up to this date has been posted.'}>
        {paged.pageRows.map((l, i) => (
          <tr key={`${l.employeeUserId}-${l.leaveTypeId}-${l.year}-${l.months[0]}-${i}`}>
            <td style={tdS}>
              <span style={{ color: 'var(--txt)' }}>{l.employeeName ?? l.employeeUserId}</span>
              {l.employeeCode && <span style={{ color: 'var(--txt-dim)' }}> · {l.employeeCode}</span>}
            </td>
            <td style={tdS}>{l.leaveTypeName}</td>
            <td style={tdS}>{periodOf(l.months)} {l.year} <span style={{ color: 'var(--txt-dim)' }}>({frequencyLabel(l.frequency).toLowerCase()})</span></td>
            <td style={{ ...tdS, color: 'var(--txt)' }}>{days(l.days)}</td>
            <td style={tdS}>{fmtDate(l.creditDate)}</td>
            <td style={tdS}>
              {l.status === 'PAUSED'
                ? <><Badge tone="warn">Paused</Badge> <span style={{ fontSize: 11.5 }}>{l.reason}</span></>
                : <Badge tone="ok">Credit</Badge>}
            </td>
          </tr>
        ))}
      </Table>
      {preview.lines.length > 0 && <TablePagination {...paged.pagerProps} noun="credits" />}
    </div>
  );
}

// ── YE-02: Opening balance upload ─────────────────────────────────────────────

/** Writes rows to a one-sheet (plus optional instructions) .xlsx and downloads it; columns autosized. */
function downloadSheet(fileName: string, sheetName: string, rows: Record<string, string | number>[], headers: string[],
  instructions?: string[][]) {
  const wb = XLSX.utils.book_new();
  const ws = XLSX.utils.json_to_sheet(rows, { header: headers });
  autoSizeColumns(ws, rows, headers);
  XLSX.utils.book_append_sheet(wb, ws, sheetName);
  if (instructions) {
    const help = XLSX.utils.aoa_to_sheet(instructions);
    help['!cols'] = [{ wch: 120 }];
    XLSX.utils.book_append_sheet(wb, help, 'Instructions');
  }
  XLSX.writeFile(wb, fileName);
}

function OpeningBalanceUpload({ token }: { token: string }) {
  const { showToast } = useToast();
  const years = [CURRENT_YEAR, CURRENT_YEAR + 1];
  const [year, setYear] = useState(CURRENT_YEAR);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [fileName, setFileName] = useState('');
  const [upload, setUpload] = useState<OpeningBalanceUploadRow[] | null>(null);
  const [check, setCheck] = useState<OpeningBalanceValidation | null>(null);
  const [showUnchanged, setShowUnchanged] = useState(false);

  function reset() { setUpload(null); setCheck(null); setFileName(''); setError(''); }

  async function downloadTemplate() {
    setBusy(true); setError('');
    try {
      const rows = await leaveConfigApi.openingBalanceTemplate(year, token);
      downloadSheet(`leave-opening-balances-${year}.xlsx`, OPENING_BALANCE_SHEET, templateSheetRows(rows),
        [...OPENING_BALANCE_HEADERS], OPENING_BALANCE_INSTRUCTIONS);
    } catch (e) {
      setError(errMsg(e));
    } finally {
      setBusy(false);
    }
  }

  async function onFile(file: File | undefined) {
    if (!file) return;
    reset();
    setFileName(file.name);
    if (!file.name.toLowerCase().endsWith('.xlsx')) { setError('Upload the .xlsx template.'); return; }
    setBusy(true);
    try {
      const wb = XLSX.read(await file.arrayBuffer(), { type: 'array' });
      const ws = wb.Sheets[OPENING_BALANCE_SHEET] ?? wb.Sheets[wb.SheetNames[0]];
      const rows = parseUploadRows(XLSX.utils.sheet_to_json<Record<string, unknown>>(ws, { defval: '' }));
      if (rows === null) { setError('The columns don\'t match the template. Download the template again and fill it in.'); return; }
      if (rows.length === 0) { setError('The file has no rows.'); return; }
      setUpload(rows);
      setCheck(await leaveConfigApi.validateOpeningBalances(year, rows, token));
    } catch (e) {
      setError(errMsg(e));
    } finally {
      setBusy(false);
    }
  }

  async function confirm() {
    if (!upload) return;
    setBusy(true); setError('');
    try {
      const r = await leaveConfigApi.applyOpeningBalances(year, upload, token);
      showToast('success', `${r.changes} opening balance(s) updated for ${r.year}`);
      reset();
    } catch (e) {
      setError(errMsg(e));
    } finally {
      setBusy(false);
    }
  }

  const shown = check ? check.results.filter(r => showUnchanged || r.changed || r.errors.length > 0) : [];
  const paged = usePaged(shown, [check, showUnchanged]);
  return (
    <div style={{ marginTop: 28 }}>
      <h2 style={{ margin: '0 0 4px', fontSize: 15, fontWeight: 700 }}>Opening balances</h2>
      <p style={{ ...hintS, maxWidth: 760 }}>
        Set many balances at once — for days brought from another system, a joiner's prorated quota or corrections. Download
        the template (every active employee and paid leave type, with today's figures), fill in the new figures and a reason,
        and upload it. Every row is checked first and nothing is saved until the whole file is clean; you see each change
        before confirming. Each change is recorded in the audit log like a manual adjustment.
      </p>
      <div style={{ display: 'flex', gap: 12, alignItems: 'flex-end', flexWrap: 'wrap', marginBottom: 12 }}>
        <div style={{ width: 140 }}>
          <label style={labelS} htmlFor="ob-year">Leave year</label>
          <select id="ob-year" style={inputS} value={year} disabled={busy} onChange={e => { setYear(Number(e.target.value)); reset(); }}>
            {years.map(y => <option key={y} value={y}>{y}</option>)}
          </select>
        </div>
        <button style={secondaryBtn} disabled={busy} onClick={downloadTemplate}>Download template</button>
        <label style={{ ...primaryBtn(busy), display: 'inline-block' }}>
          Upload filled template
          <input type="file" accept=".xlsx" style={{ display: 'none' }} disabled={busy}
            onChange={e => { void onFile(e.target.files?.[0]); e.target.value = ''; }} />
        </label>
        {fileName && <span style={{ fontSize: 12, color: 'var(--txt-dim)' }}>{fileName}</span>}
      </div>
      <FormError error={error} />
      {check && (
        <>
          <div style={{ display: 'flex', gap: 12, alignItems: 'center', flexWrap: 'wrap', margin: '6px 0 10px', fontSize: 12.5 }}>
            <span>{check.rows} row(s) · <b>{check.changes}</b> change(s) for {check.year}</span>
            {check.errorRows > 0
              ? <span style={{ color: 'var(--risk)', fontWeight: 600 }}>{check.errorRows} row(s) with errors — nothing will be saved</span>
              : <span style={{ color: 'var(--ok)', fontWeight: 600 }}>No errors</span>}
            {check.errorRows > 0 && upload && (
              <button style={secondaryBtn} onClick={() => downloadSheet(`leave-opening-balances-${check.year}-errors.xlsx`, 'Errors',
                errorReportRows(check.results, upload),
                ['Row', 'Employee Code', 'Leave Type', 'New Credited', 'New Carried Forward', 'Reason', 'Errors'])}>
                Download error report
              </button>
            )}
            <label style={{ display: 'flex', alignItems: 'center', gap: 6, color: 'var(--txt-mut)' }}>
              <input type="checkbox" checked={showUnchanged} onChange={e => setShowUnchanged(e.target.checked)} /> Show unchanged rows
            </label>
            <span style={{ marginLeft: 'auto', display: 'flex', gap: 8 }}>
              <button style={secondaryBtn} disabled={busy} onClick={reset}>Discard</button>
              <button style={primaryBtn(busy || check.errorRows > 0 || check.changes === 0)} disabled={busy || check.errorRows > 0 || check.changes === 0}
                onClick={confirm}>{busy ? 'Saving…' : `Confirm ${check.changes} change(s)`}</button>
            </span>
          </div>
          <Table head={['Row', 'Employee', 'Leave Type', 'Credited', 'Carried forward', 'Status']}
            empty={shown.length === 0 && 'Nothing to change in this file.'}>
            {paged.pageRows.map(r => (
              <tr key={r.rowNumber}>
                <td style={tdS}>{r.rowNumber}</td>
                <td style={{ ...tdS, color: 'var(--txt)' }}>{r.employeeName ?? '—'}<span style={{ color: 'var(--txt-dim)' }}> · {r.employeeCode || '—'}</span></td>
                <td style={tdS}>{r.leaveType}</td>
                <td style={tdS}>{days(r.currentCredited)}{Number(r.newCredited) !== Number(r.currentCredited) && <> → <b style={{ color: 'var(--txt)' }}>{days(r.newCredited)}</b></>}</td>
                <td style={tdS}>{days(r.currentCarriedForward)}{Number(r.newCarriedForward) !== Number(r.currentCarriedForward) && <> → <b style={{ color: 'var(--txt)' }}>{days(r.newCarriedForward)}</b></>}</td>
                <td style={{ ...tdS, maxWidth: 360 }}>
                  {r.errors.length > 0
                    ? <span style={{ color: 'var(--risk)' }}>{r.errors.join('; ')}</span>
                    : r.changed ? <Badge tone="ok">Will change</Badge> : <Badge tone="dim">Unchanged</Badge>}
                  {r.warnings.length > 0 && <div style={{ color: 'var(--warn)', fontSize: 11.5, marginTop: 4 }}>{r.warnings.join('; ')}</div>}
                </td>
              </tr>
            ))}
          </Table>
          {shown.length > 0 && <TablePagination {...paged.pagerProps} noun="rows" />}
        </>
      )}
    </div>
  );
}

function YearEndProcessing({ token, canConfigure }: { token: string; canConfigure: boolean }) {
  const lastRun = useLastRun(token, 'yearEnd');
  const { showToast } = useToast();
  const [fromYear, setFromYear] = useState(CURRENT_YEAR - 1);
  const [rows, setRows] = useState<CarryForwardTransfer[] | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const paged = usePaged(rows ?? [], [fromYear]);

  const load = useCallback(async () => {
    setError('');
    try { setRows(await leaveConfigApi.listTransfers(fromYear, token)); } catch (e) { setError(errMsg(e)); }
  }, [fromYear, token]);
  useEffect(() => { void load(); }, [load]);

  async function run() {
    setBusy(true);
    try {
      const r = await leaveConfigApi.runYearEnd(fromYear, token);
      showToast(r.failures > 0 ? 'error' : 'success',
        `${r.transfersCreated} transfer(s) recorded, ${r.expiriesUpdated} expiry update(s)${r.failures > 0 ? `, ${r.failures} failed (${(r.failedEmployees ?? []).join(', ')}) — run again to retry` : ''}`);
      lastRun.reload();
      await load();
    } catch (e) {
      setError(errMsg(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div style={{ marginTop: 28 }}>
      <h2 style={{ margin: '0 0 4px', fontSize: 15, fontWeight: 700 }}>Year-end processing</h2>
      <p style={hintS}>
        Runs automatically every night once a leave year has ended: unused days up to the maximum set in each employee's
        leave plan move into the next year's carried-forward balance, the rest lapse, and carried-forward days expire on their
        expiry date. Running it again never transfers twice. Leave still pending at year-end stays reserved in the old year.
      </p>
      <div style={{ display: 'flex', gap: 12, alignItems: 'flex-end', flexWrap: 'wrap', marginBottom: 14 }}>
        <div style={{ width: 140 }}>
          <label style={labelS} htmlFor="ye-year">Leave year</label>
          <select id="ye-year" style={inputS} value={fromYear} onChange={e => setFromYear(Number(e.target.value))}>
            {[CURRENT_YEAR - 1, CURRENT_YEAR - 2, CURRENT_YEAR - 3].map(y => <option key={y} value={y}>{y} → {y + 1}</option>)}
          </select>
        </div>
        {canConfigure && <button style={primaryBtn(busy)} disabled={busy} onClick={run}>{busy ? 'Processing…' : 'Run now'}</button>}
      </div>
      <LastRun run={lastRun.run} summary={d => `${d.fromYear ?? ''} → ${Number(d.fromYear ?? 0) + 1}: ${Number(d.transfersCreated ?? 0)} transfer(s), ${Number(d.expiriesUpdated ?? 0)} expiry update(s)`} />
      <FormError error={error} />
      {rows && (
        <>
          <Table head={['Employee', 'Leave Type', 'Unused', 'Carried', 'Lapsed', 'Expires', 'Processed']}
            empty={rows.length === 0 && `Leave year ${fromYear} hasn't been processed yet.`}>
            {paged.pageRows.map(t => (
              <tr key={t.id}>
                <td style={{ ...tdS, color: 'var(--txt)' }}>{t.employeeName}{t.employeeCode ? <span style={{ color: 'var(--txt-dim)' }}> · {t.employeeCode}</span> : null}</td>
                <td style={tdS}>{t.leaveTypeName}</td>
                <td style={tdS}>{days(t.unusedDays)}</td>
                <td style={{ ...tdS, color: 'var(--txt)', fontWeight: 600 }}>{days(t.carriedDays)}</td>
                <td style={tdS}>{days(t.lapsedDays)}</td>
                <td style={tdS}>{t.carriedDays > 0 ? (t.expiresOn ? fmtDate(t.expiresOn) : 'Never') : '–'}</td>
                <td style={tdS}>{fmtDate(t.updatedAt ?? t.processedAt)}</td>
              </tr>
            ))}
          </Table>
          {rows.length > 0 && <TablePagination {...paged.pagerProps} noun="transfers" />}
        </>
      )}
    </div>
  );
}

// ── Section ────────────────────────────────────────────────────────────────────

export default function LeaveConfigSection({ token }: { token: string }) {
  const can = useCan();
  const canConfigure = can('leave_configuration', 'CONFIGURE');
  const canEdit = can('leave_configuration', 'EDIT');
  const [area, setArea] = useState<Area>('types');
  const [types, setTypes] = useState<LeaveType[] | null>(null);
  const [error, setError] = useState('');

  const [lookups, setLookups] = useState<Lookups>({ departments: [], locations: [], employees: [], grades: [] });

  const reload = useCallback(async () => {
    try { setTypes(await leaveConfigApi.listTypes(token)); setError(''); } catch (e) { setError(errMsg(e)); }
  }, [token]);
  useEffect(() => { if (token) void reload(); }, [token, reload]);
  useEffect(() => {
    if (!token) return;
    // Best effort: without them the "applies to" editor just offers fewer choices.
    Promise.all([
      orgApi.listDepartments(token).catch(() => []),
      orgApi.listLocations(token).catch(() => []),
      leaveConfigApi.employees(token).catch(() => []),
      orgApi.listDesignations(token).catch(() => []),
    ]).then(([d, l, e, des]) => setLookups({
      departments: d.filter(x => x.active).map(x => ({ id: x.id, name: x.name })),
      locations: l.filter(x => x.active).map(x => ({ id: x.id, name: x.name })),
      employees: e,
      grades: [...new Set(des.map(x => x.grade?.trim()).filter((g): g is string => !!g))].sort(),
    }));
  }, [token]);

  const tabs = useMemo(() => [
    { key: 'types' as const, label: 'Leave Types' },
    { key: 'plans' as const, label: 'Leave Plans' },
    { key: 'assignment' as const, label: 'Assignment' },
    { key: 'balances' as const, label: 'Balances' },
    { key: 'yearend' as const, label: 'Year-End' },
    { key: 'exits' as const, label: 'Exits' },
  ], []);

  return (
    <div style={{ padding: 18 }}>
      <TabBar size="sm" ariaLabel="Leave configuration" style={{ marginBottom: 16 }} tabs={tabs} active={area} onChange={setArea} />
      {error && <div role="alert" style={{ color: 'var(--risk)', fontSize: 12.5, marginBottom: 12 }}>Couldn't load leave configuration ({error})</div>}
      {types === null && !error ? (
        <p style={hintS}>Loading…</p>
      ) : types && (
        area === 'types' ? <LeaveTypesArea types={types} token={token} canConfigure={canConfigure} reload={reload} />
          : area === 'plans' ? <LeavePlansArea types={types} token={token} canConfigure={canConfigure} lookups={lookups} />
            : area === 'assignment' ? <LeavePlanAssignment token={token} canConfigure={canConfigure} />
              : area === 'balances' ? <BalancesArea token={token} canConfigure={canConfigure} canEdit={canEdit} />
                : area === 'exits' ? <LeaveExitsArea token={token} canConfigure={canConfigure} />
                  : <YearEndProcessing token={token} canConfigure={canConfigure} />
      )}
    </div>
  );
}
