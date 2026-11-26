/**
 * PN-01 — Attendance → My Penalties (employee self-service).
 *
 * Shows every attendance penalty applied to the signed-in employee: date, rule, details, deduction
 * days, leave breakdown, LOP days and status, plus a summary strip including the late-mark
 * exemption tile ("Late marks: 3 of 3 exempt used"). Rows still inside the policy's buffer period
 * (status IN_BUFFER) link to their attendance day and offer the corrective actions (Regularize /
 * Apply Leave). Read-only otherwise — every figure comes from the backend's persisted snapshot.
 */
import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { AlertTriangle, CalendarClock, CalendarDays, ChevronLeft, ChevronRight, CircleCheck, Hourglass, MessageSquareWarning, Pencil, Scale, Timer, Wallet, X } from 'lucide-react';
import { myPenaltiesApi, penaltyDisputeApi, type MyPenalties, type MyPenaltyRow, type MyPenaltyStatus, type PenaltyDispute, type PenaltyDisputeStatus } from '../../api/attendance';
import { useToast } from '../../context/ToastContext';
import './myPenalties.css';
import { previewMyPenalties } from '../attendance-ui-preview/attendancePreviewData';

const MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];
const pad2 = (n: number) => String(n).padStart(2, '0');
const isoOf = (y: number, m: number, d: number) => `${y}-${pad2(m + 1)}-${pad2(d)}`;

function fmtDate(iso: string): string {
  const [y, m, d] = iso.split('-').map(Number);
  return new Date(y, m - 1, d).toLocaleDateString('en-GB', { day: '2-digit', month: 'short', year: 'numeric' });
}
const fmtDays = (n: number | null | undefined) => (n == null ? '-' : `${+n.toFixed(2)}`);

const STATUS_META: Record<MyPenaltyStatus, { label: string; color: string }> = {
  PENDING_REVIEW: { label: 'Applied', color: 'var(--risk)' },
  IN_BUFFER: { label: 'In buffer', color: 'var(--warn)' },
  CANCELLED: { label: 'Cancelled', color: 'var(--ok)' },
  REVERSED: { label: 'Reversed', color: 'var(--ok)' },
};

const DISPUTE_META: Record<PenaltyDisputeStatus, { label: string; color: string }> = {
  PENDING_MANAGER: { label: 'Dispute · with manager', color: 'var(--info)' },
  PENDING_HR: { label: 'Dispute · with HR', color: 'var(--info)' },
  UPHELD: { label: 'Dispute · penalty upheld', color: 'var(--risk)' },
  REVERSED: { label: 'Dispute · reversed', color: 'var(--ok)' },
};

const RULE_COLOR: Record<string, string> = {
  LATE_ARRIVAL: 'var(--warn)', WORK_HOURS_SHORTAGE: 'var(--info)', NO_ATTENDANCE: 'var(--risk)', MISSING_PUNCH: 'var(--brand)',
  EARLY_DEPARTURE: 'var(--warn)', EXCESS_BREAK: 'var(--info)',
};

const panel: React.CSSProperties = {
  background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 8, overflow: 'hidden',
  boxShadow: '0 1px 2px rgba(0,0,0,.06), 0 8px 20px -12px rgba(0,0,0,.25)',
};
const th: React.CSSProperties = {
  padding: '9px 6px', textAlign: 'left', fontSize: 10.5, fontWeight: 700, letterSpacing: '.06em',
  textTransform: 'uppercase', color: 'var(--txt-dim)', background: 'var(--raised)', borderBottom: '1px solid var(--line)', whiteSpace: 'nowrap',
};
const td: React.CSSProperties = { padding: '10px 6px', fontSize: 12.5, color: 'var(--txt-mut)', borderBottom: '1px solid var(--line)', verticalAlign: 'top' };

function Chip({ color, children }: { color: string; children: React.ReactNode }) {
  return (
    <span style={{
      display: 'inline-flex', alignItems: 'center', gap: 5, fontSize: 10.5, fontWeight: 700, color,
      background: `color-mix(in srgb, ${color} 14%, var(--raised))`, border: `1px solid color-mix(in srgb, ${color} 30%, var(--line))`,
      borderRadius: 5, padding: '2.5px 7px', whiteSpace: 'nowrap',
    }}>
      <span style={{ width: 5, height: 5, borderRadius: '50%', background: color, flexShrink: 0 }} />
      {children}
    </span>
  );
}

function Tile({ icon: Icon, label, value, sub, accent, testId }: {
  icon: typeof Timer; label: string; value: React.ReactNode; sub?: React.ReactNode; accent: string; testId?: string;
}) {
  return (
    <div data-testid={testId} style={{
      background: `linear-gradient(135deg, var(--panel) 0%, var(--panel) 35%, color-mix(in srgb, ${accent} 14%, var(--panel)) 100%)`,
      border: `1px solid color-mix(in srgb, ${accent} 24%, var(--line))`, borderLeft: `6px solid ${accent}`, borderRadius: 16,
      padding: '14px 16px', display: 'flex', flexDirection: 'column', gap: 6, minWidth: 0,
    }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 8 }}>
        <span style={{ fontSize: 11, fontWeight: 800, textTransform: 'uppercase', letterSpacing: '.07em', color: 'var(--txt-dim)', lineHeight: 1.3 }}>{label}</span>
        <Icon size={18} style={{ color: accent, flexShrink: 0 }} />
      </div>
      <div style={{ fontFamily: '"Space Grotesk", sans-serif', fontSize: 24, fontWeight: 800, color: 'var(--txt)', lineHeight: 1.1 }}>{value}</div>
      {sub && <div style={{ fontSize: 11.5, color: 'var(--txt-mut)', lineHeight: 1.4 }}>{sub}</div>}
    </div>
  );
}

export interface MyPenaltiesSectionProps {
  token: string;
  isPreview?: boolean;
  /** Bumped by the page after a Regularize / Partial Day request is saved, to re-read the list. */
  refreshKey?: number;
  /** Open that attendance day (the Attendance Log's day-details view). */
  onOpenDay: (iso: string) => void;
  /** Open the Regularize request form for that day. */
  onRegularize: (iso: string) => void;
}

export default function MyPenaltiesSection({ token, isPreview = false, refreshKey = 0, onOpenDay, onRegularize }: MyPenaltiesSectionProps) {
  const now = new Date();
  const [year, setYear] = useState(now.getFullYear());
  const [month, setMonth] = useState(now.getMonth());
  const [data, setData] = useState<MyPenalties | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const { showToast } = useToast();
  const [disputeFor, setDisputeFor] = useState<MyPenaltyRow | null>(null);
  const [reloadTick, setReloadTick] = useState(0);
  const isCurrentMonth = year === now.getFullYear() && month === now.getMonth();

  useEffect(() => {
    let cancelled = false;
    setLoading(true); setError(null);
    if (isPreview) {
      // Mock data only ever describes the current month.
      const mock = previewMyPenalties();
      setData(isCurrentMonth ? mock : { summary: { ...mock.summary, activePenalties: 0, deductionDays: 0, leaveDays: 0, lopDays: 0 }, rows: [] });
      setLoading(false);
      return;
    }
    const from = isoOf(year, month, 1);
    const to = isoOf(year, month, new Date(year, month + 1, 0).getDate());
    myPenaltiesApi.get(from, to, token)
      .then((d) => { if (!cancelled) setData(d); })
      .catch((e: Error) => { if (!cancelled) { setData(null); setError(e.message || 'Could not load your penalties.'); } })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [token, year, month, isPreview, isCurrentMonth, refreshKey, reloadTick]);

  async function submitDispute(row: MyPenaltyRow, reason: string, evidence: string) {
    if (isPreview) {
      // Mock only: reflect the dispute locally, nothing is sent anywhere.
      const mock: PenaltyDispute = {
        id: 'mock-dispute-' + row.id, penaltyId: row.id, reason, evidence: evidence || null, status: 'PENDING_MANAGER',
        managerRecommendation: null, managerComment: null, managerActedAt: null, resolutionComment: null, resolvedAt: null, createdAt: new Date().toISOString(),
      };
      setData((d) => d && { ...d, rows: d.rows.map((r) => (r.id === row.id ? { ...r, disputable: false, dispute: mock } : r)) });
      showToast('success', 'Dispute sent to your manager (preview only, nothing was saved).');
      return;
    }
    await penaltyDisputeApi.raise(row.id, { reason, evidence: evidence || undefined }, token);
    showToast('success', 'Dispute raised. Your manager will review it, then HR.');
    setReloadTick((n) => n + 1);
  }

  function shift(delta: number) {
    const d = new Date(year, month + delta, 1);
    setYear(d.getFullYear()); setMonth(d.getMonth());
  }

  const s = data?.summary;
  const rows = data?.rows ?? [];
  const lateTile = useMemo(() => {
    if (!s) return { value: '-', sub: '' };
    if (!s.lateRuleEnabled) return { value: 'No rule', sub: 'Your policy has no late-arrival rule.' };
    const per = s.period === 'WEEK' ? 'this week' : 'this month';
    if (s.lateMarksExempt == null) return { value: `${s.lateMarks}`, sub: `late marks ${per}` };
    const used = Math.min(s.lateMarks, s.lateMarksExempt);
    const over = Math.max(0, s.lateMarks - s.lateMarksExempt);
    return {
      value: `${used} of ${s.lateMarksExempt}`,
      sub: over > 0
        ? `exempt used ${per}. ${over} more late mark${over > 1 ? 's' : ''} ${over > 1 ? 'are' : 'is'} penalized`
        : used === s.lateMarksExempt ? `exempt used ${per}. The next late mark is penalized` : `exempt used ${per}`,
    };
  }, [s]);

  return (
    <div data-testid="my-penalties" className="nf-mp" style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 10 }}>
        <div>
          <h2 style={{ fontSize: 13, fontWeight: 700, color: 'var(--txt)', margin: 0 }}>My Penalties</h2>
          <p style={{ fontSize: 11, color: 'var(--txt-dim)', marginTop: 3 }}>
            Every attendance penalty applied to you, the rule behind it and how it was deducted, so your payslip adds up.
          </p>
        </div>
        <div style={{ display: 'inline-flex', alignItems: 'center', gap: 4, background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 8, padding: 3 }}>
          <button aria-label="Previous month" onClick={() => shift(-1)} style={navBtn}><ChevronLeft size={15} /></button>
          <span style={{ minWidth: 118, textAlign: 'center', fontSize: 12.5, fontWeight: 600, color: 'var(--txt)' }}>{MONTHS[month]} {year}</span>
          <button aria-label="Next month" onClick={() => shift(1)} disabled={isCurrentMonth} style={{ ...navBtn, opacity: isCurrentMonth ? 0.4 : 1, cursor: isCurrentMonth ? 'default' : 'pointer' }}><ChevronRight size={15} /></button>
        </div>
      </div>

      {s && (
        <div className="nf-mp-tiles">
          <Tile testId="tile-late-marks" icon={Timer} accent="var(--warn)" label="Late marks" value={lateTile.value} sub={lateTile.sub} />
          <Tile icon={Scale} accent="var(--risk)" label="Penalty days (this month)" value={fmtDays(s.deductionDays)}
            sub={`${s.activePenalties} active penalt${s.activePenalties === 1 ? 'y' : 'ies'}`} />
          <Tile icon={CalendarDays} accent="var(--info)" label="Deducted from leave" value={fmtDays(s.leaveDays)} sub="days taken from your leave balance" />
          <Tile icon={Wallet} accent="var(--brand)" label="Loss of pay" value={fmtDays(s.lopDays)} sub="days reflected in your payslip" />
        </div>
      )}

      {s && s.bufferPeriodDays > 0 && rows.some((r) => r.status === 'IN_BUFFER') && (
        <div role="note" style={{ display: 'flex', gap: 10, alignItems: 'flex-start', padding: '10px 12px', borderRadius: 8, fontSize: 12, color: 'var(--txt-mut)',
          background: 'color-mix(in srgb, var(--warn) 10%, var(--panel))', border: '1px solid color-mix(in srgb, var(--warn) 28%, var(--line))' }}>
          <Hourglass size={15} style={{ color: 'var(--warn)', marginTop: 1, flexShrink: 0 }} />
          <span>
            Incidents marked <b>In buffer</b> are not deducted yet. You have <b>{s.bufferPeriodDays} day{s.bufferPeriodDays > 1 ? 's' : ''}</b> after the
            incident to regularize the day or apply leave; after that the policy applies the deduction.
          </span>
        </div>
      )}

      <div style={panel}>
        {loading ? (
          <div style={{ padding: 28, textAlign: 'center', color: 'var(--txt-dim)', fontSize: 12 }}>Loading your penalties…</div>
        ) : error ? (
          <div role="alert" style={{ padding: 28, textAlign: 'center', color: 'var(--risk)', fontSize: 12 }}>{error}</div>
        ) : rows.length === 0 ? (
          <div style={{ padding: '34px 20px', textAlign: 'center', color: 'var(--txt-dim)', fontSize: 12.5 }}>
            <CircleCheck size={22} style={{ color: 'var(--ok)', marginBottom: 6 }} />
            <div style={{ color: 'var(--txt)', fontWeight: 600 }}>No penalties in {MONTHS[month]} {year}</div>
            Nothing has been deducted for your attendance this month.
          </div>
        ) : (
          <div className="nf-req-wrap">
            <table className="nf-req-table" style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead>
                <tr>
                  {['Date', 'Rule', 'Details', 'Deduction', 'Leave split', 'LOP', 'Status', 'Action'].map((h) => <th key={h} style={{ ...th, background: 'transparent' }}>{h}</th>)}
                </tr>
              </thead>
              <tbody>
                {rows.map((r) => <PenaltyRowView key={r.id} row={r} isPreview={isPreview} onOpenDay={onOpenDay} onRegularize={onRegularize} onDispute={setDisputeFor} />)}
              </tbody>
            </table>
          </div>
        )}
      </div>
      {disputeFor && (
        <DisputeModal
          row={disputeFor}
          onClose={() => setDisputeFor(null)}
          onSubmit={async (reason, evidence) => { await submitDispute(disputeFor, reason, evidence); setDisputeFor(null); }}
        />
      )}
    </div>
  );
}

const navBtn: React.CSSProperties = {
  width: 28, height: 28, display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
  background: 'transparent', border: 'none', borderRadius: 6, color: 'var(--txt-mut)', cursor: 'pointer',
};

function PenaltyRowView({ row: r, isPreview, onOpenDay, onRegularize, onDispute }: {
  row: MyPenaltyRow; isPreview: boolean; onOpenDay: (iso: string) => void; onRegularize: (iso: string) => void; onDispute: (row: MyPenaltyRow) => void;
}) {
  const meta = STATUS_META[r.status] ?? { label: r.status, color: 'var(--txt-mut)' };
  const inactive = r.status === 'CANCELLED' || r.status === 'REVERSED';
  const buffer = r.status === 'IN_BUFFER';
  return (
    <tr data-testid="penalty-row" data-status={r.status} style={{ opacity: inactive ? 0.7 : 1, ['--rc' as string]: meta.color }}>
      <td data-label="Date" style={{ ...td, color: 'var(--txt)', fontWeight: 600, whiteSpace: 'nowrap' }}>
        <button onClick={() => onOpenDay(r.incidentDate)} title="Open this attendance day"
          style={{ background: 'none', border: 'none', padding: 0, font: 'inherit', color: 'var(--brand)', cursor: 'pointer', textAlign: 'left', textDecoration: 'underline', textUnderlineOffset: 3 }}>
          {fmtDate(r.incidentDate)}
        </button>
      </td>
      <td data-label="Rule" style={{ ...td, whiteSpace: 'nowrap' }}><Chip color={RULE_COLOR[r.discrepancyType] ?? 'var(--txt-mut)'}>{r.rule}</Chip></td>
      <td data-label="Details" style={td}>{r.details}</td>
      <td data-label="Deduction" style={{ ...td, color: 'var(--txt)', fontWeight: 600, whiteSpace: 'nowrap' }}>
        {buffer ? <span style={{ color: 'var(--txt-dim)', fontWeight: 500 }}>Pending</span> : <>{fmtDays(r.deductionDays)} <span style={{ fontWeight: 500, color: 'var(--txt-dim)' }}>day{r.deductionDays === 1 ? '' : 's'}</span></>}
      </td>
      <td data-label="Leave split" style={td}>
        {buffer || r.leaveBreakdown.length === 0
          ? (buffer ? '-' : <span style={{ color: 'var(--txt-dim)' }}>No leave used</span>)
          : r.leaveBreakdown.map((b) => <div key={b.leaveTypeCode}>{b.leaveTypeName} · <b style={{ color: 'var(--txt)' }}>{fmtDays(b.days)}</b></div>)}
      </td>
      <td data-label="LOP" style={{ ...td, whiteSpace: 'nowrap', color: (r.lopDays ?? 0) > 0 ? 'var(--risk)' : 'var(--txt-mut)', fontWeight: (r.lopDays ?? 0) > 0 ? 700 : 400 }}>
        {buffer ? '-' : fmtDays(r.lopDays)}
      </td>
      <td data-label="Status" style={td}>
        <Chip color={meta.color}>{meta.label}</Chip>
        {r.status === 'CANCELLED' && r.cancellationReason && <div style={{ fontSize: 11, color: 'var(--txt-dim)', marginTop: 4, maxWidth: 170 }}>{r.cancellationReason}</div>}
        {r.dispute && (
          <div data-testid="dispute-status" style={{ marginTop: 6 }}>
            <Chip color={DISPUTE_META[r.dispute.status].color}>{DISPUTE_META[r.dispute.status].label}</Chip>
            {r.dispute.resolutionComment && <div style={{ fontSize: 11, color: 'var(--txt-dim)', marginTop: 4, maxWidth: 190 }}>{r.dispute.resolutionComment}</div>}
          </div>
        )}
      </td>
      <td data-label="Action" style={td}>
        {r.correctable ? (
          <div>
            <div style={{ display: 'flex', gap: 6, flexDirection: 'column', alignItems: 'flex-start' }}>
              <button onClick={() => onRegularize(r.incidentDate)} style={actBtn(true)}><Pencil size={12} /> Regularize</button>
              <Link to="/leave" onClick={(e) => { if (isPreview) e.preventDefault(); }} style={{ ...actBtn(false), textDecoration: 'none' }}><CalendarClock size={12} /> Apply Leave</Link>
            </div>
            {r.correctableUntil && <div style={{ fontSize: 11, color: 'var(--warn)', marginTop: 5 }}>Fix by {fmtDate(r.correctableUntil)}</div>}
          </div>
        ) : r.regularizationOpen ? (
          <span style={{ fontSize: 11.5, color: 'var(--info)', display: 'inline-flex', alignItems: 'flex-start', gap: 5, maxWidth: 130 }}><AlertTriangle size={12} style={{ flexShrink: 0, marginTop: 2 }} /> Regularization already requested</span>
        ) : r.disputable ? (
          <button data-testid="dispute-btn" onClick={() => onDispute(r)} style={actBtn(false)}><MessageSquareWarning size={12} /> Dispute</button>
        ) : null}
      </td>
    </tr>
  );
}

function actBtn(primary: boolean): React.CSSProperties {
  return {
    display: 'inline-flex', alignItems: 'center', gap: 5, padding: '5px 9px', borderRadius: 6, fontSize: 11.5, fontWeight: 600, cursor: 'pointer',
    border: primary ? 'none' : '1px solid var(--line2)', background: primary ? 'var(--brand)' : 'var(--raised)', color: primary ? '#fff' : 'var(--txt-mut)',
  };
}

function DisputeModal({ row, onClose, onSubmit }: { row: MyPenaltyRow; onClose: () => void; onSubmit: (reason: string, evidence: string) => Promise<void> }) {
  const [reason, setReason] = useState('');
  const [evidence, setEvidence] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const field: React.CSSProperties = { width: '100%', boxSizing: 'border-box', background: 'var(--raised)', color: 'var(--txt)', border: '1px solid var(--line2)', borderRadius: 6, padding: '8px 10px', fontSize: 12.5, fontFamily: 'inherit', resize: 'vertical' };
  async function submit() {
    if (!reason.trim()) return;
    setBusy(true); setErr(null);
    try { await onSubmit(reason.trim(), evidence.trim()); } catch (e) { setErr((e as Error).message || 'Could not raise the dispute.'); setBusy(false); }
  }
  return (
    <div role="dialog" aria-modal="true" aria-label="Dispute penalty" onClick={onClose}
      style={{ position: 'fixed', inset: 0, zIndex: 1000, background: 'rgba(0,0,0,.5)', display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 16 }}>
      <div onClick={(e) => e.stopPropagation()} style={{ width: 'min(480px, 100%)', background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, padding: 20, boxShadow: '0 20px 50px rgba(0,0,0,.4)' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 10, marginBottom: 12 }}>
          <div>
            <h3 style={{ margin: 0, fontSize: 15, color: 'var(--txt)' }}>Dispute this penalty</h3>
            <p style={{ margin: '4px 0 0', fontSize: 12, color: 'var(--txt-mut)' }}>
              {row.rule} · {fmtDate(row.incidentDate)} · {fmtDays(row.deductionDays)} day(s). It goes to your manager, then HR, who either uphold the penalty or reverse it.
            </p>
          </div>
          <button aria-label="Close" onClick={onClose} style={{ ...navBtn, flexShrink: 0 }}><X size={15} /></button>
        </div>
        <label style={{ display: 'block', fontSize: 11, fontWeight: 700, letterSpacing: '.05em', textTransform: 'uppercase', color: 'var(--txt-dim)', marginBottom: 5 }}>Reason</label>
        <textarea data-testid="dispute-reason" value={reason} onChange={(e) => setReason(e.target.value)} maxLength={2000} rows={3} placeholder="e.g. The biometric device was down, I was in the office on time" style={field} />
        <label style={{ display: 'block', fontSize: 11, fontWeight: 700, letterSpacing: '.05em', textTransform: 'uppercase', color: 'var(--txt-dim)', margin: '12px 0 5px' }}>Evidence</label>
        <textarea data-testid="dispute-evidence" value={evidence} onChange={(e) => setEvidence(e.target.value)} maxLength={2000} rows={2} placeholder="e.g. IT ticket IT-4821, witness name, door-access log" style={field} />
        {err && <div role="alert" style={{ marginTop: 10, fontSize: 12, color: 'var(--risk)' }}>{err}</div>}
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8, marginTop: 16 }}>
          <button onClick={onClose} style={actBtn(false)}>Cancel</button>
          <button data-testid="dispute-submit" onClick={submit} disabled={!reason.trim() || busy} style={{ ...actBtn(true), opacity: !reason.trim() || busy ? 0.55 : 1 }}>{busy ? 'Submitting…' : 'Submit dispute'}</button>
        </div>
      </div>
    </div>
  );
}
