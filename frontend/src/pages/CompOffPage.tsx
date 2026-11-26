import { useCallback, useEffect, useMemo, useState } from 'react';
import { CalendarClock } from 'lucide-react';
import PageTitleBlock from '../components/PageTitleBlock';
import { useAuthStore } from '../store/authStore';
import { useToast } from '../context/ToastContext';
import { compOffApi, type CompOffClaim, type CompOffCredit, type MyCompOff } from '../api/compOff';
import { leaveApi, type LeaveRequestRecord } from '../api/leave';

const input: React.CSSProperties = { background: 'var(--shell)', border: '1px solid var(--line2)', borderRadius: 6, padding: '8px 10px', color: 'var(--txt)', fontSize: 13, boxSizing: 'border-box', outline: 'none' };
const label: React.CSSProperties = { display: 'block', fontSize: 11, fontWeight: 600, color: 'var(--txt-mut)', marginBottom: 5, textTransform: 'uppercase', letterSpacing: '.06em' };
const th: React.CSSProperties = { textAlign: 'left', fontSize: 11, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.05em', padding: '8px 10px', borderBottom: '1px solid var(--line2)', whiteSpace: 'nowrap' };
const td: React.CSSProperties = { padding: '9px 10px', fontSize: 13, color: 'var(--txt)', borderBottom: '1px solid var(--line2)', whiteSpace: 'nowrap' };
const btn: React.CSSProperties = { background: 'var(--accent, #3b82f6)', color: '#fff', border: 'none', borderRadius: 6, padding: '8px 14px', fontSize: 13, fontWeight: 600, cursor: 'pointer' };
const btnGhost: React.CSSProperties = { ...btn, background: 'transparent', color: 'var(--txt)', border: '1px solid var(--line2)' };
const card: React.CSSProperties = { border: '1px solid var(--line2)', borderRadius: 10, padding: 16, background: 'var(--surface, transparent)' };

const COLOR: Record<string, string> = {
  AVAILABLE: '#3FB27F', APPROVED: '#3FB27F', PENDING: '#E0A93B', PENDING_APPROVAL: '#E0A93B',
  REJECTED: '#E5534B', EXPIRED: '#8A8F98', OT: '#5B8DEF', COMP_OFF: '#3FB27F',
};
const DAY_LABEL = { WEEKDAY: 'Working day', WEEKLY_OFF: 'Weekly off', HOLIDAY: 'Holiday' } as const;
const EVENT_LABEL: Record<string, string> = {
  CREDIT_GENERATED: 'Credit generated', CREDIT_APPROVED: 'Credit approved', CREDIT_REJECTED: 'Credit rejected',
  CREDIT_UTILIZED: 'Used for leave', CREDIT_EXPIRED: 'Expired', ADJUSTED_ADD: 'Adjusted (added)',
  ADJUSTED_DEDUCT: 'Adjusted (deducted)', OT_SELECTED: 'Overtime selected',
};

const iso = (d: Date) => d.toISOString().slice(0, 10);
const days = (n: number) => Number(n.toFixed(2)).toString();
const hrs = (m: number) => (m / 60).toFixed(1);

function Badge({ text }: { text: string }) {
  const c = COLOR[text] ?? 'var(--txt-mut)';
  return <span style={{ color: c, border: `1px solid ${c}`, borderRadius: 10, padding: '2px 8px', fontSize: 11, fontWeight: 600 }}>{text.replace('_', ' ')}</span>;
}

function Stat({ title, value, hint }: { title: string; value: number; hint?: string }) {
  return (
    <div style={{ ...card, minWidth: 130, flex: '1 1 130px' }}>
      <div style={{ fontSize: 11, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.05em' }}>{title}</div>
      <div style={{ fontSize: 26, fontWeight: 700, color: 'var(--txt)', marginTop: 4 }}>{days(value)}</div>
      {hint && <div style={{ fontSize: 11, color: 'var(--txt-mut)', marginTop: 2 }}>{hint}</div>}
    </div>
  );
}

export default function CompOffPage() {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const [data, setData] = useState<MyCompOff | null>(null);
  const [requests, setRequests] = useState<LeaveRequestRecord[]>([]);
  const [busy, setBusy] = useState<string | null>(null);

  const fail = useCallback((e: unknown) => showToast('error', e instanceof Error ? e.message : 'Something went wrong'), [showToast]);

  const load = useCallback(() => {
    Promise.all([compOffApi.mine(token), leaveApi.listMine(token)])
      .then(([mine, mineRequests]) => { setData(mine); setRequests(mineRequests.filter(r => r.leaveTypeCode === 'COMP_OFF')); })
      .catch(fail);
  }, [token, fail]);
  useEffect(load, [load]);

  const choose = (claim: CompOffClaim, choice: 'OT' | 'COMP_OFF') => {
    setBusy(claim.id);
    compOffApi.choose(token, claim.id, choice)
      .then(() => { showToast('success', choice === 'OT' ? 'Overtime selected' : 'Comp-Off credited'); load(); })
      .catch(fail).finally(() => setBusy(null));
  };

  const header = (
    <PageTitleBlock card icon={CalendarClock} style={{ marginBottom: 22 }}>
      <h1 style={{ fontFamily: 'Inter, sans-serif', fontSize: 20, fontWeight: 700, color: 'var(--txt)', margin: 0 }}>My Comp-Off</h1>
      <p style={{ fontSize: 13, color: 'var(--txt-mut)', marginTop: 4 }}>
        Extra hours on weekly offs and holidays can earn compensatory leave. See your balance, settle eligible days and apply for leave.
      </p>
    </PageTitleBlock>
  );

  if (!data) return <div>{header}<p style={{ color: 'var(--txt-mut)' }}>Loading…</p></div>;

  if (!data.policy.compOffEnabled && data.credits.length === 0) {
    return <div>{header}<div style={card}>Your company pays extra working hours as overtime — Comp-Off is not in use.</div></div>;
  }

  const pendingClaims = data.claims.filter(c => c.choice === 'PENDING');
  const b = data.balance;

  return (
    <div>
      {header}

      <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', marginBottom: 22 }}>
        <Stat title="Available" value={b.available} hint="days you can apply for" />
        <Stat title="Earned" value={b.earned} />
        <Stat title="Utilized" value={b.utilized} />
        <Stat title="Pending" value={b.pending} hint="awaiting approval" />
        <Stat title="Expired" value={b.expired} />
      </div>

      {pendingClaims.length > 0 && (
        <Section title="Choose how to be compensated">
          {data.policy.overtimeEnabled && data.policy.compOffEnabled
            ? <p style={note}>You worked extra on these days. Pick overtime pay or Comp-Off for each — the choice is final.</p>
            : null}
          <table style={{ borderCollapse: 'collapse', width: '100%' }}>
            <thead><tr>{['Date', 'Day', 'Worked', 'Eligible', 'Comp-Off credit', ''].map(h => <th key={h} style={th}>{h}</th>)}</tr></thead>
            <tbody>{pendingClaims.map(c => (
              <tr key={c.id}>
                <td style={td}>{c.workDate}</td><td style={td}>{DAY_LABEL[c.dayType]}</td>
                <td style={td}>{hrs(c.workedMinutes)} h</td><td style={td}>{hrs(c.eligibleMinutes)} h</td>
                <td style={td}>{days(c.creditDays)} day</td>
                <td style={{ ...td, display: 'flex', gap: 8 }}>
                  {data.policy.compOffEnabled && <button style={btn} disabled={busy === c.id} onClick={() => choose(c, 'COMP_OFF')}>Take Comp-Off</button>}
                  {data.policy.overtimeEnabled && <button style={btnGhost} disabled={busy === c.id} onClick={() => choose(c, 'OT')}>Take overtime</button>}
                </td>
              </tr>
            ))}</tbody>
          </table>
        </Section>
      )}

      {data.policy.compOffEnabled && (
        <Section title="Apply for Comp-Off leave">
          <ApplyForm token={token} available={b.available} allowPartial={data.policy.allowPartialDay} onDone={load} fail={fail} />
          <RequestsTable rows={requests} />
        </Section>
      )}

      <Section title="Comp-Off credits">
        <CreditsTable credits={data.credits} />
      </Section>

      <Section title="History">
        {data.history.length === 0 ? <p style={note}>Nothing yet.</p> : (
          <table style={{ borderCollapse: 'collapse', width: '100%' }}>
            <thead><tr>{['When', 'Event', 'Days', 'Note', 'By'].map(h => <th key={h} style={th}>{h}</th>)}</tr></thead>
            <tbody>{data.history.map(h => (
              <tr key={h.id}>
                <td style={td}>{h.createdAt.replace('T', ' ').slice(0, 16)}</td>
                <td style={td}>{EVENT_LABEL[h.event] ?? h.event}</td>
                <td style={td}>{h.days == null ? '—' : days(h.days)}</td>
                <td style={{ ...td, whiteSpace: 'normal' }}>{h.note ?? '—'}</td>
                <td style={td}>{h.actorName ?? '—'}</td>
              </tr>
            ))}</tbody>
          </table>
        )}
      </Section>
    </div>
  );
}

const note: React.CSSProperties = { fontSize: 13, color: 'var(--txt-mut)', margin: '0 0 10px' };

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section style={{ marginBottom: 26, overflowX: 'auto' }}>
      <h2 style={{ fontSize: 15, fontWeight: 700, color: 'var(--txt)', margin: '0 0 10px' }}>{title}</h2>
      {children}
    </section>
  );
}

function CreditsTable({ credits }: { credits: CompOffCredit[] }) {
  if (credits.length === 0) return <p style={note}>No Comp-Off credits yet.</p>;
  return (
    <table style={{ borderCollapse: 'collapse', width: '100%' }}>
      <thead><tr>{['Earned on', 'Worked on', 'Source', 'Credit', 'Used', 'Expired', 'Remaining', 'Valid until', 'Status'].map(h => <th key={h} style={th}>{h}</th>)}</tr></thead>
      <tbody>{credits.map(c => (
        <tr key={c.id}>
          <td style={td}>{c.earnedOn}</td><td style={td}>{c.workDate ?? '—'}</td><td style={td}>{c.source === 'MANUAL' ? 'HR adjustment' : 'Worked'}</td>
          <td style={td}>{days(c.creditDays)}</td><td style={td}>{days(c.usedDays + c.deductedDays)}</td><td style={td}>{days(c.expiredDays)}</td>
          <td style={td}>{c.status === 'AVAILABLE' ? days(c.remainingDays) : '—'}</td><td style={td}>{c.expiresOn}</td>
          <td style={td}><Badge text={c.status} /></td>
        </tr>
      ))}</tbody>
    </table>
  );
}

function RequestsTable({ rows }: { rows: LeaveRequestRecord[] }) {
  if (rows.length === 0) return null;
  return (
    <table style={{ borderCollapse: 'collapse', width: '100%', marginTop: 14 }}>
      <thead><tr>{['From', 'To', 'Days', 'Reason', 'Status', 'Decision'].map(h => <th key={h} style={th}>{h}</th>)}</tr></thead>
      <tbody>{rows.map(r => (
        <tr key={r.id}>
          <td style={td}>{r.startDate}</td><td style={td}>{r.endDate}</td>
          <td style={td}>{days(Number(r.totalDays))}{r.halfDay ? ' (half)' : ''}</td>
          <td style={{ ...td, whiteSpace: 'normal' }}>{r.employeeReason}</td>
          <td style={td}><Badge text={r.status} /></td>
          <td style={{ ...td, whiteSpace: 'normal' }}>{r.decisionReason ?? (r.decidedByName ? `By ${r.decidedByName}` : '—')}</td>
        </tr>
      ))}</tbody>
    </table>
  );
}

function ApplyForm({ token, available, allowPartial, onDone, fail }:
  { token: string; available: number; allowPartial: boolean; onDone: () => void; fail: (e: unknown) => void }) {
  const { showToast } = useToast();
  const today = iso(new Date());
  const [start, setStart] = useState(today);
  const [end, setEnd] = useState(today);
  const [half, setHalf] = useState(false);
  const [session, setSession] = useState<'FIRST_HALF' | 'SECOND_HALF'>('FIRST_HALF');
  const [reason, setReason] = useState('');
  const [saving, setSaving] = useState(false);

  const requested = useMemo(() => half ? 0.5 : Math.max(0, Math.round((new Date(end).getTime() - new Date(start).getTime()) / 86400000) + 1), [half, start, end]);
  const exceeds = requested > available;

  const submit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!reason.trim()) { showToast('error', 'A reason is required'); return; }
    setSaving(true);
    leaveApi.submit({
      leaveTypeCode: 'COMP_OFF', startDate: start, endDate: half ? start : end, halfDay: half,
      halfDaySession: half ? session : undefined, reason: reason.trim(),
    }, token)
      .then(() => { showToast('success', 'Comp-Off leave submitted for approval'); setReason(''); onDone(); })
      .catch(fail).finally(() => setSaving(false));
  };

  return (
    <form onSubmit={submit} style={{ display: 'flex', flexWrap: 'wrap', gap: 14, alignItems: 'end' }}>
      <div><label style={label}>From</label><input type="date" style={input} value={start} min={today} onChange={e => { setStart(e.target.value); if (e.target.value > end) setEnd(e.target.value); }} /></div>
      {!half && <div><label style={label}>To</label><input type="date" style={input} value={end} min={start} onChange={e => setEnd(e.target.value)} /></div>}
      {allowPartial && (
        <div><label style={label}>Duration</label>
          <select style={input} value={half ? 'half' : 'full'} onChange={e => setHalf(e.target.value === 'half')}>
            <option value="full">Full day(s)</option><option value="half">Half day</option>
          </select>
        </div>
      )}
      {half && (
        <div><label style={label}>Session</label>
          <select style={input} value={session} onChange={e => setSession(e.target.value as typeof session)}>
            <option value="FIRST_HALF">First half</option><option value="SECOND_HALF">Second half</option>
          </select>
        </div>
      )}
      <div style={{ flex: '1 1 220px' }}><label style={label}>Reason</label><input style={{ ...input, width: '100%' }} value={reason} onChange={e => setReason(e.target.value)} /></div>
      <button type="submit" style={btn} disabled={saving || exceeds || requested === 0}>Request {days(requested)} day(s)</button>
      {exceeds && <span style={{ fontSize: 12, color: '#E5534B' }}>You have {days(available)} day(s) available.</span>}
    </form>
  );
}
