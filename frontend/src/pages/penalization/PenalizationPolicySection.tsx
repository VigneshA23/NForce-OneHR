import { useEffect, useState } from 'react';
import { Eye, Pencil, X } from 'lucide-react';
import { useToast } from '../../context/ToastContext';
import {
  getCurrentPolicy, savePolicy,
  type PenalizationPolicy, type PenalizationPolicyRequest, type WorkHoursTier,
} from '../../api/penalizationPolicy';

type SectionKey = 'noAttendance' | 'lateArrival' | 'workHoursShortage' | 'missingLogs';

const SECTION_LABELS: Record<SectionKey, string> = {
  noAttendance: 'No Attendance',
  lateArrival: 'Late Arrival',
  workHoursShortage: 'Work Hours Shortage',
  missingLogs: 'Missing Logs',
};

const SECTIONS: SectionKey[] = ['noAttendance', 'lateArrival', 'workHoursShortage', 'missingLogs'];

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
  if (key === 'noAttendance') {
    const c = policy.noAttendance;
    if (!c.enabled) return 'Disabled';
    if (c.deductionDays == null) return 'Not configured';
    return `${c.deductionDays} day(s) leave for every no-attendance day`;
  }
  if (key === 'lateArrival') {
    const c = policy.lateArrival;
    if (!c.enabled) return 'Disabled';
    if (c.gracePeriodMinutes == null) return 'Not configured';
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
      <div style={{ marginBottom: 12 }}>
        <EnabledBadge enabled={
          section === 'noAttendance' ? policy.noAttendance.enabled :
          section === 'lateArrival' ? policy.lateArrival.enabled :
          section === 'workHoursShortage' ? policy.workHoursShortage.enabled :
          policy.missingLogs.enabled
        } />
      </div>
      <DetailRow label="Policy version" value={`V${policy.version}`} />
      <DetailRow label="Effective from" value={fmtDate(policy.effectiveFrom)} />
      <DetailRow label="Effective to" value={policy.effectiveTo ? fmtDate(policy.effectiveTo) : 'Current'} />

      {section === 'noAttendance' && (
        <>
          <DetailRow label="Deduction" value={policy.noAttendance.deductionDays != null ? `${policy.noAttendance.deductionDays} day(s) per no-attendance day` : 'Not configured'} />
          <DetailRow label="No-show rule" value={policy.noAttendance.noShowEnabled ? `Below ${policy.noAttendance.noShowThresholdHours ?? '—'} effective hours` : 'Disabled'} />
        </>
      )}
      {section === 'lateArrival' && (
        <>
          <DetailRow label="Basis" value="Number of incidents" />
          <DetailRow label="Grace period" value={policy.lateArrival.gracePeriodMinutes != null ? `${policy.lateArrival.gracePeriodMinutes} min(s) every shift` : 'Not configured'} />
          <DetailRow label="Exempt" value={policy.lateArrival.exemptCount != null ? `${policy.lateArrival.exemptCount} late arrival(s) / ${policy.lateArrival.exemptPeriod.toLowerCase()}` : 'Not configured'} />
          <DetailRow label="Deduction" value={policy.lateArrival.deductionDays != null ? `${policy.lateArrival.deductionDays} day(s) per ${policy.lateArrival.deductionPerShifts ?? 1} shift(s)` : 'Not configured'} />
          <DetailRow label="Ignore if effective hours met" value={policy.lateArrival.ignoreWhenEffectiveHoursMetEnabled ? 'Yes' : 'No'} />
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
function EditModal({ initial, onClose, onSaved, initialTab, token }: {
  initial: PenalizationPolicyRequest; onClose: () => void; onSaved: (p: PenalizationPolicy) => void; initialTab: SectionKey; token: string;
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
      const saved = await savePolicy(token, form);
      onSaved(saved);
      showToast('success', 'Penalization Policy saved — takes effect from the next monthly cycle');
      onClose();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to save policy');
    } finally {
      setSaving(false);
    }
  }

  const na = form.noAttendance, la = form.lateArrival, whs = form.workHoursShortage, ml = form.missingLogs;

  function setTier(i: number, field: keyof WorkHoursTier, value: number) {
    const tiers = whs.tiers.map((t, idx) => idx === i ? { ...t, [field]: value } : t);
    patch('workHoursShortage', { tiers });
  }
  function removeTier(i: number) {
    patch('workHoursShortage', { tiers: whs.tiers.filter((_, idx) => idx !== i) });
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
        <label style={checkboxRow}>
          <input type="checkbox" checked={
            tab === 'noAttendance' ? na.enabled : tab === 'lateArrival' ? la.enabled :
            tab === 'workHoursShortage' ? whs.enabled : ml.enabled
          } onChange={e => patch(tab, { enabled: e.target.checked } as any)} />
          Enabled
        </label>

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
          </>
        )}

        {tab === 'lateArrival' && (
          <>
            <div>
              <span style={labelText}>Grace period (minutes every shift)</span>
              <input type="number" min={0} style={inputStyle} value={la.gracePeriodMinutes ?? ''}
                onChange={e => patch('lateArrival', { gracePeriodMinutes: e.target.value === '' ? null : Number(e.target.value) })} />
            </div>
            <div>
              <span style={labelText}>Exempt late arrival(s) per month</span>
              <input type="number" min={0} style={inputStyle} value={la.exemptCount ?? ''}
                onChange={e => patch('lateArrival', { exemptCount: e.target.value === '' ? null : Number(e.target.value) })} />
            </div>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
              <div>
                <span style={labelText}>Deduct (day(s))</span>
                <input type="number" min={0} step={0.25} style={inputStyle} value={la.deductionDays ?? ''}
                  onChange={e => patch('lateArrival', { deductionDays: e.target.value === '' ? null : Number(e.target.value) })} />
              </div>
              <div>
                <span style={labelText}>Per shift(s) of late arrival</span>
                <input type="number" min={1} style={inputStyle} value={la.deductionPerShifts ?? ''}
                  onChange={e => patch('lateArrival', { deductionPerShifts: e.target.value === '' ? null : Number(e.target.value) })} />
              </div>
            </div>
            <label style={checkboxRow}>
              <input type="checkbox" checked={la.ignoreWhenEffectiveHoursMetEnabled}
                onChange={e => patch('lateArrival', { ignoreWhenEffectiveHoursMetEnabled: e.target.checked })} />
              Ignore late arrival penalty when employee completes desired effective hours in a shift
            </label>
          </>
        )}

        {tab === 'workHoursShortage' && (
          <>
            <div style={{ fontSize: 12.5, color: 'var(--txt-mut)' }}>
              Deduct leave for the shortage of Effective Hours on a Day — tiers below (existing tiers only; adding new tiers isn't supported in this release).
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
            <div>
              <span style={labelText}>Exempt day(s) of missing logs per month</span>
              <input type="number" min={0} style={inputStyle} value={ml.exemptDays ?? ''}
                onChange={e => patch('missingLogs', { exemptDays: e.target.value === '' ? null : Number(e.target.value) })} />
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
  noAttendance: { enabled: false, deductionDays: null, noShowEnabled: false, noShowThresholdHours: null },
  lateArrival: { enabled: false, basis: 'NUMBER_OF_INCIDENTS', gracePeriodMinutes: null, exemptCount: null, exemptPeriod: 'MONTH', deductionDays: null, deductionPerShifts: null, ignoreWhenEffectiveHoursMetEnabled: false },
  workHoursShortage: { enabled: false, deductionBasis: 'EFFECTIVE_HOURS', deductionPeriod: 'DAY', tiers: [], applyPenaltyForShortageEnabled: true, applyPenaltyForLateArrivalEnabled: false },
  missingLogs: { enabled: false, exemptDays: null, exemptPeriod: 'MONTH', deductionMode: 'PER_SHIFT', deductionDays: null, deductionPerShifts: null, ignoreRuleEnabled: false, ignoreRuleThresholdPercent: null },
};

function toRequest(p: PenalizationPolicy | null): PenalizationPolicyRequest {
  if (!p) return EMPTY_REQUEST;
  return {
    noAttendance: { ...p.noAttendance },
    lateArrival: { ...p.lateArrival },
    workHoursShortage: { ...p.workHoursShortage, tiers: p.workHoursShortage.tiers.map(t => ({ ...t })) },
    missingLogs: { ...p.missingLogs },
  };
}

export default function PenalizationPolicySection({ token }: { token: string }) {
  const [policy, setPolicy] = useState<PenalizationPolicy | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const [viewing, setViewing] = useState<SectionKey | null>(null);
  const [editing, setEditing] = useState<SectionKey | null>(null);

  async function load() {
    setLoadError('');
    setLoading(true);
    try {
      const current = await getCurrentPolicy(token);
      setPolicy(current);
    } catch (e) {
      setLoadError(e instanceof Error ? e.message : 'Failed to load Penalization Policy');
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { if (token) load(); }, [token]);

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
        const enabled = policy ? (
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
                <EnabledBadge enabled={enabled} />
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
          onClose={() => setEditing(null)}
          onSaved={setPolicy}
        />
      )}
    </div>
  );
}
