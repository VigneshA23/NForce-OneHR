import { useCallback, useEffect, useMemo, useState } from 'react';
import { X } from 'lucide-react';
import { TabBar } from '../../components/TabBar';
import { KebabMenu, type KebabItem } from '../../components/KebabMenu';
import { usePaged } from '../../components/usePaged';
import { TablePagination } from '../../components/TablePagination';
import { useCan } from '../../lib/access';
import { useToast } from '../../context/ToastContext';
import {
  leaveConfigApi,
  type AccrualCredit, type CarryForwardPriority, type CarryForwardTransfer, type CreditFrequency, type DirectoryEmployee,
  type EligibilityConfig, type EntitlementPolicy, type LeaveBalance, type LeaveType, type LeaveTypeClassification,
  type LeaveJobRun,
} from '../../api/leave';
import { orgApi } from '../../api/org';
import { AppliesToEditor, EMPTY_ELIGIBILITY, appliesToSummary, sameEligibility, type NamedOption } from './AppliesTo';

/**
 * Organization Masters / Organization Structure > Leave (V310, V314). Three areas: Leave Types (incl.
 * who each type applies to), Leave Entitlements (yearly quota, all-at-once or monthly crediting, an
 * employee's balances and manual adjustment) and Carry-Forward Settings (incl. year-end processing). Everything is gated by leave_configuration on the server; the buttons here
 * only follow the same permission: VIEW reads, CONFIGURE changes policy, EDIT adjusts a balance.
 */

type Area = 'types' | 'entitlements' | 'carryforward';

/** Departments, locations and employees for the "applies to" editor and summaries. */
interface Lookups { departments: NamedOption[]; locations: NamedOption[]; employees: DirectoryEmployee[] }

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

const CURRENT_YEAR = new Date().getFullYear();

const inputS: React.CSSProperties = {
  background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, padding: '8px 10px',
  fontSize: 13, color: 'var(--txt)', width: '100%', boxSizing: 'border-box',
};
const labelS: React.CSSProperties = { fontSize: 12, fontWeight: 600, color: 'var(--txt-mut)', display: 'block', marginBottom: 5 };
const thS: React.CSSProperties = {
  padding: '10px 16px', textAlign: 'left', fontWeight: 600, color: 'var(--txt-dim)', borderBottom: '1px solid var(--line)',
  whiteSpace: 'nowrap', fontSize: 11, letterSpacing: '.04em', textTransform: 'uppercase', background: 'var(--raised)',
};
const tdS: React.CSSProperties = { padding: '10px 16px', color: 'var(--txt-mut)', fontSize: 12.5, borderBottom: '1px solid var(--line)' };
const hintS: React.CSSProperties = { margin: '0 0 14px', fontSize: 12.5, color: 'var(--txt-mut)', lineHeight: 1.55 };
const primaryBtn = (disabled = false): React.CSSProperties => ({
  padding: '7px 16px', background: 'var(--brand)', border: 'none', borderRadius: 6, fontSize: 12.5, fontWeight: 600,
  color: '#fff', cursor: disabled ? 'not-allowed' : 'pointer', opacity: disabled ? 0.6 : 1, whiteSpace: 'nowrap',
});
const secondaryBtn: React.CSSProperties = {
  padding: '7px 14px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6,
  fontSize: 12.5, color: 'var(--txt-mut)', cursor: 'pointer', whiteSpace: 'nowrap',
};

function days(n: number | null | undefined): string {
  if (n == null) return '–';
  return `${Number(n).toLocaleString(undefined, { maximumFractionDigits: 2 })}`;
}

function fmtDate(iso: string | null | undefined): string {
  if (!iso) return '–';
  const d = new Date(iso.length === 10 ? `${iso}T00:00:00` : iso);
  return d.toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' });
}

function errMsg(e: unknown) {
  return e instanceof Error ? e.message : 'Something went wrong';
}

function entitlementLabel(t: LeaveType): string {
  if (t.classification === 'UNPAID') return 'Unlimited';
  if (t.entitlementBasis === 'PER_OCCURRENCE') return `${days(t.currentEntitlementDays)} days per occurrence`;
  return `${days(t.currentEntitlementDays)} days per year`;
}

function frequencyLabel(f: CreditFrequency | null | undefined): string {
  return f === 'MONTHLY' ? 'Monthly' : 'All at once';
}

/** One month's share of a yearly quota, as the server credits it (exact over the year). */
function monthlyShare(quota: number): number {
  return Math.round((quota / 12) * 100) / 100;
}

function CreditFrequencyField({ value, onChange, quota, name }: {
  value: CreditFrequency; onChange(v: CreditFrequency): void; quota: number; name: string;
}) {
  const share = isNaN(quota) || quota <= 0 ? null : monthlyShare(quota);
  return (
    <fieldset style={{ border: 'none', padding: 0, margin: '0 0 14px' }}>
      <legend style={labelS}>Crediting</legend>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
        <label style={{ display: 'flex', alignItems: 'flex-start', gap: 8, fontSize: 13, cursor: 'pointer' }}>
          <input type="radio" name={name} checked={value === 'MONTHLY'} onChange={() => onChange('MONTHLY')} style={{ marginTop: 3 }} />
          <span>
            Monthly, on the 1st of each month
            <span style={{ display: 'block', fontSize: 11.5, color: 'var(--txt-dim)' }}>
              {share != null ? `About ${days(share)} day(s) a month. ` : ''}Employees may apply for up to the full year's quota in advance; later credits cover it.
            </span>
          </span>
        </label>
        <label style={{ display: 'flex', alignItems: 'flex-start', gap: 8, fontSize: 13, cursor: 'pointer' }}>
          <input type="radio" name={name} checked={value === 'YEARLY'} onChange={() => onChange('YEARLY')} style={{ marginTop: 3 }} />
          <span>
            All at once, on 1 January
            <span style={{ display: 'block', fontSize: 11.5, color: 'var(--txt-dim)' }}>
              Someone who joins mid-year gets the months left (counting the joining month), rounded to half a day.
            </span>
          </span>
        </label>
      </div>
    </fieldset>
  );
}

function Badge({ tone, children }: { tone: 'ok' | 'warn' | 'dim' | 'info'; children: React.ReactNode }) {
  const colors = {
    ok: ['rgba(47,182,124,.15)', 'var(--ok)'],
    warn: ['rgba(228,155,15,.15)', 'var(--warn, #b7791f)'],
    dim: ['rgba(107,114,128,.15)', 'var(--txt-dim)'],
    info: ['rgba(79,110,247,.14)', 'var(--brand)'],
  }[tone];
  return (
    <span style={{ display: 'inline-flex', padding: '2px 8px', borderRadius: 20, fontSize: 11, fontWeight: 600, background: colors[0], color: colors[1], whiteSpace: 'nowrap' }}>
      {children}
    </span>
  );
}

/** Same overlay/card chrome as the other Organization Masters dialogs (orgSetupModals.css). */
function Dialog({ title, kind = 'edit', width = 460, onClose, children }: {
  title: string; kind?: 'add' | 'edit' | 'warn'; width?: number; onClose(): void; children: React.ReactNode;
}) {
  return (
    <div className="nf-om-dlg" role="dialog" aria-modal="true" aria-label={title}
      style={{ position: 'fixed', inset: 0, zIndex: 200, display: 'flex', alignItems: 'center', justifyContent: 'center', background: 'rgba(0,0,0,.55)', backdropFilter: 'blur(4px)' }}
      onClick={e => { if (e.target === e.currentTarget) onClose(); }}>
      <div className="nf-om-card" style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, padding: 28, width, maxWidth: '94vw', maxHeight: '92vh', overflowY: 'auto', position: 'relative' }}>
        <h2 data-kind={kind}>{title}</h2>
        <button onClick={onClose} aria-label="Close" className="nf-om-x"><X size={16} /></button>
        {children}
      </div>
    </div>
  );
}

function FormError({ error }: { error: string }) {
  return error ? <div role="alert" style={{ color: 'var(--risk)', fontSize: 12, marginBottom: 12 }}>{error}</div> : null;
}

function DialogActions({ busy, label, onCancel, danger }: { busy: boolean; label: string; onCancel(): void; danger?: boolean }) {
  return (
    <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
      <button type="button" onClick={onCancel} disabled={busy} style={secondaryBtn}>Cancel</button>
      <button type="submit" disabled={busy} style={{ ...primaryBtn(busy), background: danger ? 'var(--risk)' : 'var(--brand)' }}>
        {busy ? 'Please wait…' : label}
      </button>
    </div>
  );
}

function Table({ head, children, empty }: { head: string[]; children: React.ReactNode; empty?: string | false }) {
  return (
    <div style={{ overflowX: 'auto', border: '1px solid var(--line)', borderRadius: 8 }}>
      <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12.5 }}>
        <thead><tr>{head.map(h => <th key={h} style={thS}>{h}</th>)}</tr></thead>
        <tbody>
          {children}
          {empty && <tr><td colSpan={head.length} style={{ ...tdS, textAlign: 'center', padding: '28px 16px', color: 'var(--txt-dim)' }}>{empty}</td></tr>}
        </tbody>
      </table>
    </div>
  );
}

// ── Leave type dialog ──────────────────────────────────────────────────────────

function LeaveTypeDialog({ editRow, token, lookups, onClose, onSaved }: {
  editRow?: LeaveType; token: string; lookups: Lookups; onClose(): void; onSaved(saved: LeaveType): void;
}) {
  const isEdit = !!editRow;
  const isSystem = !!editRow?.systemKey;
  const originalEligibility = editRow?.eligibility ?? EMPTY_ELIGIBILITY;
  const [eligibility, setEligibility] = useState<EligibilityConfig>(originalEligibility);
  const [frequency, setFrequency] = useState<CreditFrequency>('MONTHLY');
  const [name, setName] = useState(editRow?.name ?? '');
  const [code, setCode] = useState(editRow?.code ?? '');
  const [classification, setClassification] = useState<LeaveTypeClassification>(editRow?.classification ?? 'PAID');
  const [basis, setBasis] = useState<'ANNUAL' | 'PER_OCCURRENCE'>(editRow?.entitlementBasis === 'PER_OCCURRENCE' ? 'PER_OCCURRENCE' : 'ANNUAL');
  // Prefill only — the server stores whatever is saved here as the type's entitlement policy.
  const [entitlement, setEntitlement] = useState('15');
  const [windowMonths, setWindowMonths] = useState(String(editRow?.occurrenceWindowMonths ?? 12));
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  const referenced = (editRow?.referenceCount ?? 0) > 0;
  const becomingPaid = isEdit && editRow?.classification === 'UNPAID' && classification === 'PAID';
  const needsDays = classification === 'PAID' && (!isEdit || becomingPaid);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!name.trim()) { setError('Name is required'); return; }
    if (!isEdit && !/^[A-Za-z0-9_]{1,20}$/.test(code.trim())) { setError('Code is required: letters, digits or underscores, up to 20'); return; }
    const daysN = Number(entitlement);
    if (needsDays && (entitlement.trim() === '' || isNaN(daysN) || daysN < 0)) { setError('Entitlement days must be zero or more'); return; }
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
            ...(becomingPaid ? { entitlementDays: daysN } : {}),
          }, token)
        : await leaveConfigApi.createType({
            code: code.trim(), name: name.trim(), classification,
            ...(classification === 'PAID' ? { entitlementBasis: basis, entitlementDays: daysN } : {}),
            ...(classification === 'PAID' && basis === 'ANNUAL' ? { creditFrequency: frequency } : {}),
            ...(perOccurrence ? { occurrenceWindowMonths: monthsN } : {}),
          }, token);
      const final = sameEligibility(eligibility, originalEligibility)
        ? saved
        : await leaveConfigApi.updateEligibility(saved.id, eligibility, token);
      onSaved(final);
      onClose();
    } catch (err) {
      setError(errMsg(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Dialog title={isEdit ? 'Edit Leave Type' : 'Add Leave Type'} kind={isEdit ? 'edit' : 'add'} width={580} onClose={onClose}>
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
                <input type="radio" name="lt-classification" checked={classification === c} disabled={isSystem} onChange={() => setClassification(c)} />
                {c === 'PAID' ? 'Paid' : 'Unpaid (unlimited, never uses paid balances)'}
              </label>
            ))}
          </div>
          {isSystem && <p style={{ ...hintS, margin: '6px 0 0', fontSize: 11.5 }}>Built-in type: it can be renamed and restricted, but not reclassified.</p>}
        </fieldset>
        {classification === 'PAID' && (
          <>
            <div style={{ marginBottom: 14 }}>
              <label style={labelS} htmlFor="lt-basis">Entitlement basis</label>
              <select id="lt-basis" style={inputS} value={basis} disabled={referenced || isSystem}
                onChange={e => {
                  const v = e.target.value as 'ANNUAL' | 'PER_OCCURRENCE';
                  setBasis(v);
                  if (!isEdit) setEntitlement(v === 'PER_OCCURRENCE' ? '182' : '15');
                }}>
                <option value="ANNUAL">Per leave year (own balance, can carry forward)</option>
                <option value="PER_OCCURRENCE">Per eligible occurrence (e.g. Maternity)</option>
              </select>
              {referenced && <p style={{ ...hintS, margin: '6px 0 0', fontSize: 11.5 }}>Already in use, so the basis can no longer change.</p>}
            </div>
            {needsDays && (
              <div style={{ marginBottom: 14 }}>
                <label style={labelS} htmlFor="lt-days">{basis === 'PER_OCCURRENCE' ? 'Days per occurrence *' : 'Days per year *'}</label>
                <input id="lt-days" type="number" min={0} max={999} step={0.5} style={inputS} value={entitlement} onChange={e => setEntitlement(e.target.value)} />
              </div>
            )}
            {!isEdit && basis === 'ANNUAL' && (
              <CreditFrequencyField value={frequency} onChange={setFrequency} quota={Number(entitlement)} name="lt-frequency" />
            )}
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
        {isEdit && classification === 'PAID' && !becomingPaid && (
          <p style={{ ...hintS, fontSize: 11.5 }}>Change the number of days and how they're credited under Leave Entitlements, with an effective year.</p>
        )}
        <AppliesToEditor value={eligibility} onChange={setEligibility} departments={lookups.departments} locations={lookups.locations}
          employees={lookups.employees} token={token} />
        <FormError error={error} />
        <DialogActions busy={busy} label={isEdit ? 'Save Changes' : 'Add'} onCancel={onClose} />
      </form>
    </Dialog>
  );
}

/** Confirm-or-explain dialog, the Document Types deletion pattern. */
function ConfirmDialog({ title, body, confirmLabel, danger, onConfirm, onClose }: {
  title: string; body: string; confirmLabel?: string; danger?: boolean; onConfirm?: () => Promise<void>; onClose(): void;
}) {
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!onConfirm) { onClose(); return; }
    setBusy(true);
    try { await onConfirm(); onClose(); } catch (err) { setError(errMsg(err)); setBusy(false); }
  }
  return (
    <Dialog title={title} kind={danger ? 'warn' : 'edit'} width={420} onClose={onClose}>
      <form onSubmit={submit}>
        <p style={{ margin: '0 0 20px', fontSize: 13, color: 'var(--txt-mut)', lineHeight: 1.55 }}>{body}</p>
        <FormError error={error} />
        <DialogActions busy={busy} label={confirmLabel ?? 'Got it'} danger={danger} onCancel={onClose} />
      </form>
    </Dialog>
  );
}

type Confirm = { title: string; body: string; confirmLabel?: string; danger?: boolean; onConfirm?: () => Promise<void> };

// ── Area 1: Leave Types ────────────────────────────────────────────────────────

function LeaveTypesArea({ types, token, canConfigure, lookups, reload }: {
  types: LeaveType[]; token: string; canConfigure: boolean; lookups: Lookups; reload(): Promise<void>;
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
      body: `"${t.name}" and its entitlement settings will be permanently deleted. Nothing references it yet.`,
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
          Paid Leave and Unpaid Leave are built in: they can be renamed and restricted but not removed. Every paid type keeps
          its own balance; unpaid types are unlimited. Use "Applies to" to limit a type to some employees (e.g. Maternity to
          female employees). Types already used by requests or balances can be deactivated but not deleted.
        </p>
        {canConfigure && <button style={primaryBtn()} onClick={() => setDialog({ key: Date.now() })}>Add Leave Type</button>}
      </div>
      <Table head={['Leave Type', 'Code', 'Classification', 'Entitlement', 'Applies to', 'Usage', 'Status', '']}
        empty={types.length === 0 && 'No leave types configured yet. Add one to start collecting leave requests.'}>
        {types.map(t => (
          <tr key={t.id}>
            <td style={{ ...tdS, color: 'var(--txt)', fontWeight: 500 }}>
              {t.name}{t.systemKey && <span style={{ marginLeft: 8 }}><Badge tone="info">Built-in</Badge></span>}
            </td>
            <td style={{ ...tdS, fontFamily: 'var(--font-mono, monospace)', fontSize: 12 }}>{t.code}</td>
            <td style={tdS}><Badge tone={t.classification === 'PAID' ? 'ok' : 'warn'}>{t.classification === 'PAID' ? 'Paid' : 'Unpaid'}</Badge></td>
            <td style={tdS}>
              {entitlementLabel(t)}
              {t.classification === 'PAID' && t.entitlementBasis === 'ANNUAL' && (
                <div style={{ fontSize: 11, color: 'var(--txt-dim)' }}>{frequencyLabel(t.currentCreditFrequency)}</div>
              )}
            </td>
            <td style={{ ...tdS, maxWidth: 260 }}>{appliesToSummary(t, lookups.departments, lookups.locations)}</td>
            <td style={tdS}>{t.referenceCount ?? 0}</td>
            <td style={tdS}><Badge tone={t.active ? 'ok' : 'dim'}>{t.active ? 'Active' : 'Inactive'}</Badge></td>
            <td style={{ ...tdS, textAlign: 'right', width: 52 }}><KebabMenu items={items(t)} /></td>
          </tr>
        ))}
      </Table>
      {dialog && (
        <LeaveTypeDialog key={dialog.key} editRow={dialog.row} token={token} lookups={lookups} onClose={() => setDialog(null)}
          onSaved={saved => { showToast('success', dialog.row ? 'Leave type updated' : `"${saved.name}" created`); void reload(); }} />
      )}
      {confirm && <ConfirmDialog {...confirm} onClose={() => setConfirm(null)} />}
    </div>
  );
}

// ── Area 2: Leave Entitlements ─────────────────────────────────────────────────

function EntitlementDialog({ type, token, onClose, onSaved }: { type: LeaveType; token: string; onClose(): void; onSaved(): void }) {
  const [history, setHistory] = useState<EntitlementPolicy[] | null>(null);
  const [year, setYear] = useState(String(CURRENT_YEAR + 1));
  const [value, setValue] = useState(String(type.currentEntitlementDays ?? ''));
  const [frequency, setFrequency] = useState<CreditFrequency>(type.currentCreditFrequency ?? 'YEARLY');
  const [apply, setApply] = useState(false);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const perOccurrence = type.entitlementBasis === 'PER_OCCURRENCE';

  useEffect(() => {
    leaveConfigApi.listEntitlements(type.id, token).then(setHistory).catch(e => setError(errMsg(e)));
  }, [type.id, token]);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    const y = Number(year), d = Number(value);
    if (!Number.isInteger(y) || y < 2000 || y > 2100) { setError('Effective year must be between 2000 and 2100'); return; }
    if (value.trim() === '' || isNaN(d) || d < 0 || d > 999) { setError('Days must be between 0 and 999'); return; }
    setBusy(true);
    setError('');
    try {
      await leaveConfigApi.changeEntitlement(type.id, {
        effectiveYear: y, days: d, applyToExistingBalances: apply && !perOccurrence,
        ...(perOccurrence ? {} : { creditFrequency: frequency }),
      }, token);
      onSaved();
      onClose();
    } catch (err) {
      setError(errMsg(err));
      setBusy(false);
    }
  }

  return (
    <Dialog title={`${type.name} entitlement`} width={500} onClose={onClose}>
      <form onSubmit={submit}>
        <p style={hintS}>
          {perOccurrence
            ? 'Days granted for each eligible occurrence. An occurrence already open keeps the entitlement it started with.'
            : 'Days granted per leave year from the effective year onwards, and how they reach employees. Months not credited yet follow the new figure; days already credited stay unless you choose to update them.'}
        </p>
        <div style={{ display: 'flex', gap: 12, marginBottom: 14, flexWrap: 'wrap' }}>
          <div style={{ flex: '1 1 140px' }}>
            <label style={labelS} htmlFor="ent-year">Effective from year *</label>
            <input id="ent-year" type="number" min={2000} max={2100} style={inputS} value={year} onChange={e => setYear(e.target.value)} />
          </div>
          <div style={{ flex: '1 1 140px' }}>
            <label style={labelS} htmlFor="ent-days">{perOccurrence ? 'Days per occurrence *' : 'Days per year *'}</label>
            <input id="ent-days" type="number" min={0} max={999} step={0.5} style={inputS} value={value} onChange={e => setValue(e.target.value)} />
          </div>
        </div>
        {!perOccurrence && <CreditFrequencyField value={frequency} onChange={setFrequency} quota={Number(value)} name="ent-frequency" />}
        {!perOccurrence && (
          <label style={{ display: 'flex', alignItems: 'flex-start', gap: 8, fontSize: 12.5, color: 'var(--txt-mut)', marginBottom: 16, cursor: 'pointer' }}>
            <input type="checkbox" checked={apply} onChange={e => setApply(e.target.checked)} style={{ marginTop: 2 }} />
            Also re-credit days already credited in these years at the new figure (used and carried-forward days are kept).
          </label>
        )}
        <h3 style={{ fontSize: 12, fontWeight: 700, color: 'var(--txt-mut)', margin: '0 0 8px', textTransform: 'uppercase', letterSpacing: '.04em' }}>History</h3>
        <div style={{ marginBottom: 16 }}>
          {history === null ? <p style={hintS}>Loading…</p> : (
            <Table head={perOccurrence ? ['From year', 'Days', 'Set by'] : ['From year', 'Days', 'Crediting', 'Set by']}
              empty={history.length === 0 && 'No entitlement configured yet.'}>
              {history.map(h => (
                <tr key={h.id}>
                  <td style={tdS}>{h.effectiveYear <= 2000 ? 'Default (all years)' : h.effectiveYear}</td>
                  <td style={tdS}>{days(h.days)}</td>
                  {!perOccurrence && <td style={tdS}>{frequencyLabel(h.creditFrequency)}</td>}
                  <td style={tdS}>{h.createdByName}</td>
                </tr>
              ))}
            </Table>
          )}
        </div>
        <FormError error={error} />
        <DialogActions busy={busy} label="Save Entitlement" onCancel={onClose} />
      </form>
    </Dialog>
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
              <td style={{ ...tdS, color: 'var(--txt)' }}>{days(r.days)}</td>
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

function EntitlementsArea({ types, token, canConfigure, canEdit, reload }: {
  types: LeaveType[]; token: string; canConfigure: boolean; canEdit: boolean; reload(): Promise<void>;
}) {
  const { showToast } = useToast();
  const [editing, setEditing] = useState<LeaveType | null>(null);
  return (
    <div>
      <p style={hintS}>
        Regular paid leave has a yearly quota (January–December), each type on its own balance, credited either all at once
        on 1 January or a twelfth on the 1st of each month. With monthly crediting employees may apply for up to the whole
        year's quota in advance. Per-occurrence leave such as Maternity is granted once per eligible occurrence. Unpaid leave
        is unlimited.
      </p>
      <Table head={['Leave Type', 'Basis', `Entitlement in ${CURRENT_YEAR}`, 'Crediting', '']} empty={types.length === 0 && 'No leave types configured yet.'}>
        {types.map(t => (
          <tr key={t.id}>
            <td style={{ ...tdS, color: 'var(--txt)', fontWeight: 500 }}>
              {t.name}{!t.active && <span style={{ marginLeft: 8 }}><Badge tone="dim">Inactive</Badge></span>}
            </td>
            <td style={tdS}>{t.classification === 'UNPAID' ? 'Unpaid' : t.entitlementBasis === 'PER_OCCURRENCE' ? 'Per occurrence' : 'Per leave year'}</td>
            <td style={tdS}>{t.classification === 'UNPAID' ? <Badge tone="info">Unlimited</Badge> : entitlementLabel(t)}</td>
            <td style={tdS}>
              {t.classification === 'PAID' && t.entitlementBasis === 'ANNUAL' ? frequencyLabel(t.currentCreditFrequency) : '–'}
            </td>
            <td style={{ ...tdS, textAlign: 'right' }}>
              {t.classification === 'PAID' && (
                <button style={secondaryBtn} onClick={() => setEditing(t)}>{canConfigure ? 'Change' : 'History'}</button>
              )}
            </td>
          </tr>
        ))}
      </Table>
      {editing && (canConfigure ? (
        <EntitlementDialog type={editing} token={token} onClose={() => setEditing(null)}
          onSaved={() => { showToast('success', `${editing.name} entitlement saved`); void reload(); }} />
      ) : (
        <EntitlementHistoryDialog type={editing} token={token} onClose={() => setEditing(null)} />
      ))}
      <CreditingRun token={token} canConfigure={canConfigure} />
      <EmployeeBalances token={token} canEdit={canEdit} />
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
  async function run() {
    setBusy(true);
    try {
      const r = await leaveConfigApi.runAccrual(token);
      showToast(r.failures > 0 ? 'error' : 'success',
        `${r.creditsAdded} employee balance(s) credited${r.failures > 0 ? `, ${r.failures} failed (${(r.failedEmployees ?? []).join(', ')}) — run again to retry` : ''}`);
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
          Runs automatically every night: whatever is due is credited, once. New joiners get their joining month in full.
          Nothing more is credited after an employee's last working month, or for a type that no longer applies to them.
        </p>
        {canConfigure && <button style={primaryBtn(busy)} disabled={busy} onClick={run}>{busy ? 'Crediting…' : 'Run crediting now'}</button>}
      </div>
      <LastRun run={lastRun.run} summary={d => `${Number(d.creditsAdded ?? 0)} balance(s) credited for ${d.year ?? ''}, ${Number(d.employeesChecked ?? 0)} checked`} />
    </div>
  );
}

function EntitlementHistoryDialog({ type, token, onClose }: { type: LeaveType; token: string; onClose(): void }) {
  const [history, setHistory] = useState<EntitlementPolicy[] | null>(null);
  const [error, setError] = useState('');
  useEffect(() => { leaveConfigApi.listEntitlements(type.id, token).then(setHistory).catch(e => setError(errMsg(e))); }, [type.id, token]);
  return (
    <ConfirmDialog title={`${type.name} entitlement history`} onClose={onClose}
      body={error || (history === null ? 'Loading…' : history.map(h => `${h.effectiveYear <= 2000 ? 'Default' : `From ${h.effectiveYear}`}: ${days(h.days)} days${type.entitlementBasis === 'ANNUAL' ? `, ${frequencyLabel(h.creditFrequency).toLowerCase()}` : ''}`).join(' · ') || 'None')} />
  );
}

// ── Area 3: Carry-Forward Settings ─────────────────────────────────────────────

function CarryForwardRow({ type, token, canConfigure, onSaved }: { type: LeaveType; token: string; canConfigure: boolean; onSaved(): void }) {
  const { showToast } = useToast();
  const [enabled, setEnabled] = useState(!!type.carryForwardEnabled);
  const [maxDays, setMaxDays] = useState(type.carryForwardMaxDays == null ? '' : String(type.carryForwardMaxDays));
  const [expiry, setExpiry] = useState(type.carryForwardExpiryMonths == null ? '' : String(type.carryForwardExpiryMonths));
  const [priority, setPriority] = useState<CarryForwardPriority>(type.carryForwardPriority ?? 'CARRY_FORWARD_FIRST');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const dirty = enabled !== !!type.carryForwardEnabled
    || maxDays !== (type.carryForwardMaxDays == null ? '' : String(type.carryForwardMaxDays))
    || expiry !== (type.carryForwardExpiryMonths == null ? '' : String(type.carryForwardExpiryMonths))
    || priority !== (type.carryForwardPriority ?? 'CARRY_FORWARD_FIRST');

  async function save() {
    const max = maxDays.trim() === '' ? null : Number(maxDays);
    const months = expiry.trim() === '' ? null : Number(expiry);
    if (max != null && (isNaN(max) || max < 0)) { setError('Maximum must be zero or more'); return; }
    if (months != null && (!Number.isInteger(months) || months < 1 || months > 24)) { setError('Expiry must be 1–24 months'); return; }
    setBusy(true);
    setError('');
    try {
      await leaveConfigApi.updateCarryForward(type.id, { enabled, maxDays: max, expiryMonths: months, priority }, token);
      showToast('success', `${type.name} carry-forward saved`);
      onSaved();
    } catch (e) {
      setError(errMsg(e));
    } finally {
      setBusy(false);
    }
  }

  const disabled = !canConfigure;
  return (
    <tr>
      <td style={{ ...tdS, color: 'var(--txt)', fontWeight: 500 }}>{type.name}</td>
      <td style={tdS}>
        <label style={{ display: 'flex', alignItems: 'center', gap: 7, cursor: disabled ? 'default' : 'pointer' }}>
          <input type="checkbox" checked={enabled} disabled={disabled} onChange={e => setEnabled(e.target.checked)} aria-label={`Carry forward ${type.name}`} />
          {enabled ? 'Enabled' : 'Disabled'}
        </label>
      </td>
      <td style={tdS}>
        <input type="number" min={0} max={999} step={0.5} style={{ ...inputS, width: 90 }} value={maxDays} disabled={disabled || !enabled}
          placeholder="No cap" aria-label={`${type.name} maximum carry-forward days`} onChange={e => setMaxDays(e.target.value)} />
      </td>
      <td style={tdS}>
        <input type="number" min={1} max={24} style={{ ...inputS, width: 90 }} value={expiry} disabled={disabled || !enabled}
          placeholder="Never" aria-label={`${type.name} expiry in months`} onChange={e => setExpiry(e.target.value)} />
      </td>
      <td style={tdS}>
        <select style={{ ...inputS, width: 170 }} value={priority} disabled={disabled || !enabled} aria-label={`${type.name} consumption priority`}
          onChange={e => setPriority(e.target.value as CarryForwardPriority)}>
          <option value="CARRY_FORWARD_FIRST">Carried-forward first</option>
          <option value="CURRENT_FIRST">Current year first</option>
        </select>
      </td>
      <td style={{ ...tdS, textAlign: 'right' }}>
        {canConfigure && <button style={primaryBtn(busy || !dirty)} disabled={busy || !dirty} onClick={save}>{busy ? 'Saving…' : 'Save'}</button>}
        {error && <div role="alert" style={{ color: 'var(--risk)', fontSize: 11.5, marginTop: 6, whiteSpace: 'normal' }}>{error}</div>}
      </td>
    </tr>
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
        Runs automatically every night once a leave year has ended: unused days up to the maximum move into the next year's
        carried-forward balance, the rest lapse, and carried-forward days expire on their expiry date. Running it again never
        transfers twice. Leave still pending at year-end stays reserved in the old year.
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

function CarryForwardArea({ types, token, canConfigure, reload }: { types: LeaveType[]; token: string; canConfigure: boolean; reload(): Promise<void> }) {
  const eligible = types.filter(t => t.classification === 'PAID' && t.entitlementBasis === 'ANNUAL');
  const ineligible = types.filter(t => !eligible.includes(t));
  return (
    <div>
      <p style={hintS}>
        Choose, per regular paid leave type, whether unused days move into the next leave year, the most that may move, when
        they expire (months into the new year; blank = never) and which days are used first. Disabling carry-forward never
        removes days already carried.
      </p>
      <Table head={['Leave Type', 'Carry forward', 'Maximum days', 'Expires after (months)', 'Use first', '']}
        empty={eligible.length === 0 && 'No regular paid leave types.'}>
        {eligible.map(t => (
          <CarryForwardRow key={`${t.id}:${t.carryForwardEnabled}:${t.carryForwardMaxDays}:${t.carryForwardExpiryMonths}:${t.carryForwardPriority}`}
            type={t} token={token} canConfigure={canConfigure} onSaved={() => void reload()} />
        ))}
      </Table>
      {ineligible.length > 0 && (
        <p style={{ ...hintS, marginTop: 10, fontSize: 11.5 }}>
          Not eligible for carry-forward: {ineligible.map(t => t.name).join(', ')} (unpaid or per-occurrence leave).
        </p>
      )}
      <YearEndProcessing token={token} canConfigure={canConfigure} />
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

  const [lookups, setLookups] = useState<Lookups>({ departments: [], locations: [], employees: [] });

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
    ]).then(([d, l, e]) => setLookups({
      departments: d.filter(x => x.active).map(x => ({ id: x.id, name: x.name })),
      locations: l.filter(x => x.active).map(x => ({ id: x.id, name: x.name })),
      employees: e,
    }));
  }, [token]);

  const tabs = useMemo(() => [
    { key: 'types' as const, label: 'Leave Types' },
    { key: 'entitlements' as const, label: 'Leave Entitlements' },
    { key: 'carryforward' as const, label: 'Carry-Forward Settings' },
  ], []);

  return (
    <div style={{ padding: 18 }}>
      <TabBar size="sm" ariaLabel="Leave configuration" style={{ marginBottom: 16 }} tabs={tabs} active={area} onChange={setArea} />
      {error && <div role="alert" style={{ color: 'var(--risk)', fontSize: 12.5, marginBottom: 12 }}>Couldn't load leave configuration ({error})</div>}
      {types === null && !error ? (
        <p style={hintS}>Loading…</p>
      ) : types && (
        area === 'types' ? <LeaveTypesArea types={types} token={token} canConfigure={canConfigure} lookups={lookups} reload={reload} />
          : area === 'entitlements' ? <EntitlementsArea types={types} token={token} canConfigure={canConfigure} canEdit={canEdit} reload={reload} />
            : <CarryForwardArea types={types} token={token} canConfigure={canConfigure} reload={reload} />
      )}
    </div>
  );
}
