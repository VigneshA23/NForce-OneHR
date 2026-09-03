import { useEffect, useRef, useState } from 'react';
import { Search, X, Users, ChevronLeft, ChevronRight, History, UserPlus } from 'lucide-react';
import { useToast } from '../../context/ToastContext';
import { KebabMenu, type KebabItem } from '../../components/KebabMenu';
import { orgApi, type BusinessUnitRow, type DepartmentRow, type LocationRow } from '../../api/org';
import { penalisationPoliciesApi, type PenalisationPolicySummary } from '../../api/penalisationPolicies';
import {
  penalizationPolicyAllocationApi,
  type AllocationDto,
  type AllocationBulkResult,
  type EmployeeAllocationRow,
  type EmployeeAllocationSearchResponse,
  type EmployeeAllocationDetailResponse,
  type ResolvedPolicySource,
} from '../../api/penalizationPolicyAllocation';

/** Gap-015: per-employee bulk-failure detail, mirroring MyTeamPage's AssignmentActionResult. */
interface AllocationActionResult {
  succeeded: number;
  failures: { label: string; reason: string }[];
}

function toActionResult(res: AllocationBulkResult, employees: { employeeUserId: string; fullName: string }[]): AllocationActionResult {
  return {
    succeeded: res.succeededIds.length,
    failures: res.failed.map(f => ({
      label: employees.find(e => e.employeeUserId === f.employeeUserId)?.fullName ?? f.employeeUserId,
      reason: f.reason,
    })),
  };
}

// The main Allocation table shows every matching employee at once (no pagination) — only the
// Add Employees modal still paginates its own independent employee search.
const ADD_MODAL_PAGE_SIZE = 20;

const inputStyle: React.CSSProperties = {
  background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6,
  padding: '7px 10px', fontSize: 12.5, color: 'var(--txt)', outline: 'none',
};
const labelText: React.CSSProperties = { fontSize: 11, fontWeight: 600, color: 'var(--txt-mut)', display: 'block', marginBottom: 5 };

function fmtDate(iso: string | null | undefined): string {
  if (!iso) return '—';
  const [y, m, d] = iso.split('-').map(Number);
  return new Date(y, m - 1, d).toLocaleDateString(undefined, { day: '2-digit', month: 'short', year: 'numeric' });
}

function SourceBadge({ source }: { source: ResolvedPolicySource }) {
  const copy = source === 'ALLOCATION' ? 'Allocated'
    : source === 'LEGACY' ? 'Legacy'
    : source === 'ALLOCATION_REQUIRED' ? 'Needs Allocation'
    : 'Org Default';
  const color = source === 'ALLOCATION' ? 'var(--brand-bright)'
    : source === 'LEGACY' ? '#E0A93B'
    : source === 'ALLOCATION_REQUIRED' ? 'var(--risk)'
    : 'var(--txt-dim)';
  return (
    <span style={{ fontSize: 10, fontWeight: 700, color, textTransform: 'uppercase', letterSpacing: '.04em' }}>
      {copy}
    </span>
  );
}

function StatusPill({ status }: { status: 'CURRENT' | 'FUTURE' | 'HISTORICAL' }) {
  const bg = status === 'CURRENT' ? 'rgba(47,182,124,.15)' : status === 'FUTURE' ? 'rgba(76,141,214,.15)' : 'rgba(107,114,128,.15)';
  const color = status === 'CURRENT' ? 'var(--ok)' : status === 'FUTURE' ? '#4C8DD6' : 'var(--txt-dim)';
  return (
    <span style={{ display: 'inline-flex', padding: '2px 8px', borderRadius: 20, fontSize: 10.5, fontWeight: 600, background: bg, color }}>
      {status}
    </span>
  );
}

/** Minimal shape shared by table rows and the detail modal for the assign/edit/remove actions. */
interface AllocatableEmployee {
  employeeUserId: string;
  fullName: string;
  currentAllocation: AllocationDto | null;
}

// ── ConfirmModal (local, minimal) ───────────────────────────────────────────────
function ConfirmModal({ title, body, confirmLabel, danger, onConfirm, onClose }: {
  title: string; body: string; confirmLabel: string; danger?: boolean;
  onConfirm: () => Promise<void>; onClose: () => void;
}) {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  async function handleConfirm() {
    setLoading(true); setError('');
    try { await onConfirm(); onClose(); }
    catch (e) { setError(e instanceof Error ? e.message : 'Something went wrong'); setLoading(false); }
  }
  return (
    <div role="dialog" aria-modal="true" style={{ position: 'fixed', inset: 0, zIndex: 220, display: 'flex', alignItems: 'center', justifyContent: 'center', background: 'rgba(0,0,0,.55)', backdropFilter: 'blur(4px)' }}
      onClick={e => { if (e.target === e.currentTarget) onClose(); }}>
      <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, padding: '22px 26px', width: 420, maxWidth: '95vw' }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 14 }}>
          <h2 style={{ margin: 0, fontSize: 15, fontFamily: '"Space Grotesk", sans-serif', fontWeight: 700, color: 'var(--txt)' }}>{title}</h2>
          <button onClick={onClose} aria-label="Close" style={{ background: 'transparent', border: 'none', cursor: 'pointer', color: 'var(--txt-dim)' }}><X size={16} /></button>
        </div>
        <p style={{ margin: '0 0 20px', fontSize: 13, color: 'var(--txt-mut)', lineHeight: 1.55 }}>{body}</p>
        {error && <div role="alert" style={{ background: 'rgba(228,55,61,.1)', border: '1px solid rgba(228,55,61,.3)', borderRadius: 6, padding: '8px 12px', marginBottom: 16, color: 'var(--risk)', fontSize: 12.5 }}>{error}</div>}
        <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
          <button type="button" onClick={onClose} style={{ padding: '7px 14px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, fontSize: 12.5, color: 'var(--txt-mut)', cursor: 'pointer' }}>Cancel</button>
          <button type="button" onClick={handleConfirm} disabled={loading} style={{ padding: '7px 16px', background: danger ? 'var(--risk)' : 'var(--brand)', border: 'none', borderRadius: 6, fontSize: 12.5, fontWeight: 600, color: '#fff', cursor: loading ? 'not-allowed' : 'pointer', opacity: loading ? 0.7 : 1 }}>
            {loading ? 'Please wait…' : confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}

// ── Allocate/Edit modal — one policy + effective-date-range picker, used for individual add/edit and bulk ──
type AllocateAction =
  | { type: 'assign'; penalisationPolicyId: string; effectiveFrom: string; effectiveTo: string | null }
  | { type: 'remove' };

interface AllocateModalProps {
  title: string;
  token: string;
  employeeUserIds: string[];
  /** For per-employee name resolution in the conflict preview — falls back to the raw id when a
   * selected employee isn't in this list (e.g. selected on a different page of a paginated search). */
  employees?: { employeeUserId: string; fullName: string }[];
  policies: PenalisationPolicySummary[];
  initial?: AllocationDto | null;
  /** Only offered for bulk actions — individual remove has its own dedicated confirmation flow. */
  allowRemoveOption?: boolean;
  onClose(): void;
  onSubmit(action: AllocateAction): Promise<void>;
}

function AllocateModal({ title, token, employeeUserIds, employees, policies, initial, allowRemoveOption, onClose, onSubmit }: AllocateModalProps) {
  const employeeCount = employeeUserIds.length;
  const [removeMode, setRemoveMode] = useState(false);
  const [policyId, setPolicyId] = useState(initial?.penalisationPolicyId ?? '');
  const [effectiveFrom, setEffectiveFrom] = useState(initial?.effectiveFrom ?? new Date().toISOString().slice(0, 10));
  const [noEndDate, setNoEndDate] = useState(!initial || initial.effectiveTo == null);
  const [effectiveTo, setEffectiveTo] = useState(initial?.effectiveTo ?? '');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const [conflicts, setConflicts] = useState<Record<string, AllocationDto>>({});
  const activePolicies = policies.filter(p => p.status === 'ACTIVE');

  // Gap-016: pre-submit preview of the same overlap check the backend enforces at submit time —
  // debounced so it doesn't fire on every keystroke, and purely advisory (submit is still the
  // authoritative check; this can't block it, only warn ahead of it).
  useEffect(() => {
    if (removeMode || !policyId || !effectiveFrom) { setConflicts({}); return; }
    const timer = setTimeout(() => {
      penalizationPolicyAllocationApi.checkConflicts(token, {
        employeeUserIds,
        effectiveFrom,
        effectiveTo: noEndDate ? null : (effectiveTo || null),
        excludeAllocationId: initial?.id,
      }).then(setConflicts).catch(() => setConflicts({}));
    }, 350);
    return () => clearTimeout(timer);
  }, [token, employeeUserIds, removeMode, policyId, effectiveFrom, effectiveTo, noEndDate, initial?.id]);

  const conflictEntries = Object.entries(conflicts);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setError('');
    setLoading(true);
    try {
      if (removeMode) {
        await onSubmit({ type: 'remove' });
      } else {
        if (!policyId) { setError('Select a Penalization Policy'); setLoading(false); return; }
        if (!effectiveFrom) { setError('Effective From is required'); setLoading(false); return; }
        if (!noEndDate && !effectiveTo) { setError('Effective To is required unless "No end date" is checked'); setLoading(false); return; }
        if (!noEndDate && effectiveTo < effectiveFrom) { setError('Effective To cannot be before Effective From'); setLoading(false); return; }
        await onSubmit({ type: 'assign', penalisationPolicyId: policyId, effectiveFrom, effectiveTo: noEndDate ? null : effectiveTo });
      }
      onClose();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Something went wrong');
    } finally {
      setLoading(false);
    }
  }

  return (
    <div role="dialog" aria-modal="true" aria-label={title} style={{ position: 'fixed', inset: 0, zIndex: 220, display: 'flex', alignItems: 'center', justifyContent: 'center', background: 'rgba(0,0,0,.55)', backdropFilter: 'blur(4px)' }}
      onClick={e => { if (e.target === e.currentTarget) onClose(); }}>
      <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, padding: '24px 26px', width: 440, maxWidth: '95vw' }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 6 }}>
          <h2 style={{ margin: 0, fontSize: 15, fontFamily: '"Space Grotesk", sans-serif', fontWeight: 700, color: 'var(--txt)' }}>{title}</h2>
          <button onClick={onClose} aria-label="Close" style={{ background: 'transparent', border: 'none', cursor: 'pointer', color: 'var(--txt-dim)' }}><X size={16} /></button>
        </div>
        <p style={{ margin: '0 0 16px', fontSize: 12.5, color: 'var(--txt-mut)' }}>
          Selected Employees: <strong style={{ color: 'var(--txt)' }}>{employeeCount === 1 ? '1 employee' : `${employeeCount} employees`}</strong> selected
        </p>
        {error && <div role="alert" style={{ background: 'rgba(228,55,61,.1)', border: '1px solid rgba(228,55,61,.3)', borderRadius: 6, padding: '8px 12px', marginBottom: 14, color: 'var(--risk)', fontSize: 12.5 }}>{error}</div>}
        <form onSubmit={submit} style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
          <div>
            <span style={labelText}>Choose Penalization Policy {!removeMode && '*'}</span>
            <select style={{ ...inputStyle, width: '100%' }} value={policyId} disabled={removeMode}
              onChange={e => setPolicyId(e.target.value)}>
              <option value="">— Select Penalization Policy —</option>
              {activePolicies.map(p => <option key={p.id} value={p.id}>{p.name}</option>)}
            </select>
          </div>
          <div className="nf-grid-2col-collapse" style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
            <div>
              <span style={labelText}>Effective From {!removeMode && '*'}</span>
              <input type="date" style={{ ...inputStyle, width: '100%' }} value={effectiveFrom} disabled={removeMode}
                onChange={e => setEffectiveFrom(e.target.value)} />
              <div style={{ fontSize: 11.5, color: 'var(--txt-dim)', marginTop: 4 }}>
                When this employee's assignment to the selected policy starts — separate from the policy's own effective date on the Penalization Policy screen.
              </div>
            </div>
            <div>
              <span style={labelText}>Effective Up To</span>
              <input type="date" style={{ ...inputStyle, width: '100%' }} value={effectiveTo} disabled={noEndDate || removeMode}
                onChange={e => setEffectiveTo(e.target.value)} min={effectiveFrom} />
            </div>
          </div>
          {conflictEntries.length > 0 && (
            <div role="alert" style={{ background: 'rgba(181,101,29,.1)', border: '1px solid rgba(181,101,29,.3)', borderRadius: 6, padding: '8px 12px', color: '#B5651D', fontSize: 12 }}>
              <div style={{ fontWeight: 600, marginBottom: 4 }}>This date range overlaps an existing allocation:</div>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 3 }}>
                {conflictEntries.map(([empId, conflict]) => {
                  const label = employees?.find(e => e.employeeUserId === empId)?.fullName ?? empId;
                  return (
                    <div key={empId}>
                      {employeeCount > 1 ? `${label}: ` : ''}already allocated to "{conflict.penalisationPolicyName}" from {fmtDate(conflict.effectiveFrom)}
                      {conflict.effectiveTo ? ` to ${fmtDate(conflict.effectiveTo)}` : ' onward (no end date)'}.
                    </div>
                  );
                })}
              </div>
            </div>
          )}
          <label style={{ display: 'flex', alignItems: 'center', gap: 7, fontSize: 12.5, color: 'var(--txt-mut)', cursor: removeMode ? 'not-allowed' : 'pointer' }}>
            <input type="checkbox" checked={noEndDate} disabled={removeMode} onChange={e => setNoEndDate(e.target.checked)} style={{ width: 14, height: 14 }} />
            No End Date (stays in effect until changed)
          </label>
          {allowRemoveOption && (
            <label style={{ display: 'flex', alignItems: 'center', gap: 7, fontSize: 12.5, color: 'var(--risk)', cursor: 'pointer', borderTop: '1px solid var(--line)', paddingTop: 12 }}>
              <input type="checkbox" checked={removeMode} onChange={e => { setRemoveMode(e.target.checked); setError(''); }} style={{ width: 14, height: 14 }} />
              Remove Penalization Policy (clears the current allocation for all selected employees instead of assigning one)
            </label>
          )}
          <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end', marginTop: 4 }}>
            <button type="button" onClick={onClose} style={{ padding: '7px 14px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, fontSize: 12.5, color: 'var(--txt-mut)', cursor: 'pointer' }}>Cancel</button>
            <button type="submit" disabled={loading} style={{ padding: '7px 16px', background: removeMode ? 'var(--risk)' : 'var(--brand)', border: 'none', borderRadius: 6, fontSize: 12.5, fontWeight: 600, color: '#fff', cursor: loading ? 'not-allowed' : 'pointer', opacity: loading ? 0.7 : 1 }}>
              {loading ? 'Saving…' : 'Update Penalization Policy'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

// ── Employee Detail / History modal ─────────────────────────────────────────────
function EmployeeDetailModal({ employeeUserId, token, onClose, onChangePolicy, onRemovePolicy }: {
  employeeUserId: string; token: string; onClose(): void;
  onChangePolicy(emp: AllocatableEmployee): void;
  onRemovePolicy(emp: AllocatableEmployee): void;
}) {
  const [detail, setDetail] = useState<EmployeeAllocationDetailResponse | null>(null);
  const [noPolicyReason, setNoPolicyReason] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    penalizationPolicyAllocationApi.getEmployeeDetail(token, employeeUserId)
      .then(d => {
        setDetail(d);
        // ALLOCATION_REQUIRED (Section 21): explain WHY, not just that nothing resolved.
        if (d.resolvedPolicySource === 'ALLOCATION_REQUIRED') {
          const today = new Date().toISOString().slice(0, 10);
          penalizationPolicyAllocationApi.resolveFor(token, employeeUserId, today)
            .then(r => setNoPolicyReason(r.reason))
            .catch(() => { /* the detail view already degrades to "No Penalization Policy" without a reason */ });
        }
      })
      .catch(e => setError(e instanceof Error ? e.message : 'Failed to load employee detail'))
      .finally(() => setLoading(false));
  }, [token, employeeUserId]);

  const currentEntry = detail?.history.find(a => a.status === 'CURRENT') ?? null;
  const canRemove = detail?.resolvedPolicySource === 'ALLOCATION' && currentEntry != null;

  return (
    <div role="dialog" aria-modal="true" style={{ position: 'fixed', inset: 0, zIndex: 220, display: 'flex', alignItems: 'center', justifyContent: 'center', background: 'rgba(0,0,0,.55)', backdropFilter: 'blur(4px)' }}
      onClick={e => { if (e.target === e.currentTarget) onClose(); }}>
      <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, padding: 24, width: 640, maxWidth: '94vw', maxHeight: '84vh', overflowY: 'auto' }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 4 }}>
          <h2 style={{ margin: 0, fontSize: 15, fontWeight: 700 }}>Employee Details</h2>
          <button onClick={onClose} aria-label="Close" style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt-dim)' }}><X size={16} /></button>
        </div>
        {loading ? (
          <div style={{ padding: '24px 0', textAlign: 'center', color: 'var(--txt-mut)', fontSize: 13 }}>Loading…</div>
        ) : error ? (
          <div role="alert" style={{ color: 'var(--risk)', fontSize: 13 }}>{error}</div>
        ) : detail ? (
          <>
            {/* Employee Information */}
            <div style={{ marginTop: 12, marginBottom: 16, padding: 14, background: 'var(--raised)', borderRadius: 8 }}>
              <div style={{ fontSize: 14, color: 'var(--txt)', fontWeight: 700 }}>{detail.fullName}</div>
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 6, marginTop: 8, fontSize: 12, color: 'var(--txt-mut)' }}>
                <div><span style={{ color: 'var(--txt-dim)' }}>Employee Number:</span> {detail.employeeCode}</div>
                <div><span style={{ color: 'var(--txt-dim)' }}>Email:</span> {detail.email}</div>
                <div><span style={{ color: 'var(--txt-dim)' }}>Designation:</span> {detail.designationTitle ?? '—'}</div>
                <div><span style={{ color: 'var(--txt-dim)' }}>Department:</span> {detail.departmentName ?? '—'}</div>
                <div><span style={{ color: 'var(--txt-dim)' }}>Business Unit:</span> {detail.businessUnitName ?? '—'}</div>
                <div><span style={{ color: 'var(--txt-dim)' }}>Location:</span> {detail.locationName ?? '—'}</div>
                <div><span style={{ color: 'var(--txt-dim)' }}>Reporting Manager:</span> {detail.reportingManagerName ?? '—'}</div>
              </div>
            </div>

            {/* Current Penalization Policy */}
            <div style={{ marginBottom: 16 }}>
              <div style={{ fontSize: 11, fontWeight: 700, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.04em', marginBottom: 6 }}>
                Current Penalization Policy
              </div>
              {detail.resolvedPolicyName ? (
                <div style={{ fontSize: 13, color: 'var(--txt)' }}>
                  {detail.resolvedPolicyName} <SourceBadge source={detail.resolvedPolicySource} />
                  {currentEntry && (
                    <div style={{ fontSize: 11.5, color: 'var(--txt-mut)', marginTop: 2 }}>
                      Effective {fmtDate(currentEntry.effectiveFrom)} – {currentEntry.effectiveTo ? fmtDate(currentEntry.effectiveTo) : 'no end date'}
                    </div>
                  )}
                </div>
              ) : (
                <div>
                  <div style={{ fontSize: 13, color: 'var(--txt-mut)', fontStyle: 'italic' }}>
                    No Penalization Policy <SourceBadge source={detail.resolvedPolicySource} />
                  </div>
                  {noPolicyReason && (
                    <div style={{ fontSize: 11.5, color: 'var(--risk)', marginTop: 4 }}>{noPolicyReason}</div>
                  )}
                </div>
              )}
              <div style={{ display: 'flex', gap: 8, marginTop: 10 }}>
                <button
                  onClick={() => onChangePolicy({ employeeUserId: detail.employeeUserId, fullName: detail.fullName, currentAllocation: currentEntry })}
                  style={{ padding: '6px 12px', background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 6, fontSize: 12, fontWeight: 600, cursor: 'pointer' }}>
                  {currentEntry ? 'Change Policy' : 'Add Policy'}
                </button>
                {canRemove && (
                  <button
                    onClick={() => onRemovePolicy({ employeeUserId: detail.employeeUserId, fullName: detail.fullName, currentAllocation: currentEntry })}
                    style={{ padding: '6px 12px', background: 'var(--raised2)', color: 'var(--risk)', border: '1px solid rgba(228,55,61,.3)', borderRadius: 6, fontSize: 12, fontWeight: 600, cursor: 'pointer' }}>
                    Remove Policy
                  </button>
                )}
              </div>
            </div>

            {/* Assignment History */}
            <div style={{ fontSize: 11, fontWeight: 700, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.04em', marginBottom: 6, display: 'flex', alignItems: 'center', gap: 6 }}>
              <History size={13} /> Assignment History
            </div>
            {detail.history.length === 0 ? (
              <div style={{ padding: '20px 0', textAlign: 'center', color: 'var(--txt-mut)', fontSize: 13 }}>
                No allocation history — this employee follows the legacy/default assignment only.
              </div>
            ) : (
              <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12.5 }}>
                <thead>
                  <tr style={{ background: 'var(--raised)' }}>
                    {['Policy', 'Effective From', 'Effective To', 'Status'].map(h => (
                      <th key={h} style={{ padding: '8px 10px', textAlign: 'left', fontSize: 10.5, textTransform: 'uppercase', letterSpacing: '.04em', color: 'var(--txt-dim)', borderBottom: '1px solid var(--line)' }}>{h}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {detail.history.map(a => (
                    <tr key={a.id} style={{ borderBottom: '1px solid var(--line)' }}>
                      <td style={{ padding: '8px 10px', color: 'var(--txt)' }}>{a.penalisationPolicyName}</td>
                      <td style={{ padding: '8px 10px', color: 'var(--txt-mut)' }}>{fmtDate(a.effectiveFrom)}</td>
                      <td style={{ padding: '8px 10px', color: 'var(--txt-mut)' }}>{a.effectiveTo ? fmtDate(a.effectiveTo) : '—'}</td>
                      <td style={{ padding: '8px 10px' }}><StatusPill status={a.status} /></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </>
        ) : null}
      </div>
    </div>
  );
}

// ── Add Employees modal — its own search/filter/multi-select, independent of the main table's filters ──
function AddEmployeesModal({ token, policies, businessUnits, departments, locations, onClose, onAssigned }: {
  token: string;
  policies: PenalisationPolicySummary[];
  businessUnits: BusinessUnitRow[];
  departments: DepartmentRow[];
  locations: LocationRow[];
  onClose(): void;
  onAssigned(result: AllocationBulkResult, assignedEmployees: { employeeUserId: string; fullName: string }[]): Promise<void>;
}) {
  const { showToast } = useToast();
  const [search, setSearch] = useState('');
  const [businessUnitId, setBusinessUnitId] = useState('');
  const [departmentId, setDepartmentId] = useState('');
  const [locationId, setLocationId] = useState('');
  const [page, setPage] = useState(0);
  const [result, setResult] = useState<EmployeeAllocationSearchResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [step, setStep] = useState<'select' | 'assign'>('select');
  const searchDebounce = useRef<ReturnType<typeof setTimeout> | null>(null);

  async function fetchEmployees(nextPage: number) {
    setLoading(true);
    setLoadError('');
    try {
      const res = await penalizationPolicyAllocationApi.searchEmployees(token, {
        businessUnitId: businessUnitId || undefined,
        departmentId: departmentId || undefined,
        locationId: locationId || undefined,
        search: search || undefined,
        page: nextPage, size: ADD_MODAL_PAGE_SIZE,
      });
      setResult(res);
    } catch (e) {
      setResult(null);
      setLoadError(e instanceof Error ? e.message : 'Unable to load employees.');
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { fetchEmployees(0); setPage(0); }, [businessUnitId, departmentId, locationId]);
  useEffect(() => {
    if (searchDebounce.current) clearTimeout(searchDebounce.current);
    searchDebounce.current = setTimeout(() => { setPage(0); fetchEmployees(0); }, 350);
    return () => { if (searchDebounce.current) clearTimeout(searchDebounce.current); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [search]);
  useEffect(() => { fetchEmployees(page); }, [page]);

  function toggle(row: EmployeeAllocationRow) {
    setSelected(prev => {
      const next = new Set(prev);
      if (next.has(row.employeeUserId)) next.delete(row.employeeUserId); else next.add(row.employeeUserId);
      return next;
    });
  }

  async function handleAssign(action: AllocateAction) {
    if (action.type !== 'assign') return;
    const res = await penalizationPolicyAllocationApi.bulkAllocate(token, { employeeUserIds: Array.from(selected), ...action });
    showToast(res.failed.length > 0 ? 'error' : 'success',
      `${res.succeededIds.length} employee(s) assigned${res.failed.length > 0 ? `, ${res.failed.length} failed` : ''}`);
    // Only this page's rows are loaded here — a failure for an employee selected on a different
    // page falls back to showing their raw id, same convention MyTeamPage's failure list uses.
    await onAssigned(res, result?.content ?? []);
  }

  if (step === 'assign') {
    return (
      <AllocateModal
        title={`Assign Policy — ${selected.size} employee${selected.size === 1 ? '' : 's'}`}
        token={token}
        employeeUserIds={Array.from(selected)}
        employees={result?.content}
        policies={policies}
        onClose={onClose}
        onSubmit={handleAssign}
      />
    );
  }

  return (
    <div role="dialog" aria-modal="true" aria-label="Add Employees" style={{ position: 'fixed', inset: 0, zIndex: 220, display: 'flex', alignItems: 'center', justifyContent: 'center', background: 'rgba(0,0,0,.55)', backdropFilter: 'blur(4px)' }}
      onClick={e => { if (e.target === e.currentTarget) onClose(); }}>
      <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, padding: 22, width: 720, maxWidth: '95vw', maxHeight: '88vh', display: 'flex', flexDirection: 'column' }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 12 }}>
          <h2 style={{ margin: 0, fontSize: 15, fontWeight: 700, display: 'flex', alignItems: 'center', gap: 8 }}><UserPlus size={16} /> Add Employees</h2>
          <button onClick={onClose} aria-label="Close" style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt-dim)' }}><X size={16} /></button>
        </div>

        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, marginBottom: 12 }}>
          <div style={{ position: 'relative', flex: '1 1 200px' }}>
            <Search size={12} style={{ position: 'absolute', left: 8, top: '50%', transform: 'translateY(-50%)', color: 'var(--txt-dim)' }} />
            <input type="search" value={search} onChange={e => setSearch(e.target.value)}
              placeholder="Search employee name / number / email"
              style={{ ...inputStyle, width: '100%', paddingLeft: 26, boxSizing: 'border-box' }} />
          </div>
          <select style={inputStyle} value={businessUnitId} onChange={e => setBusinessUnitId(e.target.value)}>
            <option value="">All Business Units</option>
            {businessUnits.filter(b => b.active).map(b => <option key={b.id} value={b.id}>{b.name}</option>)}
          </select>
          <select style={inputStyle} value={departmentId} onChange={e => setDepartmentId(e.target.value)}>
            <option value="">All Departments</option>
            {departments.filter(d => d.active).map(d => <option key={d.id} value={d.id}>{d.name}</option>)}
          </select>
          <select style={inputStyle} value={locationId} onChange={e => setLocationId(e.target.value)}>
            <option value="">All Locations</option>
            {locations.filter(l => l.active).map(l => <option key={l.id} value={l.id}>{l.name}</option>)}
          </select>
        </div>

        <div style={{ flex: 1, overflowY: 'auto', border: '1px solid var(--line)', borderRadius: 8 }}>
          {loading ? (
            <div style={{ padding: 30, textAlign: 'center', color: 'var(--txt-mut)', fontSize: 13 }}>Loading…</div>
          ) : loadError ? (
            <div style={{ padding: 30, textAlign: 'center' }}>
              <div role="alert" style={{ color: 'var(--risk)', fontSize: 13, marginBottom: 10 }}>Unable to load employees. {loadError}</div>
              <button onClick={() => fetchEmployees(page)} style={{ padding: '6px 14px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, fontSize: 12.5, color: 'var(--txt)', cursor: 'pointer' }}>Retry</button>
            </div>
          ) : !result || result.content.length === 0 ? (
            <div style={{ padding: 30, textAlign: 'center', color: 'var(--txt-mut)', fontSize: 13 }}>No employees match these filters.</div>
          ) : (
            <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12.5 }}>
              <tbody>
                {result.content.map(row => (
                  <tr key={row.employeeUserId} onClick={() => toggle(row)} style={{ borderBottom: '1px solid var(--line)', cursor: 'pointer', background: selected.has(row.employeeUserId) ? 'rgba(176,17,22,.06)' : 'transparent' }}>
                    <td style={{ padding: '8px 12px', width: 28 }}>
                      <input type="checkbox" checked={selected.has(row.employeeUserId)} onChange={() => toggle(row)} onClick={e => e.stopPropagation()} style={{ width: 14, height: 14 }} />
                    </td>
                    <td style={{ padding: '8px 10px' }}>
                      <div style={{ color: 'var(--txt)', fontWeight: 500 }}>{row.fullName}</div>
                      <div style={{ color: 'var(--txt-mut)', fontSize: 11 }}>{row.employeeCode} · {row.email}</div>
                    </td>
                    <td style={{ padding: '8px 10px', color: 'var(--txt-mut)' }}>{row.departmentName ?? '—'}</td>
                    <td style={{ padding: '8px 10px', color: 'var(--txt-mut)' }}>{row.resolvedPolicyName ?? 'No Penalization Policy'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>

        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginTop: 10 }}>
          <span style={{ fontSize: 12, color: 'var(--txt-mut)' }}>{selected.size} selected</span>
          {result && (
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 12, color: 'var(--txt-mut)' }}>
              <span>Page {result.page + 1} of {Math.max(1, result.totalPages)}</span>
              <button onClick={() => setPage(p => Math.max(0, p - 1))} disabled={page === 0}
                style={{ display: 'flex', padding: 4, background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, cursor: page === 0 ? 'not-allowed' : 'pointer', opacity: page === 0 ? 0.5 : 1 }}><ChevronLeft size={13} /></button>
              <button onClick={() => setPage(p => (result.page + 1 < result.totalPages ? p + 1 : p))} disabled={result.page + 1 >= result.totalPages}
                style={{ display: 'flex', padding: 4, background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, cursor: result.page + 1 >= result.totalPages ? 'not-allowed' : 'pointer', opacity: result.page + 1 >= result.totalPages ? 0.5 : 1 }}><ChevronRight size={13} /></button>
            </div>
          )}
        </div>
        <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end', marginTop: 14 }}>
          <button type="button" onClick={onClose} style={{ padding: '7px 14px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, fontSize: 12.5, color: 'var(--txt-mut)', cursor: 'pointer' }}>Cancel</button>
          <button type="button" disabled={selected.size === 0} onClick={() => setStep('assign')}
            style={{ padding: '7px 16px', background: 'var(--brand)', border: 'none', borderRadius: 6, fontSize: 12.5, fontWeight: 600, color: '#fff', cursor: selected.size === 0 ? 'not-allowed' : 'pointer', opacity: selected.size === 0 ? 0.6 : 1 }}>
            Choose Policy for {selected.size || ''} Selected
          </button>
        </div>
      </div>
    </div>
  );
}

// ── Main section ─────────────────────────────────────────────────────────────────
export default function PenalizationPolicyAllocationSection({ token, initialPolicyFilter, onInitialPolicyFilterConsumed }: {
  token: string;
  /** Pre-selects the Penalization Policy filter once (e.g. navigated here from the Policy List's employee-count link). */
  initialPolicyFilter?: string | null;
  onInitialPolicyFilterConsumed?: () => void;
}) {
  const { showToast } = useToast();

  const [businessUnits, setBusinessUnits] = useState<BusinessUnitRow[]>([]);
  const [departments, setDepartments] = useState<DepartmentRow[]>([]);
  const [locations, setLocations] = useState<LocationRow[]>([]);
  const [policies, setPolicies] = useState<PenalisationPolicySummary[]>([]);

  const [search, setSearch] = useState('');
  const [businessUnitId, setBusinessUnitId] = useState('');
  const [departmentId, setDepartmentId] = useState('');
  const [locationId, setLocationId] = useState('');
  const [penalisationPolicyId, setPenalisationPolicyId] = useState('');

  const [result, setResult] = useState<EmployeeAllocationSearchResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const [selected, setSelected] = useState<Set<string>>(new Set());

  // Each lookup dropdown fails/succeeds independently — one bad lookup must not blank the other
  // three, and must never be silently swallowed into an empty "All X" dropdown.
  const [lookupErrors, setLookupErrors] = useState<{ businessUnits?: string; departments?: string; locations?: string; policies?: string }>({});

  const [allocateTarget, setAllocateTarget] = useState<{ employeeIds: string[]; title: string; initial?: AllocationDto | null; allowRemove?: boolean } | null>(null);
  const [detailEmployeeId, setDetailEmployeeId] = useState<string | null>(null);
  const [confirmState, setConfirmState] = useState<{ title: string; body: string; onConfirm: () => Promise<void> } | null>(null);
  const [addEmployeesOpen, setAddEmployeesOpen] = useState(false);
  const [lastResult, setLastResult] = useState<AllocationActionResult | null>(null);

  const searchDebounce = useRef<ReturnType<typeof setTimeout> | null>(null);

  // Apply the "navigated here from Policy List" filter exactly once, then let the parent clear it.
  useEffect(() => {
    if (initialPolicyFilter) {
      setPenalisationPolicyId(initialPolicyFilter);
      onInitialPolicyFilterConsumed?.();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [initialPolicyFilter]);

  // Promise.allSettled, not Promise.all — one lookup failing (e.g. a transient 5xx) must not
  // wipe out the other three dropdowns, and must never be masked as "no data" (an empty,
  // silently-succeeded-looking dropdown). Each lookup keeps whatever it last successfully loaded
  // and reports its own failure independently.
  async function fetchLookups() {
    const [bu, deps, locs, pols] = await Promise.allSettled([
      orgApi.listBusinessUnits(token),
      orgApi.listDepartments(token),
      orgApi.listLocations(token),
      penalisationPoliciesApi.list(token),
    ]);

    const errors: typeof lookupErrors = {};
    if (bu.status === 'fulfilled') setBusinessUnits(bu.value);
    else errors.businessUnits = bu.reason instanceof Error ? bu.reason.message : 'Couldn’t load Business Units';
    if (deps.status === 'fulfilled') setDepartments(deps.value);
    else errors.departments = deps.reason instanceof Error ? deps.reason.message : 'Couldn’t load Departments';
    if (locs.status === 'fulfilled') setLocations(locs.value);
    else errors.locations = locs.reason instanceof Error ? locs.reason.message : 'Couldn’t load Locations';
    if (pols.status === 'fulfilled') setPolicies(pols.value);
    else errors.policies = pols.reason instanceof Error ? pols.reason.message : 'Couldn’t load Penalization Policies';
    setLookupErrors(errors);

    // Dynamic dropdowns: a filter selection that no longer resolves to a live, active
    // option (renamed away, deactivated, or removed) is cleared automatically rather than
    // silently continuing to filter against a stale id. Only clears against a lookup that
    // actually loaded successfully — a failed lookup's stale-but-still-displayed options are
    // left alone rather than being wiped by an incomplete fetch.
    if (bu.status === 'fulfilled' && businessUnitId && !bu.value.some(x => x.id === businessUnitId && x.active)) setBusinessUnitId('');
    if (deps.status === 'fulfilled' && departmentId && !deps.value.some(x => x.id === departmentId && x.active)) setDepartmentId('');
    if (locs.status === 'fulfilled' && locationId && !locs.value.some(x => x.id === locationId && x.active)) setLocationId('');
    if (pols.status === 'fulfilled' && penalisationPolicyId && !pols.value.some(x => x.id === penalisationPolicyId)) setPenalisationPolicyId('');
  }

  async function fetchEmployees() {
    setLoading(true);
    setLoadError('');
    try {
      const res = await penalizationPolicyAllocationApi.searchEmployees(token, {
        businessUnitId: businessUnitId || undefined,
        departmentId: departmentId || undefined,
        locationId: locationId || undefined,
        penalisationPolicyId: penalisationPolicyId || undefined,
        search: search || undefined,
        all: true,
      });
      setResult(res);
      setSelected(new Set());
    } catch (e) {
      // A failed fetch must never be rendered as "zero employees" — clear any stale result so
      // the error state (not the empty state) is what actually shows.
      setResult(null);
      setLoadError(e instanceof Error ? e.message : 'Unable to load employees.');
    } finally {
      setLoading(false);
    }
  }

  async function refreshAll() {
    await Promise.all([fetchLookups(), fetchEmployees()]);
  }

  useEffect(() => { if (token) fetchLookups(); }, [token]);
  useEffect(() => { if (token) fetchEmployees(); }, [token, businessUnitId, departmentId, locationId, penalisationPolicyId]);

  // Debounced search-text filtering — avoids firing a request on every keystroke.
  useEffect(() => {
    if (searchDebounce.current) clearTimeout(searchDebounce.current);
    searchDebounce.current = setTimeout(() => { fetchEmployees(); }, 350);
    return () => { if (searchDebounce.current) clearTimeout(searchDebounce.current); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [search]);

  function toggleSelected(id: string) {
    setSelected(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  }

  function toggleSelectAll() {
    if (!result) return;
    const allIds = result.content.map(r => r.employeeUserId);
    const allSelected = allIds.every(id => selected.has(id));
    setSelected(allSelected ? new Set() : new Set(allIds));
  }

  async function handleAllocateSubmit(action: AllocateAction) {
    if (!allocateTarget) return;
    const employees = result?.content ?? [];
    if (action.type === 'remove') {
      const res = await penalizationPolicyAllocationApi.bulkRemove(token, allocateTarget.employeeIds);
      showToast(res.failed.length > 0 ? 'error' : 'success',
        `${res.succeededIds.length} removed${res.failed.length > 0 ? `, ${res.failed.length} had nothing to remove` : ''}`);
      setLastResult(toActionResult(res, employees));
    } else if (allocateTarget.employeeIds.length === 1 && allocateTarget.initial) {
      await penalizationPolicyAllocationApi.update(token, allocateTarget.initial.id, action);
      showToast('success', 'Allocation updated');
    } else if (allocateTarget.employeeIds.length === 1) {
      await penalizationPolicyAllocationApi.allocate(token, { employeeUserId: allocateTarget.employeeIds[0], ...action });
      showToast('success', 'Policy allocated');
    } else {
      const res = await penalizationPolicyAllocationApi.bulkAllocate(token, { employeeUserIds: allocateTarget.employeeIds, ...action });
      showToast(res.failed.length > 0 ? 'error' : 'success',
        `${res.succeededIds.length} allocated${res.failed.length > 0 ? `, ${res.failed.length} failed` : ''}`);
      setLastResult(toActionResult(res, employees));
    }
    await refreshAll();
  }

  function openAssign(emp: AllocatableEmployee) {
    setAllocateTarget({ employeeIds: [emp.employeeUserId], title: `Assign Policy — ${emp.fullName}` });
  }

  function openEdit(emp: AllocatableEmployee) {
    if (!emp.currentAllocation) return;
    setAllocateTarget({ employeeIds: [emp.employeeUserId], title: `Edit Allocation — ${emp.fullName}`, initial: emp.currentAllocation });
  }

  function openBulkAssign() {
    setAllocateTarget({ employeeIds: Array.from(selected), title: `Update Penalization Policy — ${selected.size} employees`, allowRemove: true });
  }

  function triggerRemove(emp: AllocatableEmployee) {
    if (!emp.currentAllocation) return;
    setConfirmState({
      title: 'Remove Penalization Policy',
      body: `Are you sure you want to remove the penalization policy from ${emp.fullName}?`,
      onConfirm: async () => {
        await penalizationPolicyAllocationApi.remove(token, emp.currentAllocation!.id);
        showToast('success', 'Penalization policy removed');
        setDetailEmployeeId(null);
        await refreshAll();
      },
    });
  }

  function triggerBulkRemove() {
    setConfirmState({
      title: 'Remove Penalization Policy',
      body: `${selected.size} employee${selected.size === 1 ? '' : 's'} will lose their explicit allocation and fall back to the legacy/default penalization policy.`,
      onConfirm: async () => {
        const res = await penalizationPolicyAllocationApi.bulkRemove(token, Array.from(selected));
        showToast(res.failed.length > 0 ? 'error' : 'success',
          `${res.succeededIds.length} removed${res.failed.length > 0 ? `, ${res.failed.length} had nothing to remove` : ''}`);
        setLastResult(toActionResult(res, result?.content ?? []));
        await refreshAll();
      },
    });
  }

  function kebabItems(row: EmployeeAllocationRow): KebabItem[] {
    const items: KebabItem[] = [];
    if (row.resolvedPolicySource === 'ALLOCATION' && row.currentAllocation) {
      items.push({ label: 'Edit', onClick: () => openEdit(row) });
    } else {
      items.push({ label: 'Assign Policy', onClick: () => openAssign(row) });
    }
    if (row.resolvedPolicySource === 'ALLOCATION' && row.currentAllocation) {
      items.push({ label: 'Remove', danger: true, onClick: () => triggerRemove(row) });
    }
    return items;
  }

  const hasFilters = search || businessUnitId || departmentId || locationId || penalisationPolicyId;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      {/* Header actions */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <h2 style={{ margin: 0, fontSize: 15, fontWeight: 700, color: 'var(--txt)' }}>Penalization Policy Allocation</h2>
        <div style={{ display: 'flex', gap: 8 }}>
          <button onClick={() => setAddEmployeesOpen(true)} style={{ display: 'flex', alignItems: 'center', gap: 6, padding: '7px 14px', background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 6, fontSize: 12.5, fontWeight: 600, cursor: 'pointer' }}>
            <UserPlus size={14} /> Add Employees
          </button>
          <button onClick={openBulkAssign} disabled={selected.size === 0}
            style={{ padding: '7px 14px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, fontSize: 12.5, fontWeight: 600, color: selected.size === 0 ? 'var(--txt-dim)' : 'var(--txt)', cursor: selected.size === 0 ? 'not-allowed' : 'pointer', opacity: selected.size === 0 ? 0.6 : 1 }}>
            Update Penalization Policy
          </button>
        </div>
      </div>

      {/* Filters */}
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, alignItems: 'center' }}>
        <div style={{ position: 'relative', flex: '1 1 220px', minWidth: 200 }}>
          <Search size={12} style={{ position: 'absolute', left: 8, top: '50%', transform: 'translateY(-50%)', color: 'var(--txt-dim)' }} />
          <input
            type="search" value={search} onChange={e => setSearch(e.target.value)}
            placeholder="Search employee name / number / email"
            style={{ ...inputStyle, width: '100%', paddingLeft: 26, boxSizing: 'border-box' }}
          />
        </div>
        <div>
          <select style={inputStyle} value={businessUnitId} onChange={e => setBusinessUnitId(e.target.value)}>
            <option value="">All Business Units</option>
            {businessUnits.filter(b => b.active).map(b => <option key={b.id} value={b.id}>{b.name}</option>)}
          </select>
          {lookupErrors.businessUnits && <div style={{ fontSize: 10.5, color: 'var(--risk)', marginTop: 3 }}>Couldn't load Business Units</div>}
        </div>
        <div>
          <select style={inputStyle} value={departmentId} onChange={e => setDepartmentId(e.target.value)}>
            <option value="">All Departments</option>
            {departments.filter(d => d.active).map(d => <option key={d.id} value={d.id}>{d.name}</option>)}
          </select>
          {lookupErrors.departments && <div style={{ fontSize: 10.5, color: 'var(--risk)', marginTop: 3 }}>Couldn't load Departments</div>}
        </div>
        <div>
          <select style={inputStyle} value={locationId} onChange={e => setLocationId(e.target.value)}>
            <option value="">All Locations</option>
            {locations.filter(l => l.active).map(l => <option key={l.id} value={l.id}>{l.name}</option>)}
          </select>
          {lookupErrors.locations && <div style={{ fontSize: 10.5, color: 'var(--risk)', marginTop: 3 }}>Couldn't load Locations</div>}
        </div>
        <div>
          <select style={inputStyle} value={penalisationPolicyId} onChange={e => setPenalisationPolicyId(e.target.value)}>
            <option value="">All Penalization Policies</option>
            {policies.map(p => <option key={p.id} value={p.id}>{p.name}</option>)}
          </select>
          {lookupErrors.policies && <div style={{ fontSize: 10.5, color: 'var(--risk)', marginTop: 3 }}>Couldn't load Penalization Policies</div>}
        </div>
        {hasFilters && (
          <button onClick={() => { setSearch(''); setBusinessUnitId(''); setDepartmentId(''); setLocationId(''); setPenalisationPolicyId(''); }}
            style={{ background: 'none', border: '1px solid var(--line2)', borderRadius: 6, padding: '6px 10px', fontSize: 12, color: 'var(--txt-mut)', cursor: 'pointer' }}>
            Clear Filters
          </button>
        )}
      </div>

      {Object.keys(lookupErrors).length > 0 && (
        <div role="alert" style={{ display: 'flex', alignItems: 'center', gap: 10, background: 'rgba(228,55,61,.1)', border: '1px solid rgba(228,55,61,.3)', borderRadius: 8, padding: '10px 14px', color: 'var(--risk)', fontSize: 13 }}>
          <span>Some filter options failed to load — the affected dropdown(s) are marked above.</span>
          <button onClick={() => fetchLookups()} style={{ marginLeft: 'auto', padding: '5px 12px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, fontSize: 12, fontWeight: 600, color: 'var(--txt)', cursor: 'pointer' }}>Retry</button>
        </div>
      )}

      {/* Bulk action bar */}
      {selected.size > 0 && (
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, background: 'var(--raised)', border: '1px solid var(--line)', borderRadius: 8, padding: '8px 14px' }}>
          <Users size={14} style={{ color: 'var(--txt-mut)' }} />
          <span style={{ fontSize: 12.5, color: 'var(--txt)', fontWeight: 600 }}>{selected.size} selected</span>
          <button onClick={openBulkAssign} style={{ padding: '5px 12px', background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 6, fontSize: 12, fontWeight: 600, cursor: 'pointer' }}>
            Update Penalization Policy
          </button>
          <button onClick={triggerBulkRemove} style={{ padding: '5px 12px', background: 'var(--raised2)', color: 'var(--risk)', border: '1px solid rgba(228,55,61,.3)', borderRadius: 6, fontSize: 12, fontWeight: 600, cursor: 'pointer' }}>
            Remove Allocation
          </button>
          <button onClick={() => setSelected(new Set())} style={{ marginLeft: 'auto', background: 'none', border: 'none', color: 'var(--txt-dim)', fontSize: 12, cursor: 'pointer' }}>
            Clear selection
          </button>
        </div>
      )}

      {loadError && (
        <div role="alert" style={{ display: 'flex', alignItems: 'center', gap: 10, background: 'rgba(228,55,61,.1)', border: '1px solid rgba(228,55,61,.3)', borderRadius: 8, padding: '10px 14px', color: 'var(--risk)', fontSize: 13 }}>
          <span>Unable to load allocation data. {loadError}</span>
          <button onClick={() => fetchEmployees()} style={{ marginLeft: 'auto', padding: '5px 12px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, fontSize: 12, fontWeight: 600, color: 'var(--txt)', cursor: 'pointer' }}>Retry</button>
        </div>
      )}

      {/* Gap-015: per-employee bulk failure detail, matching Team Assignments' own result banner. */}
      {lastResult && (
        <div style={{ padding: '10px 18px', border: '1px solid var(--line)', borderRadius: 8, background: lastResult.failures.length ? 'rgba(228,55,61,.08)' : 'rgba(47,182,124,.08)' }}>
          <div style={{ fontSize: 12, fontWeight: 600, color: 'var(--txt)', marginBottom: lastResult.failures.length ? 6 : 0 }}>
            Bulk update — {lastResult.succeeded} succeeded{lastResult.failures.length ? `, ${lastResult.failures.length} failed` : ''}
          </div>
          {lastResult.failures.length > 0 && (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 3 }}>
              {lastResult.failures.map((f, i) => (
                <div key={i} style={{ fontSize: 11.5, color: 'var(--risk)' }}>{f.label}: {f.reason}</div>
              ))}
            </div>
          )}
        </div>
      )}

      {/* Table */}
      <div style={{ border: '1px solid var(--line)', borderRadius: 10, overflow: 'hidden' }}>
        <div style={{ overflowX: 'auto' }}>
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 12.5 }}>
            <thead>
              <tr style={{ background: 'var(--raised)' }}>
                <th style={{ padding: '10px 12px', width: 32 }}>
                  <input type="checkbox" checked={!!result && result.content.length > 0 && result.content.every(r => selected.has(r.employeeUserId))}
                    onChange={toggleSelectAll} style={{ width: 14, height: 14 }} />
                </th>
                {['Employee', 'Employee Number', 'Department', 'Location', 'Business Unit', 'Reporting Manager', 'Penalization Policy', 'Effective From', 'Effective Up To', 'Status', ''].map(h => (
                  <th key={h} style={{ padding: '10px 14px', textAlign: 'left', fontWeight: 600, color: 'var(--txt-dim)', borderBottom: '1px solid var(--line)', fontSize: 11, letterSpacing: '.04em', textTransform: 'uppercase', whiteSpace: 'nowrap' }}>
                    {h}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {loading ? (
                <tr><td colSpan={11} style={{ padding: '40px 0', textAlign: 'center', color: 'var(--txt-mut)' }}>Loading…</td></tr>
              ) : loadError ? (
                <tr><td colSpan={11} style={{ padding: '40px 0', textAlign: 'center' }}>
                  <div style={{ color: 'var(--risk)', fontSize: 13, marginBottom: 10 }}>Unable to load allocation data.</div>
                  <button onClick={() => fetchEmployees()} style={{ padding: '6px 14px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, fontSize: 12.5, color: 'var(--txt)', cursor: 'pointer' }}>Retry</button>
                </td></tr>
              ) : !result || result.content.length === 0 ? (
                <tr><td colSpan={11} style={{ padding: '40px 0', textAlign: 'center', color: 'var(--txt-mut)' }}>No employees match the current filters.</td></tr>
              ) : (
                result.content.map(row => (
                  <tr key={row.employeeUserId} onClick={() => setDetailEmployeeId(row.employeeUserId)}
                    style={{ borderBottom: '1px solid var(--line)', opacity: row.active ? 1 : 0.6, cursor: 'pointer' }}>
                    <td style={{ padding: '10px 12px' }} onClick={e => e.stopPropagation()}>
                      <input type="checkbox" checked={selected.has(row.employeeUserId)} onChange={() => toggleSelected(row.employeeUserId)} style={{ width: 14, height: 14 }} />
                    </td>
                    <td style={{ padding: '10px 14px' }}>
                      <div style={{ color: 'var(--txt)', fontWeight: 500 }}>{row.fullName}</div>
                      <div style={{ color: 'var(--txt-mut)', fontSize: 11 }}>{row.designationTitle ?? '—'}</div>
                    </td>
                    <td style={{ padding: '10px 14px', color: 'var(--txt-mut)', fontFamily: '"JetBrains Mono", monospace', fontSize: 11.5 }}>{row.employeeCode}</td>
                    <td style={{ padding: '10px 14px', color: 'var(--txt-mut)' }}>{row.departmentName ?? '—'}</td>
                    <td style={{ padding: '10px 14px', color: 'var(--txt-mut)' }}>{row.locationName ?? '—'}</td>
                    <td style={{ padding: '10px 14px', color: 'var(--txt-mut)' }}>{row.businessUnitName ?? '—'}</td>
                    <td style={{ padding: '10px 14px', color: 'var(--txt-mut)' }}>{row.reportingManagerName ?? '—'}</td>
                    <td style={{ padding: '10px 14px' }}>
                      {row.resolvedPolicyName ? (
                        <div style={{ color: 'var(--txt)' }}>{row.resolvedPolicyName} <SourceBadge source={row.resolvedPolicySource} /></div>
                      ) : (
                        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                          <span style={{ color: 'var(--txt-mut)', fontStyle: 'italic' }}>No Penalization Policy</span>
                          <button onClick={e => { e.stopPropagation(); openAssign(row); }}
                            style={{ background: 'none', border: 'none', color: 'var(--brand-bright)', fontSize: 11.5, fontWeight: 600, cursor: 'pointer', textDecoration: 'underline' }}>
                            Assign Policy
                          </button>
                        </div>
                      )}
                      {row.upcomingAllocation && (
                        <div style={{ color: 'var(--txt-dim)', fontSize: 11 }}>
                          → {row.upcomingAllocation.penalisationPolicyName} from {fmtDate(row.upcomingAllocation.effectiveFrom)}
                        </div>
                      )}
                    </td>
                    <td style={{ padding: '10px 14px', color: 'var(--txt-mut)' }}>{row.currentAllocation ? fmtDate(row.currentAllocation.effectiveFrom) : '—'}</td>
                    <td style={{ padding: '10px 14px', color: 'var(--txt-mut)' }}>{row.currentAllocation?.effectiveTo ? fmtDate(row.currentAllocation.effectiveTo) : row.currentAllocation ? 'No End Date' : '—'}</td>
                    <td style={{ padding: '10px 14px' }}>{row.currentAllocation ? <StatusPill status={row.currentAllocation.status} /> : '—'}</td>
                    <td style={{ padding: '10px 14px', textAlign: 'right' }} onClick={e => e.stopPropagation()}>
                      <KebabMenu items={kebabItems(row)} />
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </div>

      {/* All matching employees are shown above — no pagination on this table. */}
      {result && result.totalElements > 0 && (
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'flex-end', fontSize: 12, color: 'var(--txt-mut)' }}>
          <span>{result.totalElements} employee{result.totalElements === 1 ? '' : 's'}</span>
        </div>
      )}

      {allocateTarget && (
        <AllocateModal
          title={allocateTarget.title}
          token={token}
          employeeUserIds={allocateTarget.employeeIds}
          employees={result?.content}
          policies={policies}
          initial={allocateTarget.initial}
          allowRemoveOption={allocateTarget.allowRemove}
          onClose={() => setAllocateTarget(null)}
          onSubmit={handleAllocateSubmit}
        />
      )}
      {detailEmployeeId && (
        <EmployeeDetailModal
          employeeUserId={detailEmployeeId}
          token={token}
          onClose={() => setDetailEmployeeId(null)}
          onChangePolicy={emp => { setDetailEmployeeId(null); if (emp.currentAllocation) openEdit(emp); else openAssign(emp); }}
          onRemovePolicy={emp => triggerRemove(emp)}
        />
      )}
      {confirmState && (
        <ConfirmModal
          title={confirmState.title}
          body={confirmState.body}
          confirmLabel="Remove"
          danger
          onConfirm={confirmState.onConfirm}
          onClose={() => setConfirmState(null)}
        />
      )}
      {addEmployeesOpen && (
        <AddEmployeesModal
          token={token}
          policies={policies}
          businessUnits={businessUnits}
          departments={departments}
          locations={locations}
          onClose={() => setAddEmployeesOpen(false)}
          onAssigned={async (res, assignedEmployees) => {
            setAddEmployeesOpen(false);
            setLastResult(toActionResult(res, assignedEmployees));
            await refreshAll();
          }}
        />
      )}
    </div>
  );
}
