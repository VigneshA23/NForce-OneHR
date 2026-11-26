/**
 * PN-02 - Penalty disputes awaiting the signed-in manager (forward to HR with a recommendation)
 * or HR (final decision: Uphold the penalty / Reverse it). Rendered above the Regularize & Cancel
 * Penalties table; renders nothing when there is nothing to review. `mock` swaps the API for
 * canned rows (design-review preview only - nothing is sent anywhere).
 */
import { useCallback, useEffect, useState } from 'react';
import { Gavel, X } from 'lucide-react';
import { penaltyDisputeApi, type PenaltyDispute } from '../../api/attendance';
import { useAuthStore } from '../../store/authStore';
import { hasPermission } from '../../lib/permissions';
import { useToast } from '../../context/ToastContext';

const fmt = (iso?: string | null) => (iso ? new Date(iso + (iso.length === 10 ? 'T00:00:00' : '')).toLocaleDateString('en-GB', { day: '2-digit', month: 'short', year: 'numeric' }) : '-');

const btn = (primary: boolean): React.CSSProperties => ({
  display: 'inline-flex', alignItems: 'center', gap: 5, padding: '6px 12px', borderRadius: 6, fontSize: 12, fontWeight: 600, cursor: 'pointer',
  border: primary ? 'none' : '1px solid var(--line2)', background: primary ? 'var(--brand)' : 'var(--raised)', color: primary ? '#fff' : 'var(--txt-mut)',
});
const field: React.CSSProperties = { width: '100%', boxSizing: 'border-box', background: 'var(--raised)', color: 'var(--txt)', border: '1px solid var(--line2)', borderRadius: 6, padding: '8px 10px', fontSize: 12.5, fontFamily: 'inherit', resize: 'vertical' };

/** Design-review mock only: one dispute at each stage. */
export const MOCK_DISPUTE_QUEUE: PenaltyDispute[] = [
  { id: 'md-1', penaltyId: 'p1', employeeName: 'Rahul Verma', employeeCode: 'NF-1042', incidentDate: new Date(Date.now() - 6 * 864e5).toISOString().slice(0, 10), rule: 'Late Arrival', deductionDays: 0.5,
    reason: 'Biometric device was down, I was at my desk from 09:30', evidence: 'IT ticket IT-4821', status: 'PENDING_MANAGER', managerRecommendation: null, managerComment: null, managerActedAt: null, resolutionComment: null, resolvedAt: null, createdAt: new Date().toISOString() },
  { id: 'md-2', penaltyId: 'p2', employeeName: 'Meera Nair', employeeCode: 'NF-1187', incidentDate: new Date(Date.now() - 9 * 864e5).toISOString().slice(0, 10), rule: 'No Attendance', deductionDays: 1,
    reason: 'Was on client site, forgot to mark Work From Home', evidence: 'Client email attached by HR', status: 'PENDING_HR', managerRecommendation: 'REVERSE', managerComment: 'Confirmed with client, genuine', managerActedAt: new Date().toISOString(), resolutionComment: null, resolvedAt: null, createdAt: new Date().toISOString() },
];

export default function PenaltyDisputesPanel({ mock }: { mock?: PenaltyDispute[] }) {
  const token = useAuthStore((s) => s.token);
  const permissions = useAuthStore((s) => s.permissions);
  const { showToast } = useToast();
  const [rows, setRows] = useState<PenaltyDispute[]>(mock ?? []);
  const [acting, setActing] = useState<PenaltyDispute | null>(null);
  const canDecide = mock ? true : hasPermission(permissions, 'attendance', 'EDIT', 'ALL');

  const load = useCallback(() => {
    if (mock || !token) return;
    penaltyDisputeApi.queue(token).then(setRows).catch(() => setRows([]));
  }, [mock, token]);
  useEffect(load, [load]);

  if (rows.length === 0) return null;

  async function submit(d: PenaltyDispute, choice: string, comment: string) {
    if (mock) {
      setRows((r) => r.filter((x) => x.id !== d.id));
      showToast('success', d.status === 'PENDING_MANAGER' ? 'Forwarded to HR (preview - nothing was saved).' : 'Decision recorded (preview - nothing was saved).');
      return;
    }
    if (d.status === 'PENDING_MANAGER') {
      await penaltyDisputeApi.forward(d.id, { recommendation: choice as 'UPHOLD' | 'REVERSE', comment }, token!);
      showToast('success', 'Forwarded to HR.');
    } else {
      await penaltyDisputeApi.decide(d.id, { decision: choice as 'UPHELD' | 'REVERSED', comment }, token!);
      showToast('success', choice === 'REVERSED' ? 'Penalty reversed.' : 'Penalty upheld.');
    }
    load();
  }

  return (
    <div data-testid="dispute-queue" style={{ background: 'var(--panel)', border: '1px solid color-mix(in srgb, var(--warn) 35%, var(--line))', borderRadius: 18, overflow: 'hidden' }}>
      <div style={{ padding: '14px 20px', borderBottom: '1px solid var(--line)', display: 'flex', alignItems: 'center', gap: 10, background: 'color-mix(in srgb, var(--warn) 9%, var(--panel))' }}>
        <Gavel size={16} style={{ color: 'var(--warn)' }} />
        <span style={{ fontSize: 14, fontWeight: 700, color: 'var(--txt)' }}>Penalty disputes to review</span>
        <span style={{ fontSize: 12, color: 'var(--txt-mut)' }}>{rows.length} awaiting you</span>
      </div>
      <div style={{ display: 'flex', flexDirection: 'column' }}>
        {rows.map((d) => {
          const hrStage = d.status === 'PENDING_HR';
          const canAct = hrStage ? canDecide : true;
          return (
            <div key={d.id} data-testid="dispute-item" style={{ padding: '14px 20px', borderBottom: '1px solid var(--line)', display: 'grid', gridTemplateColumns: 'minmax(0,1fr) auto', gap: 12, alignItems: 'start' }}>
              <div style={{ minWidth: 0 }}>
                <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--txt)' }}>
                  {d.employeeName ?? 'Employee'} <span style={{ fontWeight: 500, color: 'var(--txt-dim)' }}>{d.employeeCode}</span>
                  <span style={{ marginLeft: 8, fontSize: 10.5, fontWeight: 700, padding: '2px 7px', borderRadius: 5, color: 'var(--info)', background: 'color-mix(in srgb, var(--info) 14%, var(--raised))' }}>
                    {hrStage ? 'Awaiting HR decision' : 'Awaiting your review'}
                  </span>
                </div>
                <div style={{ fontSize: 12, color: 'var(--txt-mut)', marginTop: 3 }}>
                  {d.rule} · {fmt(d.incidentDate)} · {d.deductionDays ?? '-'} day(s) deducted
                </div>
                <div style={{ fontSize: 12.5, color: 'var(--txt)', marginTop: 8 }}><b>Reason:</b> {d.reason}</div>
                {d.evidence && <div style={{ fontSize: 12.5, color: 'var(--txt)', marginTop: 3 }}><b>Evidence:</b> {d.evidence}</div>}
                {hrStage && d.managerComment && (
                  <div style={{ fontSize: 12, color: 'var(--txt-mut)', marginTop: 6 }}>
                    Manager recommends <b>{d.managerRecommendation === 'REVERSE' ? 'reversing' : 'upholding'}</b>: {d.managerComment}
                  </div>
                )}
              </div>
              {canAct && <button data-testid="dispute-review-btn" onClick={() => setActing(d)} style={btn(true)}>{hrStage ? 'Decide' : 'Review'}</button>}
            </div>
          );
        })}
      </div>
      {acting && <ActModal d={acting} onClose={() => setActing(null)} onSubmit={async (c, t) => { await submit(acting, c, t); setActing(null); }} />}
    </div>
  );
}

function ActModal({ d, onClose, onSubmit }: { d: PenaltyDispute; onClose: () => void; onSubmit: (choice: string, comment: string) => Promise<void> }) {
  const hrStage = d.status === 'PENDING_HR';
  const options = hrStage
    ? [{ v: 'REVERSED', label: 'Reverse the penalty', hint: 'Leave balance is restored and the deduction removed.' }, { v: 'UPHELD', label: 'Uphold the penalty', hint: 'The penalty stands.' }]
    : [{ v: 'REVERSE', label: 'Recommend reversing', hint: 'Sends it to HR recommending the penalty be reversed.' }, { v: 'UPHOLD', label: 'Recommend upholding', hint: 'Sends it to HR recommending the penalty stands.' }];
  const [choice, setChoice] = useState(options[0].v);
  const [comment, setComment] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  async function go() {
    if (!comment.trim()) return;
    setBusy(true); setErr(null);
    try { await onSubmit(choice, comment.trim()); } catch (e) { setErr((e as Error).message || 'Failed'); setBusy(false); }
  }
  return (
    <div role="dialog" aria-modal="true" onClick={onClose} style={{ position: 'fixed', inset: 0, zIndex: 1000, background: 'rgba(0,0,0,.5)', display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 16 }}>
      <div onClick={(e) => e.stopPropagation()} style={{ width: 'min(480px,100%)', background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, padding: 20 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', gap: 10, marginBottom: 12 }}>
          <div>
            <h3 style={{ margin: 0, fontSize: 15, color: 'var(--txt)' }}>{hrStage ? 'Decide penalty dispute' : 'Review penalty dispute'}</h3>
            <p style={{ margin: '4px 0 0', fontSize: 12, color: 'var(--txt-mut)' }}>{d.employeeName} · {d.rule} · {fmt(d.incidentDate)}</p>
          </div>
          <button aria-label="Close" onClick={onClose} style={{ background: 'none', border: 'none', color: 'var(--txt-mut)', cursor: 'pointer' }}><X size={15} /></button>
        </div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8, marginBottom: 12 }}>
          {options.map((o) => (
            <label key={o.v} style={{ display: 'flex', gap: 8, alignItems: 'flex-start', padding: '8px 10px', borderRadius: 8, cursor: 'pointer', border: '1px solid ' + (choice === o.v ? 'var(--brand)' : 'var(--line)'), background: 'var(--raised)' }}>
              <input type="radio" name="dispute-choice" checked={choice === o.v} onChange={() => setChoice(o.v)} style={{ marginTop: 3 }} />
              <span><span style={{ fontSize: 12.5, fontWeight: 600, color: 'var(--txt)' }}>{o.label}</span><br /><span style={{ fontSize: 11.5, color: 'var(--txt-mut)' }}>{o.hint}</span></span>
            </label>
          ))}
        </div>
        <label style={{ display: 'block', fontSize: 11, fontWeight: 700, letterSpacing: '.05em', textTransform: 'uppercase', color: 'var(--txt-dim)', marginBottom: 5 }}>Comment</label>
        <textarea data-testid="dispute-comment" value={comment} onChange={(e) => setComment(e.target.value)} rows={3} maxLength={2000} style={field} />
        {err && <div role="alert" style={{ marginTop: 8, fontSize: 12, color: 'var(--risk)' }}>{err}</div>}
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8, marginTop: 14 }}>
          <button onClick={onClose} style={btn(false)}>Cancel</button>
          <button data-testid="dispute-act-submit" onClick={go} disabled={!comment.trim() || busy} style={{ ...btn(true), opacity: !comment.trim() || busy ? 0.55 : 1 }}>{busy ? 'Saving…' : hrStage ? 'Record decision' : 'Forward to HR'}</button>
        </div>
      </div>
    </div>
  );
}
