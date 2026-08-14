import { useEffect, useState } from 'react';
import { Eye, Pencil, X } from 'lucide-react';
import { useToast } from '../../context/ToastContext';
import { leaveApi, type LeaveType } from '../../api/leave';
import {
  getCurrentPolicy, savePolicy,
  type PenalizationPolicy, type PenalizationPolicyRequest, type WorkHoursTier, type LateHoursTier,
} from '../../api/penalizationPolicy';

type SectionKey = 'basicInfo' | 'noAttendance' | 'lateArrival' | 'workHoursShortage' | 'missingLogs';

const SECTION_LABELS: Record<SectionKey, string> = {
  basicInfo: 'Basic Information',
  noAttendance: 'No Attendance',
  lateArrival: 'Late Arrival',
  workHoursShortage: 'Work Hours Shortage',
  missingLogs: 'Missing Logs',
};

const SECTIONS: SectionKey[] = ['basicInfo', 'noAttendance', 'lateArrival', 'workHoursShortage', 'missingLogs'];
// Sections with their own Enabled/Disabled toggle — Basic Information applies to the whole
// document (deduction method, buffer period, notice period), so it has no enabled flag of its own.
const TOGGLEABLE_SECTIONS: SectionKey[] = ['noAttendance', 'lateArrival', 'workHoursShortage', 'missingLogs'];

// ── Shared style tokens — matching OrgSetupPage's inline-style convention ──
const inputStyle: React.CSSProperties = {
  background: 'var(--raised)', border: '1px solid var(--line2)',
  borderRadius: 6, padding: '7px 9px', fontSize: 13, color: 'var(--txt)',
  outline: 'none', width: '100%', boxSizing: 'border-box',
};
const labelText: React.CSSProperties = { fontSize: 12, fontWeight: 600, color: 'var(--txt-mut)', display: 'block', marginBottom: 5 };
const checkboxRow: React.CSSProperties = { display: 'flex', alignItems: 'center', gap: 8, fontSize: 13, color: 'var(--txt)', cursor: 'pointer' };

function EnabledBadge({ enabled }: { enabled: boolean }) {
  return (
    <span style={{
      display: 'inline-flex', alignItems: 'center', gap: 4,
      padding: '2px 8px', borderRadius: 20, fontSize: 11, fontWeight: 600,
      background: enabled ? 'rgba(47,182,124,.15)' : 'rgba(107,114,128,.15)',
      color: enabled ? 'var(--ok)' : 'var(--txt-dim)',
    }}>
      {enabled ? 'Enabled' : 'Disabled'}
    </span>
  );
}

function summarize(policy: PenalizationPolicy | null, key: SectionKey): string {
  if (!policy) return 'Not configured';
  if (key === 'basicInfo') {
    const b = policy.basicInfo;
    const method = b.deductionMethod === 'PAID_LEAVE'
      ? `Paid Leave (${b.leavePriorityOrder.join(' → ') || 'no leave types configured'})`
      : 'Loss of Pay';
    const buffer = b.bufferPeriodDays != null ? `${b.bufferPeriodDays} day(s) buffer` : 'No buffer period';
    const notice = b.noticePeriodForcesLopEnabled ? 'notice period forces LoP' : 'no notice-period override';
    return `${method} · ${buffer} · ${notice}`;
  }
  if (key === 'noAttendance') {
    const c = policy.noAttendance;
    if (!c.enabled) return 'Disabled';
    if (c.deductionDays == null) return 'Not configured';
    const adjoining = [
      c.adjoiningHolidayEnabled ? 'adjoining-holiday' : null,
      c.adjoiningWeekoffEnabled ? 'adjoining-week-off' : null,
    ].filter(Boolean).join(' + ');
    return `${c.deductionDays} day(s) leave for every no-attendance day${adjoining ? ` · ${adjoining} rule active` : ''}`;
  }
  if (key === 'lateArrival') {
    const c = policy.lateArrival;
    if (!c.enabled) return 'Disabled';
    if (c.gracePeriodMinutes == null) return 'Not configured';
    if (c.basis === 'TOTAL_HOURS') {
      return `Total hours basis · ${c.allowedHours ?? '—'}h allowed/${c.exemptPeriod.toLowerCase()} · ${c.lateHoursTiers.length} tier(s)`;
    }
    return `${c.gracePeriodMinutes} min grace · ${c.deductionDays ?? '—'} day(s) per ${c.deductionPerShifts ?? '—'} shift(s) after ${c.exemptCount ?? 0}/${c.exemptPeriod.toLowerCase()}`;
  }
  if (key === 'workHoursShortage') {
    const c = policy.workHoursShortage;
    if (!c.enabled) return 'Disabled';
    if (c.tiers.length === 0) return 'Not configured';
    return c.tiers.map(t => `<${t.thresholdPercent}% → ${t.deductionDays}d`).join(', ');
  }
  const c = policy.missingLogs;
  if (!c.enabled) return 'Disabled';
  if (c.exemptDays == null) return 'Not configured';
  return `${c.deductionDays ?? '—'} day(s) per ${c.deductionPerShifts ?? '—'} shift(s) after ${c.exemptDays}/${c.exemptPeriod.toLowerCase()}`;
}

function fmtDate(iso: string | null | undefined): string {
  if (!iso) return '—';
  return new Date(iso).toLocaleDateString(undefined, { day: '2-digit', month: 'short', year: 'numeric' });
}

// ── Modal chrome — same fixed-overlay pattern as OrgSetupPage's ConfirmModal/AddEditModal ──
function ModalShell({ title, onClose, width = 480, children }: { title: string; onClose: () => void; width?: number; children: React.ReactNode }) {
  return (
    <div role="dialog" aria-modal="true" aria-label={title} style={{
      position: 'fixed', inset: 0, zIndex: 200,
      display: 'flex', alignItems: 'center', justifyContent: 'center',
      background: 'rgba(0,0,0,.55)', backdropFilter: 'blur(4px)',
    }} onClick={e => { if (e.target === e.currentTarget) onClose(); }}>
      <div style={{
        background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12,
        padding: '22px 26px', width, maxWidth: '95vw', maxHeight: '85vh', overflowY: 'auto',
        boxShadow: '0 24px 48px rgba(0,0,0,.4)',
      }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 16 }}>
          <h2 style={{ margin: 0, fontSize: 15, fontFamily: '"Space Grotesk", sans-serif', fontWeight: 700, color: 'var(--txt)' }}>{title}</h2>
          <button onClick={onClose} aria-label="Close" style={{ background: 'transparent', border: 'none', cursor: 'pointer', color: 'var(--txt-dim)', padding: 4 }}>
            <X size={16} />
          </button>
        </div>
        {children}
      </div>
    </div>
  );
}

function DetailRow({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12, padding: '7px 0', borderBottom: '1px solid var(--line)', fontSize: 13 }}>
      <span style={{ color: 'var(--txt-mut)' }}>{label}</span>
      <span style={{ color: 'var(--txt)', fontWeight: 500, textAlign: 'right' }}>{value}</span>
    </div>
  );
}

// ── View (read-only) ──
function ViewModal({ policy, section, onClose }: { policy: PenalizationPolicy; section: SectionKey; onClose: () => void }) {
  return (
    <ModalShell title={`${SECTION_LABELS[section]} — Configuration`} onClose={onClose}>
      {TOGGLEABLE_SECTIONS.includes(section) && (
        <div style={{ marginBottom: 12 }}>
          <EnabledBadge enabled={
            section === 'noAttendance' ? policy.noAttendance.enabled :
            section === 'lateArrival' ? policy.lateArrival.enabled :
            section === 'workHoursShortage' ? policy.workHoursShortage.enabled :
            policy.missingLogs.enabled
          } />
        </div>
      )}
      <DetailRow label="Policy version" value={`V${policy.version}`} />
      <DetailRow label="Effective from" value={fmtDate(policy.effectiveFrom)} />
      <DetailRow label="Effective to" value={policy.effectiveTo ? fmtDate(policy.effectiveTo) : 'Current'} />

      {section === 'basicInfo' && (
        <>
          <DetailRow label="Deduction method" value={policy.basicInfo.deductionMethod === 'PAID_LEAVE' ? 'Paid Leave' : 'Loss of Pay'} />
          {policy.basicInfo.deductionMethod === 'PAID_LEAVE' && (
            <DetailRow label="Leave priority order" value={policy.basicInfo.leavePriorityOrder.join(' → ') || 'Not configured'} />
          )}
          <DetailRow label="Buffer period" value={policy.basicInfo.bufferPeriodDays != null ? `${policy.basicInfo.bufferPeriodDays} day(s)` : 'None'} />
          <DetailRow label="Notice period forces LoP" value={policy.basicInfo.noticePeriodForcesLopEnabled ? 'Yes' : 'No'} />
        </>
      )}
      {section === 'noAttendance' && (
        <>
          <DetailRow label="Deduction" value={policy.noAttendance.deductionDays != null ? `${policy.noAttendance.deductionDays} day(s) per no-attendance day` : 'Not configured'} />
          <DetailRow label="No-show rule" value={policy.noAttendance.noShowEnabled ? `Below ${policy.noAttendance.noShowThresholdHours ?? '—'} effective hours` : 'Disabled'} />
          <DetailRow label="Adjoining holiday" value={policy.noAttendance.adjoiningHolidayEnabled
            ? `${policy.noAttendance.adjoiningHolidayCondition ?? 'ANY'} · threshold ${policy.noAttendance.adjoiningHolidayCalendarDayThreshold ?? 1} day(s) · ${policy.noAttendance.adjoiningHolidayIgnoreHalfDayLeave ? 'ignores' : 'counts'} half-day leave`
            : 'Disabled'} />
          <DetailRow label="Adjoining week-off" value={policy.noAttendance.adjoiningWeekoffEnabled
            ? `${policy.noAttendance.adjoiningWeekoffCondition ?? 'ANY'} · threshold ${policy.noAttendance.adjoiningWeekoffCalendarDayThreshold ?? 1} day(s) · ${policy.noAttendance.adjoiningWeekoffIgnoreHalfDayLeave ? 'ignores' : 'counts'} half-day leave`
            : 'Disabled'} />
        </>
      )}
      {section === 'lateArrival' && (
        <>
          <DetailRow label="Basis" value={policy.lateArrival.basis === 'TOTAL_HOURS' ? 'Total hours' : 'Number of incidents'} />
          <DetailRow label="Grace period" value={policy.lateArrival.gracePeriodMinutes != null ? `${policy.lateArrival.gracePeriodMinutes} min(s) every shift` : 'Not configured'} />
          {policy.lateArrival.basis === 'TOTAL_HOURS' ? (
            <>
              <DetailRow label="Allowed hours" value={policy.lateArrival.allowedHours != null ? `${policy.lateArrival.allowedHours}h / ${policy.lateArrival.exemptPeriod.toLowerCase()}` : 'Not configured'} />
              {policy.lateArrival.lateHoursTiers.length === 0 ? (
                <DetailRow label="Tiers" value="Not configured" />
              ) : policy.lateArrival.lateHoursTiers.map((t, i) => (
                <DetailRow key={i} label={`Greater than ${t.thresholdHours}h`} value={`${t.deductionDays} day(s)`} />
              ))}
            </>
          ) : (
            <>
              <DetailRow label="Exempt" value={policy.lateArrival.exemptCount != null ? `${policy.lateArrival.exemptCount} late arrival(s) / ${policy.lateArrival.exemptPeriod.toLowerCase()}` : 'Not configured'} />
              <DetailRow label="Deduction" value={policy.lateArrival.deductionDays != null ? `${policy.lateArrival.deductionDays} day(s) per ${policy.lateArrival.deductionPerShifts ?? 1} shift(s)` : 'Not configured'} />
              {policy.lateArrival.lateHoursTiers.length > 0 && (
                <DetailRow label="Also: total late hours tiers" value={policy.lateArrival.lateHoursTiers.map(t => `>${t.thresholdHours}h → ${t.deductionDays}d`).join(', ')} />
              )}
              {policy.lateArrival.lateHoursTiers.length > 0 && (
                <DetailRow label="When both incident and total-hours thresholds are exceeded" value={policy.lateArrival.combinedRuleBehavior === 'BOTH' ? 'Apply both' : 'Total hours only'} />
              )}
            </>
          )}
          <DetailRow label="Ignore if effective hours met" value={policy.lateArrival.ignoreWhenEffectiveHoursMetEnabled ? 'Yes' : 'No'} />
          <DetailRow label="Penalise if caused by missing log" value={policy.lateArrival.penaliseWhenCausedByMissingLogEnabled ? 'Yes' : 'No (default)'} />
        </>
      )}
      {section === 'workHoursShortage' && (
        <>
          <DetailRow label="Deduction basis" value="Effective Hours / Day" />
          {policy.workHoursShortage.tiers.length === 0 ? (
            <DetailRow label="Tiers" value="Not configured" />
          ) : policy.workHoursShortage.tiers.map((t, i) => (
            <DetailRow key={i} label={`Less than ${t.thresholdPercent}% of shift hours`} value={`${t.deductionDays} day(s)`} />
          ))}
          <DetailRow label="Apply penalty for shortage (same-day overlap)" value={policy.workHoursShortage.applyPenaltyForShortageEnabled ? 'Yes' : 'No'} />
          <DetailRow label="Also apply late-arrival penalty (same-day overlap)" value={policy.workHoursShortage.applyPenaltyForLateArrivalEnabled ? 'Yes' : 'No'} />
        </>
      )}
      {section === 'missingLogs' && (
        <>
          <DetailRow label="Exempt" value={policy.missingLogs.exemptDays != null ? `${policy.missingLogs.exemptDays} day(s) / ${policy.missingLogs.exemptPeriod.toLowerCase()}` : 'Not configured'} />
          <DetailRow label="Deduction mode" value={policy.missingLogs.deductionMode === 'PER_SHIFT' ? 'Per shift of missing logs' : 'Irrespective of missed logs'} />
          <DetailRow label="Deduction" value={policy.missingLogs.deductionDays != null ? `${policy.missingLogs.deductionDays} day(s)${policy.missingLogs.deductionMode === 'PER_SHIFT' ? ` per ${policy.missingLogs.deductionPerShifts ?? 1} shift(s)` : ''}` : 'Not configured'} />
          <DetailRow label="Ignore rule" value={policy.missingLogs.ignoreRuleEnabled ? `Above ${policy.missingLogs.ignoreRuleThresholdPercent ?? '—'}% effective hours` : 'Disabled'} />
        </>
      )}
    </ModalShell>
  );
}

// ── Edit (whole document — all 4 sections in one save, matching the approved screenshots' single drawer) ──
function EditModal({ initial, onClose, onSaved, initialTab, token, leaveTypes, policyId }: {
  initial: PenalizationPolicyRequest; onClose: () => void; onSaved: (p: PenalizationPolicy) => void;
  initialTab: SectionKey; token: string; leaveTypes: LeaveType[]; policyId?: string;
}) {
  const { showToast } = useToast();
  const [tab, setTab] = useState<SectionKey>(initialTab);
  const [form, setForm] = useState<PenalizationPolicyRequest>(initial);
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);

  function patch<K extends SectionKey>(key: K, patch: Partial<PenalizationPolicyRequest[K]>) {
    setForm(f => ({ ...f, [key]: { ...f[key], ...patch } }));
  }

  async function submit() {
    setError('');
    setSaving(true);
    try {
      const saved = await savePolicy(token, form, policyId);
      onSaved(saved);
      showToast('success', 'Penalization Policy saved — takes effect from the next monthly cycle');
      onClose();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to save policy');
    } finally {
      setSaving(false);
    }
  }

  const basicInfo = form.basicInfo, na = form.noAttendance, la = form.lateArrival, whs = form.workHoursShortage, ml = form.missingLogs;

  function setTier(i: number, field: keyof WorkHoursTier, value: number) {
    const tiers = whs.tiers.map((t, idx) => idx === i ? { ...t, [field]: value } : t);
    patch('workHoursShortage', { tiers });
  }
  function addTier() {
    patch('workHoursShortage', { tiers: [...whs.tiers, { thresholdPercent: 0, deductionDays: 0 }] });
  }
  function removeTier(i: number) {
    patch('workHoursShortage', { tiers: whs.tiers.filter((_, idx) => idx !== i) });
  }

  function setLateHoursTier(i: number, field: keyof LateHoursTier, value: number) {
    const tiers = la.lateHoursTiers.map((t, idx) => idx === i ? { ...t, [field]: value } : t);
    patch('lateArrival', { lateHoursTiers: tiers });
  }
  function addLateHoursTier() {
    patch('lateArrival', { lateHoursTiers: [...la.lateHoursTiers, { thresholdHours: 0, deductionDays: 0 }] });
  }
  function removeLateHoursTier(i: number) {
    patch('lateArrival', { lateHoursTiers: la.lateHoursTiers.filter((_, idx) => idx !== i) });
  }

  function moveLeaveType(i: number, direction: -1 | 1) {
    const order = [...basicInfo.leavePriorityOrder];
    const j = i + direction;
    if (j < 0 || j >= order.length) return;
    [order[i], order[j]] = [order[j], order[i]];
    patch('basicInfo', { leavePriorityOrder: order });
  }
  function removeLeaveType(code: string) {
    patch('basicInfo', { leavePriorityOrder: basicInfo.leavePriorityOrder.filter(c => c !== code) });
  }
  function addLeaveType(code: string) {
    if (!code || basicInfo.leavePriorityOrder.includes(code)) return;
    patch('basicInfo', { leavePriorityOrder: [...basicInfo.leavePriorityOrder, code] });
  }

  return (
    <ModalShell title="Edit Penalization Policy" onClose={onClose} width={640}>
      <div style={{ display: 'flex', gap: 6, marginBottom: 16, borderBottom: '1px solid var(--line)', paddingBottom: 8, flexWrap: 'wrap' }}>
        {SECTIONS.map(s => (
          <button key={s} onClick={() => setTab(s)} style={{
            padding: '6px 12px', borderRadius: 6, fontSize: 12.5, fontWeight: tab === s ? 600 : 400,
            background: tab === s ? 'var(--raised)' : 'transparent',
            color: tab === s ? 'var(--brand-bright)' : 'var(--txt-mut)',
            border: '1px solid ' + (tab === s ? 'var(--line2)' : 'transparent'), cursor: 'pointer',
          }}>
            {SECTION_LABELS[s]}
          </button>
        ))}
      </div>

      {error && (
        <div role="alert" style={{ background: 'rgba(228,55,61,.1)', border: '1px solid rgba(228,55,61,.3)', borderRadius: 6, padding: '8px 12px', marginBottom: 14, color: 'var(--risk)', fontSize: 12.5 }}>
          {error}
        </div>
      )}

      <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
        {TOGGLEABLE_SECTIONS.includes(tab) && (
          <label style={checkboxRow}>
            <input type="checkbox" checked={
              tab === 'noAttendance' ? na.enabled : tab === 'lateArrival' ? la.enabled :
              tab === 'workHoursShortage' ? whs.enabled : ml.enabled
            } onChange={e => patch(tab, { enabled: e.target.checked } as any)} />
            Enabled
          </label>
        )}

        {tab === 'basicInfo' && (
          <>
            <div>
              <span style={labelText}>Deduction method</span>
              <select style={inputStyle} value={basicInfo.deductionMethod}
                onChange={e => patch('basicInfo', { deductionMethod: e.target.value as any })}>
                <option value="LOSS_OF_PAY">Loss of Pay — every penalty is Loss of Pay</option>
                <option value="PAID_LEAVE">Paid Leave — deduct from configured leave types</option>
              </select>
            </div>
            {basicInfo.deductionMethod === 'PAID_LEAVE' && (
              <div>
                <span style={labelText}>Leave deduction order (top exhausted first, remainder becomes Loss of Pay)</span>
                <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                  {basicInfo.leavePriorityOrder.map((code, i) => {
                    const lt = leaveTypes.find(t => t.code === code);
                    return (
                      <div key={code} style={{ display: 'flex', alignItems: 'center', gap: 8, background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, padding: '6px 10px' }}>
                        <span style={{ fontSize: 12, fontWeight: 600, color: 'var(--txt-mut)', width: 18 }}>{i + 1}.</span>
                        <span style={{ flex: 1, fontSize: 13, color: 'var(--txt)' }}>{lt?.name ?? code}</span>
                        <button type="button" onClick={() => moveLeaveType(i, -1)} disabled={i === 0} style={{ background: 'transparent', border: 'none', cursor: i === 0 ? 'not-allowed' : 'pointer', color: 'var(--txt-mut)' }}>↑</button>
                        <button type="button" onClick={() => moveLeaveType(i, 1)} disabled={i === basicInfo.leavePriorityOrder.length - 1} style={{ background: 'transparent', border: 'none', cursor: 'pointer', color: 'var(--txt-mut)' }}>↓</button>
                        <button type="button" onClick={() => removeLeaveType(code)} style={{ background: 'transparent', border: 'none', cursor: 'pointer', color: 'var(--risk)' }}>Remove</button>
                      </div>
                    );
                  })}
                </div>
                {leaveTypes.filter(t => !basicInfo.leavePriorityOrder.includes(t.code)).length > 0 && (
                  <select style={{ ...inputStyle, marginTop: 8 }} value=""
                    onChange={e => addLeaveType(e.target.value)}>
                    <option value="">+ Add leave type…</option>
                    {leaveTypes.filter(t => !basicInfo.leavePriorityOrder.includes(t.code)).map(t => (
                      <option key={t.code} value={t.code}>{t.name}</option>
                    ))}
                  </select>
                )}
              </div>
            )}
            <div>
              <span style={labelText}>Buffer period (days an employee has to self-correct before a penalty is finalized)</span>
              <input type="number" min={0} style={inputStyle} value={basicInfo.bufferPeriodDays ?? ''}
                onChange={e => patch('basicInfo', { bufferPeriodDays: e.target.value === '' ? null : Number(e.target.value) })} />
            </div>
            <label style={checkboxRow}>
              <input type="checkbox" checked={basicInfo.noticePeriodForcesLopEnabled}
                onChange={e => patch('basicInfo', { noticePeriodForcesLopEnabled: e.target.checked })} />
              If employee is under notice period, consider all penalties as Loss of Pay
            </label>
          </>
        )}

        {tab === 'noAttendance' && (
          <>
            <div>
              <span style={labelText}>Deduct (day(s) leave for every day of no attendance)</span>
              <input type="number" min={0} step={0.25} style={inputStyle} value={na.deductionDays ?? ''}
                onChange={e => patch('noAttendance', { deductionDays: e.target.value === '' ? null : Number(e.target.value) })} />
            </div>
            <label style={checkboxRow}>
              <input type="checkbox" checked={na.noShowEnabled} onChange={e => patch('noAttendance', { noShowEnabled: e.target.checked })} />
              Employee working less than X effective hours is considered a no-show
            </label>
            {na.noShowEnabled && (
              <div>
                <span style={labelText}>No-show threshold (effective hours)</span>
                <input type="number" min={0} step={0.5} style={inputStyle} value={na.noShowThresholdHours ?? ''}
                  onChange={e => patch('noAttendance', { noShowThresholdHours: e.target.value === '' ? null : Number(e.target.value) })} />
              </div>
            )}

            <div style={{ fontSize: 12, fontWeight: 600, color: 'var(--txt-mut)', marginTop: 4 }}>Adjoining Holiday Penalty</div>
            <label style={checkboxRow}>
              <input type="checkbox" checked={na.adjoiningHolidayEnabled}
                onChange={e => patch('noAttendance', { adjoiningHolidayEnabled: e.target.checked })} />
              Also penalise the holiday itself when no-attendance days adjoin it
            </label>
            {na.adjoiningHolidayEnabled && (
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
                <div>
                  <span style={labelText}>Condition</span>
                  <select style={inputStyle} value={na.adjoiningHolidayCondition ?? 'ANY'}
                    onChange={e => patch('noAttendance', { adjoiningHolidayCondition: e.target.value as any })}>
                    <option value="SANDWICHED">Sandwiched (before AND after)</option>
                    <option value="BEFORE">No attendance immediately before</option>
                    <option value="AFTER">No attendance immediately after</option>
                    <option value="ANY">Before, after, or in-between (any)</option>
                  </select>
                </div>
                <div>
                  <span style={labelText}>No-attendance calendar-day threshold</span>
                  <input type="number" min={1} style={inputStyle} value={na.adjoiningHolidayCalendarDayThreshold ?? 1}
                    onChange={e => patch('noAttendance', { adjoiningHolidayCalendarDayThreshold: Number(e.target.value) })} />
                </div>
                <label style={{ ...checkboxRow, gridColumn: '1 / -1' }}>
                  <input type="checkbox" checked={na.adjoiningHolidayIgnoreHalfDayLeave}
                    onChange={e => patch('noAttendance', { adjoiningHolidayIgnoreHalfDayLeave: e.target.checked })} />
                  Ignore half-day leave (a half-day leave does not count as a no-attendance day)
                </label>
              </div>
            )}

            <div style={{ fontSize: 12, fontWeight: 600, color: 'var(--txt-mut)', marginTop: 4 }}>Adjoining Week-Off Penalty</div>
            <label style={checkboxRow}>
              <input type="checkbox" checked={na.adjoiningWeekoffEnabled}
                onChange={e => patch('noAttendance', { adjoiningWeekoffEnabled: e.target.checked })} />
              Also penalise the week-off itself when no-attendance days adjoin it
            </label>
            {na.adjoiningWeekoffEnabled && (
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
                <div>
                  <span style={labelText}>Condition</span>
                  <select style={inputStyle} value={na.adjoiningWeekoffCondition ?? 'ANY'}
                    onChange={e => patch('noAttendance', { adjoiningWeekoffCondition: e.target.value as any })}>
                    <option value="SANDWICHED">Sandwiched (before AND after)</option>
                    <option value="BEFORE">No attendance immediately before</option>
                    <option value="AFTER">No attendance immediately after</option>
                    <option value="ANY">Before, after, or in-between (any)</option>
                  </select>
                </div>
                <div>
                  <span style={labelText}>No-attendance calendar-day threshold</span>
                  <input type="number" min={1} style={inputStyle} value={na.adjoiningWeekoffCalendarDayThreshold ?? 1}
                    onChange={e => patch('noAttendance', { adjoiningWeekoffCalendarDayThreshold: Number(e.target.value) })} />
                </div>
                <label style={{ ...checkboxRow, gridColumn: '1 / -1' }}>
                  <input type="checkbox" checked={na.adjoiningWeekoffIgnoreHalfDayLeave}
                    onChange={e => patch('noAttendance', { adjoiningWeekoffIgnoreHalfDayLeave: e.target.checked })} />
                  Ignore half-day leave (a half-day leave does not count as a no-attendance day)
                </label>
              </div>
            )}
          </>
        )}

        {tab === 'lateArrival' && (
          <>
            <div>
              <span style={labelText}>Basis</span>
              <select style={inputStyle} value={la.basis} onChange={e => patch('lateArrival', { basis: e.target.value as any })}>
                <option value="NUMBER_OF_INCIDENTS">Number of Incidents</option>
                <option value="TOTAL_HOURS">Total Hours</option>
              </select>
            </div>
            <div>
              <span style={labelText}>Grace period (minutes every shift)</span>
              <input type="number" min={0} style={inputStyle} value={la.gracePeriodMinutes ?? ''}
                onChange={e => patch('lateArrival', { gracePeriodMinutes: e.target.value === '' ? null : Number(e.target.value) })} />
            </div>

            {la.basis === 'NUMBER_OF_INCIDENTS' && (
              <>
                <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
                  <div>
                    <span style={labelText}>Exempt late arrival(s)</span>
                    <input type="number" min={0} style={inputStyle} value={la.exemptCount ?? ''}
                      onChange={e => patch('lateArrival', { exemptCount: e.target.value === '' ? null : Number(e.target.value) })} />
                  </div>
                  <div>
                    <span style={labelText}>Per cycle</span>
                    <select style={inputStyle} value={la.exemptPeriod} onChange={e => patch('lateArrival', { exemptPeriod: e.target.value as any })}>
                      <option value="WEEK">Week</option>
                      <option value="MONTH">Month</option>
                    </select>
                  </div>
                </div>
                <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
                  <div>
                    <span style={labelText}>Post exemption, deduct (day(s))</span>
                    <input type="number" min={0} step={0.25} style={inputStyle} value={la.deductionDays ?? ''}
                      onChange={e => patch('lateArrival', { deductionDays: e.target.value === '' ? null : Number(e.target.value) })} />
                  </div>
                  <div>
                    <span style={labelText}>Per shift(s) of late arrival</span>
                    <input type="number" min={1} style={inputStyle} value={la.deductionPerShifts ?? ''}
                      onChange={e => patch('lateArrival', { deductionPerShifts: e.target.value === '' ? null : Number(e.target.value) })} />
                  </div>
                </div>
              </>
            )}

            {la.basis === 'TOTAL_HOURS' && (
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
                <div>
                  <span style={labelText}>Allowed hours</span>
                  <input type="number" min={0} step={0.25} style={inputStyle} value={la.allowedHours ?? ''}
                    onChange={e => patch('lateArrival', { allowedHours: e.target.value === '' ? null : Number(e.target.value) })} />
                </div>
                <div>
                  <span style={labelText}>Per cycle</span>
                  <select style={inputStyle} value={la.exemptPeriod} onChange={e => patch('lateArrival', { exemptPeriod: e.target.value as any })}>
                    <option value="WEEK">Week</option>
                    <option value="MONTH">Month</option>
                  </select>
                </div>
              </div>
            )}

            <div style={{ fontSize: 12, fontWeight: 600, color: 'var(--txt-mut)', marginTop: 4 }}>Total Late Hours in Shift</div>
            <div style={{ fontSize: 12.5, color: 'var(--txt-mut)' }}>
              Rule table evaluated against total late hours for the cycle — used directly when basis is Total Hours, and as an additional check alongside Number of Incidents.
            </div>
            {la.lateHoursTiers.map((t, i) => (
              <div key={i} style={{ display: 'grid', gridTemplateColumns: '1fr 1fr auto', gap: 10, alignItems: 'end' }}>
                <div>
                  <span style={labelText}>Greater than X hours</span>
                  <input type="number" min={0} step={0.25} style={inputStyle} value={t.thresholdHours}
                    onChange={e => setLateHoursTier(i, 'thresholdHours', Number(e.target.value))} />
                </div>
                <div>
                  <span style={labelText}>Deduct (day(s))</span>
                  <input type="number" min={0} step={0.25} style={inputStyle} value={t.deductionDays}
                    onChange={e => setLateHoursTier(i, 'deductionDays', Number(e.target.value))} />
                </div>
                <button type="button" onClick={() => removeLateHoursTier(i)} style={{ padding: '7px 10px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, color: 'var(--risk)', cursor: 'pointer' }}>
                  Remove
                </button>
              </div>
            ))}
            <button type="button" onClick={addLateHoursTier} style={{ alignSelf: 'flex-start', padding: '6px 12px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, color: 'var(--brand-bright)', fontSize: 12.5, fontWeight: 600, cursor: 'pointer' }}>
              + Add Rule
            </button>

            {la.basis === 'NUMBER_OF_INCIDENTS' && la.lateHoursTiers.length > 0 && (
              <div>
                <span style={labelText}>When both incident-count and total-hours thresholds are exceeded</span>
                <select style={inputStyle} value={la.combinedRuleBehavior} onChange={e => patch('lateArrival', { combinedRuleBehavior: e.target.value as any })}>
                  <option value="TOTAL_HOURS_ONLY">Apply penalty for total hours only</option>
                  <option value="BOTH">Apply penalty for both</option>
                </select>
              </div>
            )}

            <label style={checkboxRow}>
              <input type="checkbox" checked={la.ignoreWhenEffectiveHoursMetEnabled}
                onChange={e => patch('lateArrival', { ignoreWhenEffectiveHoursMetEnabled: e.target.checked })} />
              Ignore late arrival penalty when employee completes desired effective hours in a shift
            </label>
            <label style={checkboxRow}>
              <input type="checkbox" checked={la.penaliseWhenCausedByMissingLogEnabled}
                onChange={e => patch('lateArrival', { penaliseWhenCausedByMissingLogEnabled: e.target.checked })} />
              Penalise any late arrival caused by missing logs (off by default — avoids double-penalising)
            </label>
          </>
        )}

        {tab === 'workHoursShortage' && (
          <>
            <div style={{ fontSize: 12.5, color: 'var(--txt-mut)' }}>
              Deduct leave for the shortage of Effective Hours on a Day — add as many threshold rules as needed.
            </div>
            {whs.tiers.map((t, i) => (
              <div key={i} style={{ display: 'grid', gridTemplateColumns: '1fr 1fr auto', gap: 10, alignItems: 'end' }}>
                <div>
                  <span style={labelText}>Less than X% of shift hours</span>
                  <input type="number" min={0} max={100} style={inputStyle} value={t.thresholdPercent}
                    onChange={e => setTier(i, 'thresholdPercent', Number(e.target.value))} />
                </div>
                <div>
                  <span style={labelText}>Deduct (day(s))</span>
                  <input type="number" min={0} step={0.25} style={inputStyle} value={t.deductionDays}
                    onChange={e => setTier(i, 'deductionDays', Number(e.target.value))} />
                </div>
                <button type="button" onClick={() => removeTier(i)} style={{ padding: '7px 10px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, color: 'var(--risk)', cursor: 'pointer' }}>
                  Remove
                </button>
              </div>
            ))}
            <button type="button" onClick={addTier} style={{ alignSelf: 'flex-start', padding: '6px 12px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, color: 'var(--brand-bright)', fontSize: 12.5, fontWeight: 600, cursor: 'pointer' }}>
              + Add Rule
            </button>
            <div style={{ fontSize: 12, fontWeight: 600, color: 'var(--txt-mut)', marginTop: 4 }}>When shortage and late arrival both occur the same day</div>
            <label style={checkboxRow}>
              <input type="checkbox" checked={whs.applyPenaltyForShortageEnabled}
                onChange={e => patch('workHoursShortage', { applyPenaltyForShortageEnabled: e.target.checked })} />
              Apply penalty for effective hours shortage
            </label>
            <label style={checkboxRow}>
              <input type="checkbox" checked={whs.applyPenaltyForLateArrivalEnabled}
                onChange={e => patch('workHoursShortage', { applyPenaltyForLateArrivalEnabled: e.target.checked })} />
              Apply penalty for late arrival
            </label>
          </>
        )}

        {tab === 'missingLogs' && (
          <>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
              <div>
                <span style={labelText}>Exempt day(s) of missing logs</span>
                <input type="number" min={0} style={inputStyle} value={ml.exemptDays ?? ''}
                  onChange={e => patch('missingLogs', { exemptDays: e.target.value === '' ? null : Number(e.target.value) })} />
              </div>
              <div>
                <span style={labelText}>Per cycle</span>
                <select style={inputStyle} value={ml.exemptPeriod} onChange={e => patch('missingLogs', { exemptPeriod: e.target.value as any })}>
                  <option value="WEEK">Week</option>
                  <option value="MONTH">Month</option>
                </select>
              </div>
            </div>
            <div>
              <span style={labelText}>Deduction mode</span>
              <select style={inputStyle} value={ml.deductionMode} onChange={e => patch('missingLogs', { deductionMode: e.target.value as any })}>
                <option value="PER_SHIFT">Deduct per shift of missing logs</option>
                <option value="IRRESPECTIVE">Deduct irrespective of missed logs</option>
              </select>
            </div>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
              <div>
                <span style={labelText}>Deduct (day(s))</span>
                <input type="number" min={0} step={0.25} style={inputStyle} value={ml.deductionDays ?? ''}
                  onChange={e => patch('missingLogs', { deductionDays: e.target.value === '' ? null : Number(e.target.value) })} />
              </div>
              {ml.deductionMode === 'PER_SHIFT' && (
                <div>
                  <span style={labelText}>Per shift(s) of missing logs</span>
                  <input type="number" min={1} style={inputStyle} value={ml.deductionPerShifts ?? ''}
                    onChange={e => patch('missingLogs', { deductionPerShifts: e.target.value === '' ? null : Number(e.target.value) })} />
                </div>
              )}
            </div>
            <label style={checkboxRow}>
              <input type="checkbox" checked={ml.ignoreRuleEnabled} onChange={e => patch('missingLogs', { ignoreRuleEnabled: e.target.checked })} />
              Ignore missing logs rule when effective hours exceed X% of shift hours
            </label>
            {ml.ignoreRuleEnabled && (
              <div>
                <span style={labelText}>Threshold (%)</span>
                <input type="number" min={0} max={100} style={inputStyle} value={ml.ignoreRuleThresholdPercent ?? ''}
                  onChange={e => patch('missingLogs', { ignoreRuleThresholdPercent: e.target.value === '' ? null : Number(e.target.value) })} />
              </div>
            )}
          </>
        )}
      </div>

      <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end', marginTop: 20 }}>
        <button type="button" onClick={onClose} style={{ padding: '7px 14px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, fontSize: 12.5, color: 'var(--txt-mut)', cursor: 'pointer' }}>
          Cancel
        </button>
        <button type="button" onClick={submit} disabled={saving} style={{ padding: '7px 16px', background: 'var(--brand)', border: 'none', borderRadius: 6, fontSize: 12.5, fontWeight: 600, color: '#fff', cursor: saving ? 'not-allowed' : 'pointer', opacity: saving ? 0.7 : 1 }}>
          {saving ? 'Saving…' : 'Save'}
        </button>
      </div>
    </ModalShell>
  );
}

const EMPTY_REQUEST: PenalizationPolicyRequest = {
  basicInfo: { deductionMethod: 'LOSS_OF_PAY', leavePriorityOrder: [], bufferPeriodDays: null, noticePeriodForcesLopEnabled: false },
  noAttendance: {
    enabled: false, deductionDays: null, noShowEnabled: false, noShowThresholdHours: null,
    adjoiningHolidayEnabled: false, adjoiningHolidayCondition: null, adjoiningHolidayCalendarDayThreshold: 1, adjoiningHolidayIgnoreHalfDayLeave: true,
    adjoiningWeekoffEnabled: false, adjoiningWeekoffCondition: null, adjoiningWeekoffCalendarDayThreshold: 1, adjoiningWeekoffIgnoreHalfDayLeave: true,
  },
  lateArrival: {
    enabled: false, basis: 'NUMBER_OF_INCIDENTS', gracePeriodMinutes: null, exemptCount: null, exemptPeriod: 'MONTH',
    deductionDays: null, deductionPerShifts: null, ignoreWhenEffectiveHoursMetEnabled: false,
    allowedHours: null, lateHoursTiers: [], combinedRuleBehavior: 'TOTAL_HOURS_ONLY', penaliseWhenCausedByMissingLogEnabled: false,
  },
  workHoursShortage: { enabled: false, deductionBasis: 'EFFECTIVE_HOURS', deductionPeriod: 'DAY', tiers: [], applyPenaltyForShortageEnabled: true, applyPenaltyForLateArrivalEnabled: false },
  missingLogs: { enabled: false, exemptDays: null, exemptPeriod: 'MONTH', deductionMode: 'PER_SHIFT', deductionDays: null, deductionPerShifts: null, ignoreRuleEnabled: false, ignoreRuleThresholdPercent: null },
};

function toRequest(p: PenalizationPolicy | null): PenalizationPolicyRequest {
  if (!p) return EMPTY_REQUEST;
  return {
    basicInfo: { ...p.basicInfo, leavePriorityOrder: [...p.basicInfo.leavePriorityOrder] },
    noAttendance: { ...p.noAttendance },
    lateArrival: { ...p.lateArrival, lateHoursTiers: p.lateArrival.lateHoursTiers.map(t => ({ ...t })) },
    workHoursShortage: { ...p.workHoursShortage, tiers: p.workHoursShortage.tiers.map(t => ({ ...t })) },
    missingLogs: { ...p.missingLogs },
  };
}

export default function PenalizationPolicySection({ token, policyId }: { token: string; policyId?: string }) {
  const [policy, setPolicy] = useState<PenalizationPolicy | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const [viewing, setViewing] = useState<SectionKey | null>(null);
  const [editing, setEditing] = useState<SectionKey | null>(null);
  const [leaveTypes, setLeaveTypes] = useState<LeaveType[]>([]);

  async function load() {
    setLoadError('');
    setLoading(true);
    try {
      const current = await getCurrentPolicy(token, policyId);
      setPolicy(current);
    } catch (e) {
      setLoadError(e instanceof Error ? e.message : 'Failed to load Penalization Policy');
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { if (token) load(); }, [token, policyId]);
  useEffect(() => { if (token) leaveApi.listTypes(token).then(setLeaveTypes).catch(() => {}); }, [token]);

  if (loading) {
    return <div style={{ padding: 24, color: 'var(--txt-mut)', fontSize: 13 }}>Loading…</div>;
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      {loadError && (
        <div role="alert" style={{ background: 'rgba(228,55,61,.1)', border: '1px solid rgba(228,55,61,.3)', borderRadius: 8, padding: '10px 14px', color: 'var(--risk)', fontSize: 13 }}>
          {loadError}
        </div>
      )}

      {policy && (
        <div style={{ fontSize: 12, color: 'var(--txt-dim)' }}>
          Version {policy.version} · Effective from {fmtDate(policy.effectiveFrom)}
          {policy.effectiveTo ? ` to ${fmtDate(policy.effectiveTo)}` : ' (current)'}
        </div>
      )}

      {SECTIONS.map(key => {
        const enabled = policy && key !== 'basicInfo' ? (
          key === 'noAttendance' ? policy.noAttendance.enabled :
          key === 'lateArrival' ? policy.lateArrival.enabled :
          key === 'workHoursShortage' ? policy.workHoursShortage.enabled :
          policy.missingLogs.enabled
        ) : false;
        return (
          <div key={key} style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '14px 18px', display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 16 }}>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 4, minWidth: 0 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                <span style={{ fontSize: 14, fontWeight: 600, color: 'var(--txt)' }}>{SECTION_LABELS[key]}</span>
                {key !== 'basicInfo' && <EnabledBadge enabled={enabled} />}
              </div>
              <span style={{ fontSize: 12.5, color: 'var(--txt-mut)' }}>{summarize(policy, key)}</span>
            </div>
            <div style={{ display: 'flex', gap: 6, flexShrink: 0 }}>
              <button aria-label={`View ${SECTION_LABELS[key]}`} disabled={!policy} onClick={() => setViewing(key)} style={{
                display: 'flex', alignItems: 'center', justifyContent: 'center', width: 32, height: 32,
                background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6,
                color: 'var(--txt-mut)', cursor: policy ? 'pointer' : 'not-allowed', opacity: policy ? 1 : 0.5,
              }}>
                <Eye size={14} />
              </button>
              <button aria-label={`Edit ${SECTION_LABELS[key]}`} onClick={() => setEditing(key)} style={{
                display: 'flex', alignItems: 'center', justifyContent: 'center', width: 32, height: 32,
                background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6,
                color: 'var(--txt-mut)', cursor: 'pointer',
              }}>
                <Pencil size={14} />
              </button>
            </div>
          </div>
        );
      })}

      {viewing && policy && (
        <ViewModal policy={policy} section={viewing} onClose={() => setViewing(null)} />
      )}
      {editing && (
        <EditModal
          initial={toRequest(policy)}
          initialTab={editing}
          token={token}
          leaveTypes={leaveTypes}
          policyId={policyId}
          onClose={() => setEditing(null)}
          onSaved={setPolicy}
        />
      )}
    </div>
  );
}
