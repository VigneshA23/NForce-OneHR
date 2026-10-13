/**
 * MOCK — NOT CONNECTED TO ANY BACKEND.
 * Preview stand-ins for the five manager-tool tabs of the real My Team page (Efforts / Punctuality,
 * Negligence, Regularize & Cancel Penalties, Employee Assignments, Reports). The real tabs live in
 * MyTeamPage.tsx, call the live APIs, and are NOT part of this redesign; these mirror their layout
 * and interactions (filters, selection, bulk actions, report cards) over local mock data only, so
 * the whole page can be reviewed without logging in.
 */
import { useMemo, useState, type ReactNode } from 'react';
import { Search, X } from 'lucide-react';
import type { AvatarRenderer } from './MyTeamRedesign';

export interface TabPerson { userId: string; fullName: string; designation: string; department: string }
type Toast = (m: string) => void;

const panel: React.CSSProperties = { background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, overflow: 'hidden', boxShadow: '0 1px 2px rgba(16,24,40,.05)' };
const head: React.CSSProperties = { padding: '14px 18px', borderBottom: '1px solid var(--line)', display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 10, flexWrap: 'wrap' };
const title: React.CSSProperties = { fontWeight: 700, fontSize: 14, color: 'var(--txt)' };
const count: React.CSSProperties = { fontSize: 11, fontWeight: 700, color: 'var(--txt-mut)', background: 'var(--raised2)', padding: '2px 8px', borderRadius: 20 };
const field: React.CSSProperties = { background: 'var(--shell)', border: '1px solid var(--line2)', borderRadius: 7, padding: '7px 10px', color: 'var(--txt)', fontSize: 12.5, fontFamily: 'inherit' };
const th: React.CSSProperties = { padding: '8px 12px', textAlign: 'left', fontSize: 10.5, fontWeight: 700, color: 'var(--txt-dim)', textTransform: 'uppercase', borderBottom: '1px solid var(--line)', whiteSpace: 'nowrap', background: 'var(--shell)' };
const td: React.CSSProperties = { padding: '9px 12px', fontSize: 12.5, color: 'var(--txt-mut)', borderBottom: '1px solid var(--line)', whiteSpace: 'nowrap' };
const note: React.CSSProperties = { padding: '10px 18px', fontSize: 11, color: 'var(--txt-dim)', borderTop: '1px solid var(--line)' };

function seeded(seed: number) {
  let t = seed + 0x6d2b79f5;
  return () => { t = Math.imul(t ^ (t >>> 15), t | 1); t ^= t + Math.imul(t ^ (t >>> 7), t | 61); return ((t ^ (t >>> 14)) >>> 0) / 4294967296; };
}

function Btn({ children, onClick, disabled, primary }: { children: ReactNode; onClick?: () => void; disabled?: boolean; primary?: boolean }) {
  return (
    <button type="button" onClick={onClick} disabled={disabled} style={{
      fontSize: 12.5, fontWeight: 600, padding: '7px 14px', borderRadius: 7, fontFamily: 'inherit', cursor: disabled ? 'not-allowed' : 'pointer', opacity: disabled ? 0.5 : 1,
      border: `1px solid ${primary ? 'var(--brand)' : 'var(--line2)'}`, background: primary ? 'var(--brand)' : 'var(--raised)', color: primary ? '#fff' : 'var(--txt-mut)',
    }}>{children}</button>
  );
}

function Modal({ title: t, onClose, children }: { title: string; onClose: () => void; children: ReactNode }) {
  return (
    <div role="dialog" aria-modal="true" aria-label={t} onClick={onClose} style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,.55)', display: 'grid', placeItems: 'center', zIndex: 500, padding: 16 }}>
      <div onClick={e => e.stopPropagation()} style={{ ...panel, width: 'min(94vw, 440px)', maxHeight: '90vh', overflowY: 'auto' }}>
        <div style={{ ...head, fontWeight: 700 }}><span style={title}>{t}</span><button type="button" aria-label="Close" onClick={onClose} style={{ border: 'none', background: 'none', cursor: 'pointer', color: 'var(--txt-mut)' }}><X size={16} /></button></div>
        <div style={{ padding: 18, display: 'flex', flexDirection: 'column', gap: 12 }}>{children}</div>
      </div>
    </div>
  );
}

function DateRange({ from, to, setFrom, setTo }: { from: string; to: string; setFrom: (v: string) => void; setTo: (v: string) => void }) {
  return (
    <div style={{ display: 'flex', gap: 8, alignItems: 'center', padding: '12px 18px', borderBottom: '1px solid var(--line)', flexWrap: 'wrap', fontSize: 12, color: 'var(--txt-mut)' }}>
      From <input type="date" value={from} onChange={e => setFrom(e.target.value)} style={field} />
      To <input type="date" value={to} onChange={e => setTo(e.target.value)} style={field} />
    </div>
  );
}

function PersonCell({ p, avatar, sub }: { p: TabPerson; avatar: AvatarRenderer; sub?: string }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 10, minWidth: 0 }}>
      {avatar(p.userId, p.fullName, 30)}
      <div style={{ minWidth: 0 }}>
        <div style={{ fontSize: 12.5, fontWeight: 600, color: 'var(--txt)' }}>{p.fullName}</div>
        <div style={{ fontSize: 11, color: 'var(--txt-mut)' }}>{sub ?? p.designation}</div>
      </div>
    </div>
  );
}

function Bar({ pct, color }: { pct: number; color: string }) {
  return <div style={{ height: 6, borderRadius: 4, background: 'var(--raised2)', overflow: 'hidden' }}><div style={{ height: '100%', width: `${Math.min(100, pct)}%`, background: color, borderRadius: 4 }} /></div>;
}

function Kpi({ label, value, note: n, color }: { label: string; value: ReactNode; note: string; color: string }) {
  return (
    <div style={{ ...panel, padding: '14px 16px' }}>
      <div style={{ fontSize: 11, fontWeight: 700, textTransform: 'uppercase', letterSpacing: '.05em', color: 'var(--txt-mut)', display: 'flex', alignItems: 'center', gap: 7 }}>
        <span style={{ width: 8, height: 8, borderRadius: '50%', background: color }} />{label}
      </div>
      <div style={{ fontSize: 26, fontWeight: 800, color: 'var(--txt)', marginTop: 8, lineHeight: 1 }}>{value}</div>
      <div style={{ fontSize: 11.5, color: 'var(--txt-dim)', marginTop: 4 }}>{n}</div>
    </div>
  );
}

const iso = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
function useRange(days: number) {
  const [to, setTo] = useState(() => iso(new Date()));
  const [from, setFrom] = useState(() => { const d = new Date(); d.setDate(d.getDate() - (days - 1)); return iso(d); });
  return { from, to, setFrom, setTo };
}
const shortDate = (s: string) => new Date(s + 'T00:00:00').toLocaleDateString('en-IN', { day: 'numeric', month: 'short' });
/** A stable pseudo-random factor that changes with the chosen range, so changing dates visibly updates the mock. */
const rangeSeed = (from: string, to: string) => [...(from + to)].reduce((a, c) => a + c.charCodeAt(0), 0);

/* ══ Efforts / Punctuality ══ */
export function EffortsTab({ people, avatar }: { people: TabPerson[]; avatar: AvatarRenderer }) {
  const r = useRange(7);
  const rows = useMemo(() => {
    const rnd = seeded(rangeSeed(r.from, r.to));
    return people.map(p => ({ p, avg: 6.2 + rnd() * 3.4, onTime: Math.round(55 + rnd() * 45), expected: 5 }))
      .map(x => ({ ...x, worked: x.avg * x.expected })).sort((a, b) => b.avg - a.avg);
  }, [people, r.from, r.to]);
  const daily = useMemo(() => { const rnd = seeded(rangeSeed(r.to, r.from)); return Array.from({ length: 7 }, (_, i) => ({ d: i, n: Math.max(0, Math.round(people.length * (0.6 + rnd() * 0.4))) })); }, [people.length, r.from, r.to]);
  const max = Math.max(1, ...daily.map(d => d.n));
  const avgOn = daily.reduce((a, d) => a + d.n, 0) / daily.length;
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <div style={panel}>
        <div style={head}><span style={title}>Avg. Work Hours Leaderboard</span><span style={count}>{shortDate(r.from)} – {shortDate(r.to)}</span></div>
        <DateRange {...r} />
        {rows.map(({ p, avg, worked, expected }) => (
          <div key={p.userId} style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '11px 18px', borderBottom: '1px solid var(--line)', flexWrap: 'wrap' }}>
            <div style={{ minWidth: 190 }}><PersonCell p={p} avatar={avatar} /></div>
            <div style={{ flex: 1, minWidth: 100 }}><Bar pct={avg * 10} color="var(--brand-bright)" /></div>
            <div style={{ fontSize: 11.5, color: 'var(--txt-mut)', whiteSpace: 'nowrap' }}>Avg. {avg.toFixed(1)} hrs/day · {worked.toFixed(1)}/{(expected * 8).toFixed(0)} hrs worked</div>
          </div>
        ))}
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(160px, 1fr))', gap: 12 }}>
        <Kpi label="Avg. on time / day" value={avgOn.toFixed(1)} note="employees, this range" color="var(--ok)" />
        <Kpi label="Min. on time / day" value={Math.min(...daily.map(d => d.n))} note="lowest single day" color="var(--risk)" />
        <Kpi label="Max. on time / day" value={max} note="highest single day" color="var(--brand-bright)" />
      </div>
      <div style={panel}>
        <div style={head}><span style={title}>On-Time Leaderboard</span><span style={count}>{shortDate(r.from)} – {shortDate(r.to)}</span></div>
        <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0,1fr) minmax(220px, 320px)' }} className="mt-twocol">
          <div>
            {[...rows].sort((a, b) => b.onTime - a.onTime).map(({ p, onTime, expected }) => (
              <div key={p.userId} style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '11px 18px', borderBottom: '1px solid var(--line)', flexWrap: 'wrap' }}>
                <div style={{ minWidth: 190 }}><PersonCell p={p} avatar={avatar} /></div>
                <div style={{ flex: 1, minWidth: 100 }}><Bar pct={onTime} color="var(--ok)" /></div>
                <div style={{ fontSize: 11.5, color: 'var(--txt-mut)', whiteSpace: 'nowrap' }}>{onTime}% on time · {Math.round(onTime / 100 * expected)}/{expected} days</div>
              </div>
            ))}
          </div>
          <div style={{ borderLeft: '1px solid var(--line)', display: 'flex', alignItems: 'flex-end', gap: 6, height: 150, padding: '14px 10px 6px' }}>
            {daily.map(d => (
              <div key={d.d} title={`${d.n} on time`} style={{ flex: 1, textAlign: 'center', fontSize: 10, color: 'var(--txt-dim)' }}>
                <div style={{ height: Math.max(4, (d.n / max) * 100), background: 'var(--ok)', borderRadius: 4 }} />{d.n}
              </div>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}

/* ══ Negligence ══ */
export function NegligenceTab({ people, avatar }: { people: TabPerson[]; avatar: AvatarRenderer }) {
  const r = useRange(30);
  const late = useMemo(() => {
    const rnd = seeded(rangeSeed(r.from, r.to) + 3);
    return people.map(p => { const active = 20 + Math.round(rnd() * 2); const l = Math.round(rnd() * 7); return { p, active, l, pct: (l / active) * 100 }; }).filter(x => x.l > 0).sort((a, b) => b.pct - a.pct);
  }, [people, r.from, r.to]);
  const least = useMemo(() => { const rnd = seeded(rangeSeed(r.to, r.from) + 5); return people.map(p => ({ p, hrs: 5 + rnd() * 3.5 })).sort((a, b) => a.hrs - b.hrs).slice(0, 6); }, [people, r.from, r.to]);
  const buckets = [['< 4 hrs', 4], ['4–6 hrs', 9], ['6–8 hrs', 32], ['8–9 hrs', 41], ['9+ hrs', 14]] as const;
  const breaks = useMemo(() => { const rnd = seeded(rangeSeed(r.from, r.to) + 9); return people.map(p => ({ p, n: 2 + rnd() * 4 })).sort((a, b) => b.n - a.n).slice(0, 6); }, [people, r.from, r.to]);
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <div style={panel}><DateRange {...r} /></div>
      <div style={panel}>
        <div style={head}><span style={title}>Late Arrivals</span><span style={count}>{shortDate(r.from)} – {shortDate(r.to)}</span></div>
        {late.map(({ p, active, l, pct }) => (
          <div key={p.userId} style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '11px 18px', borderBottom: '1px solid var(--line)' }}>
            <div style={{ flex: 1 }}><PersonCell p={p} avatar={avatar} /></div>
            <div style={{ textAlign: 'right' }}><div style={{ fontSize: 12, color: 'var(--txt-mut)' }}>{l} / {active} Late Arrivals</div><div style={{ fontSize: 13, fontWeight: 700, color: 'var(--risk)' }}>{pct.toFixed(0)}%</div></div>
          </div>
        ))}
      </div>
      <div style={panel}>
        <div style={head}><span style={title}>Least Hours Worked</span></div>
        <div className="mt-twocol" style={{ display: 'grid', gridTemplateColumns: 'minmax(0,1fr) minmax(220px, 320px)' }}>
          <div>{least.map(({ p, hrs }) => (
            <div key={p.userId} style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '11px 18px', borderBottom: '1px solid var(--line)' }}>
              <div style={{ flex: 1 }}><PersonCell p={p} avatar={avatar} /></div>
              <div style={{ fontSize: 12.5, fontWeight: 700, color: 'var(--txt)' }}>{hrs.toFixed(1)} hrs/day</div>
            </div>
          ))}</div>
          <div style={{ borderLeft: '1px solid var(--line)', padding: 18, display: 'flex', flexDirection: 'column', gap: 9 }}>
            <div style={{ fontSize: 11, fontWeight: 700, color: 'var(--txt-dim)', textTransform: 'uppercase' }}>Hours per day (all team-days)</div>
            {buckets.map(([l, pct]) => (
              <div key={l} style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 11.5, color: 'var(--txt-mut)' }}>
                <span style={{ width: 54 }}>{l}</span><div style={{ flex: 1 }}><Bar pct={pct * 2} color="var(--info)" /></div><b style={{ color: 'var(--txt)', width: 30, textAlign: 'right' }}>{pct}%</b>
              </div>
            ))}
          </div>
        </div>
      </div>
      <div style={panel}>
        <div style={head}><span style={title}>Frequent Breaks</span></div>
        {breaks.map(({ p, n }) => (
          <div key={p.userId} style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '11px 18px', borderBottom: '1px solid var(--line)' }}>
            <div style={{ flex: 1 }}><PersonCell p={p} avatar={avatar} /></div>
            <div style={{ fontSize: 12.5, fontWeight: 700, color: 'var(--txt)' }}>{n.toFixed(1)} breaks/day</div>
          </div>
        ))}
      </div>
    </div>
  );
}

/* ══ Regularize & Cancel Penalties ══ */
type PStatus = 'PENDING_REVIEW' | 'APPLIED' | 'CANCELLED' | 'REVERSED' | 'NOT_PENALIZED';
const P_LABEL: Record<PStatus, string> = { PENDING_REVIEW: 'Pending Review', APPLIED: 'Applied', CANCELLED: 'Cancelled', REVERSED: 'Reversed', NOT_PENALIZED: 'Not Penalized' };
const P_COLOR: Record<PStatus, string> = { PENDING_REVIEW: 'var(--warn)', APPLIED: 'var(--risk)', CANCELLED: 'var(--txt-dim)', REVERSED: 'var(--info)', NOT_PENALIZED: 'var(--ok)' };
const D_LABEL: Record<string, string> = { NO_ATTENDANCE: 'No attendance', WORK_HOURS_SHORTAGE: 'Work hours shortage', LATE_ARRIVAL: 'Late arrival', EARLY_DEPARTURE: 'Early departure', MISSING_PUNCH: 'Missing punch' };

export function PenaltiesTab({ people, avatar, toast }: { people: TabPerson[]; avatar: AvatarRenderer; toast: Toast }) {
  const dis = Object.keys(D_LABEL);
  const [rows, setRows] = useState(() => {
    const rnd = seeded(41);
    const sts: PStatus[] = ['PENDING_REVIEW', 'APPLIED', 'PENDING_REVIEW', 'CANCELLED', 'REVERSED', 'PENDING_REVIEW'];
    return people.flatMap((p, i) => (rnd() < 0.75 ? [{ id: `${p.userId}-a`, p, date: `2026-09-${String(3 + ((i * 3) % 24)).padStart(2, '0')}`, type: dis[Math.floor(rnd() * dis.length)], status: sts[Math.floor(rnd() * sts.length)], amount: `${1 + Math.floor(rnd() * 2)} day${rnd() < 0.5 ? '' : 's'} LOP` }] : []));
  });
  const [status, setStatus] = useState(''); const [type, setType] = useState(''); const [dept, setDept] = useState(''); const [q, setQ] = useState('');
  const [sel, setSel] = useState<Set<string>>(new Set());
  const [open, setOpen] = useState(false); const [reason, setReason] = useState('');
  const depts = [...new Set(people.map(p => p.department))];
  const shown = rows.filter(r => (!status || r.status === status) && (!type || r.type === type) && (!dept || r.p.department === dept) && (!q || r.p.fullName.toLowerCase().includes(q.toLowerCase())));
  const cancellable = shown.filter(r => r.status === 'PENDING_REVIEW' || r.status === 'APPLIED');
  const selectedRows = rows.filter(r => sel.has(r.id));
  function cancel() {
    const ids = new Set(selectedRows.filter(r => r.status === 'PENDING_REVIEW' || r.status === 'APPLIED').map(r => r.id));
    setRows(rs => rs.map(r => (ids.has(r.id) ? { ...r, status: 'CANCELLED' } : r)));
    setSel(new Set()); setOpen(false); setReason(''); toast(`${ids.size} penalt${ids.size === 1 ? 'y' : 'ies'} cancelled (preview only)`);
  }
  return (
    <div style={panel}>
      <div style={head}><span style={title}>Regularize &amp; Cancel Penalties</span><span style={count}>{shown.length} records</span></div>
      <div style={{ display: 'flex', gap: 8, padding: '12px 18px', borderBottom: '1px solid var(--line)', flexWrap: 'wrap', alignItems: 'center' }}>
        <select style={field} value={status} onChange={e => setStatus(e.target.value)} aria-label="Status"><option value="">All statuses</option>{(Object.keys(P_LABEL) as PStatus[]).map(s => <option key={s} value={s}>{P_LABEL[s]}</option>)}</select>
        <select style={field} value={type} onChange={e => setType(e.target.value)} aria-label="Discrepancy"><option value="">All discrepancies</option>{dis.map(d => <option key={d} value={d}>{D_LABEL[d]}</option>)}</select>
        <select style={field} value={dept} onChange={e => setDept(e.target.value)} aria-label="Department"><option value="">All departments</option>{depts.map(d => <option key={d}>{d}</option>)}</select>
        <label style={{ ...field, display: 'flex', alignItems: 'center', gap: 6, flex: '1 1 160px', maxWidth: 260 }}><Search size={13} /><input value={q} onChange={e => setQ(e.target.value)} placeholder="Search employee…" style={{ flex: 1, minWidth: 0, background: 'none', border: 'none', outline: 'none', color: 'var(--txt)', fontSize: 12.5 }} /></label>
        <Btn disabled={sel.size === 0} onClick={() => setOpen(true)}>Cancel Penalty</Btn>
      </div>
      <div style={{ overflowX: 'auto' }}>
        <table style={{ borderCollapse: 'collapse', width: '100%' }}>
          <thead><tr>
            <th style={th}><input type="checkbox" aria-label="Select all" checked={cancellable.length > 0 && cancellable.every(r => sel.has(r.id))} onChange={e => setSel(e.target.checked ? new Set(cancellable.map(r => r.id)) : new Set())} /></th>
            {['Employee', 'Date', 'Discrepancy', 'Penalty', 'Status'].map(h => <th key={h} style={th}>{h}</th>)}
          </tr></thead>
          <tbody>
            {shown.length === 0 ? <tr><td colSpan={6} style={{ ...td, textAlign: 'center', padding: 24 }}>No penalties match these filters.</td></tr> : shown.map(r => (
              <tr key={r.id}>
                <td style={td}><input type="checkbox" aria-label={`Select ${r.p.fullName}`} disabled={r.status !== 'PENDING_REVIEW' && r.status !== 'APPLIED'} checked={sel.has(r.id)} onChange={e => setSel(s => { const n = new Set(s); if (e.target.checked) n.add(r.id); else n.delete(r.id); return n; })} /></td>
                <td style={td}><PersonCell p={r.p} avatar={avatar} sub={r.p.department} /></td>
                <td style={td}>{shortDate(r.date)}</td><td style={td}>{D_LABEL[r.type]}</td><td style={td}>{r.amount}</td>
                <td style={td}><span style={{ fontSize: 11, fontWeight: 700, padding: '3px 9px', borderRadius: 20, color: P_COLOR[r.status], background: `color-mix(in srgb, ${P_COLOR[r.status]} 14%, transparent)` }}>{P_LABEL[r.status]}</span></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div style={note}>Mock data. Select Pending Review / Applied rows, then Cancel Penalty.</div>
      {open && (
        <Modal title={`Cancel ${sel.size} penalt${sel.size === 1 ? 'y' : 'ies'}`} onClose={() => setOpen(false)}>
          <textarea value={reason} onChange={e => setReason(e.target.value)} placeholder="Why are these penalties being cancelled?" rows={3} style={{ ...field, resize: 'vertical' }} />
          <div style={{ display: 'flex', gap: 8 }}><Btn onClick={() => setOpen(false)}>Back</Btn><Btn primary disabled={!reason.trim()} onClick={cancel}>Confirm cancel</Btn></div>
        </Modal>
      )}
    </div>
  );
}

/* ══ Employee Assignments ══ */
const SHIFTS = ['General (9:00 AM – 6:00 PM)', 'Morning (7:00 AM – 4:00 PM)', 'Late (11:00 AM – 8:00 PM)'];
const OFFS = ['Sat–Sun', 'Fri–Sat', 'Sun only'];
const POLICIES = ['Standard Penalisation', 'Relaxed Penalisation', 'No Penalisation'];
export function AssignmentsTab({ people, avatar, toast }: { people: TabPerson[]; avatar: AvatarRenderer; toast: Toast }) {
  const [rows, setRows] = useState(() => people.map((p, i) => ({ p, shift: SHIFTS[i % 2], off: OFFS[0], policy: POLICIES[i % 3 === 2 ? 1 : 0] })));
  const [q, setQ] = useState(''); const [dept, setDept] = useState('');
  const [sel, setSel] = useState<Set<string>>(new Set());
  const [bulk, setBulk] = useState<null | 'shift' | 'off' | 'policy'>(null); const [val, setVal] = useState('');
  const shown = rows.filter(r => (!dept || r.p.department === dept) && (!q || r.p.fullName.toLowerCase().includes(q.toLowerCase())));
  const opts = bulk === 'shift' ? SHIFTS : bulk === 'off' ? OFFS : POLICIES;
  const label = bulk === 'shift' ? 'Update Shift' : bulk === 'off' ? 'Update Weekly Off' : 'Update Penalisation Policy';
  function apply() {
    setRows(rs => rs.map(r => (sel.has(r.p.userId) ? { ...r, ...(bulk === 'shift' ? { shift: val } : bulk === 'off' ? { off: val } : { policy: val }) } : r)));
    toast(`${label}: ${sel.size} employee${sel.size === 1 ? '' : 's'} updated (preview only)`); setBulk(null); setVal(''); setSel(new Set());
  }
  return (
    <div style={panel}>
      <div style={head}><span style={title}>Time Assignments</span><span style={count}>{shown.length} employees</span></div>
      <div style={{ display: 'flex', gap: 8, padding: '12px 18px', borderBottom: '1px solid var(--line)', flexWrap: 'wrap', alignItems: 'center' }}>
        <select style={field} value={dept} onChange={e => setDept(e.target.value)} aria-label="Department"><option value="">All departments</option>{[...new Set(people.map(p => p.department))].map(d => <option key={d}>{d}</option>)}</select>
        <label style={{ ...field, display: 'flex', alignItems: 'center', gap: 6, flex: '1 1 160px', maxWidth: 260 }}><Search size={13} /><input value={q} onChange={e => setQ(e.target.value)} placeholder="Search employee…" style={{ flex: 1, minWidth: 0, background: 'none', border: 'none', outline: 'none', color: 'var(--txt)', fontSize: 12.5 }} /></label>
        <Btn disabled={sel.size === 0} onClick={() => { setBulk('shift'); setVal(''); }}>Update Shift</Btn>
        <Btn disabled={sel.size === 0} onClick={() => { setBulk('off'); setVal(''); }}>Update Weekly Off</Btn>
        <Btn disabled={sel.size === 0} onClick={() => { setBulk('policy'); setVal(''); }}>Update Penalisation Policy</Btn>
      </div>
      <div style={{ overflowX: 'auto' }}>
        <table style={{ borderCollapse: 'collapse', width: '100%' }}>
          <thead><tr>
            <th style={th}><input type="checkbox" aria-label="Select all" checked={shown.length > 0 && shown.every(r => sel.has(r.p.userId))} onChange={e => setSel(e.target.checked ? new Set(shown.map(r => r.p.userId)) : new Set())} /></th>
            {['Employee', 'Department', 'Shift', 'Weekly off', 'Penalisation policy'].map(h => <th key={h} style={th}>{h}</th>)}
          </tr></thead>
          <tbody>{shown.map(r => (
            <tr key={r.p.userId}>
              <td style={td}><input type="checkbox" aria-label={`Select ${r.p.fullName}`} checked={sel.has(r.p.userId)} onChange={e => setSel(s => { const n = new Set(s); if (e.target.checked) n.add(r.p.userId); else n.delete(r.p.userId); return n; })} /></td>
              <td style={td}><PersonCell p={r.p} avatar={avatar} /></td><td style={td}>{r.p.department}</td><td style={td}>{r.shift}</td><td style={td}>{r.off}</td><td style={td}>{r.policy}</td>
            </tr>
          ))}</tbody>
        </table>
      </div>
      <div style={note}>Mock data. Select employees, then use a bulk action.</div>
      {bulk && (
        <Modal title={`${label} (${sel.size})`} onClose={() => setBulk(null)}>
          <select style={field} value={val} onChange={e => setVal(e.target.value)} aria-label="New value"><option value="">Select…</option>{opts.map(o => <option key={o}>{o}</option>)}</select>
          <div style={{ display: 'flex', gap: 8 }}><Btn onClick={() => setBulk(null)}>Cancel</Btn><Btn primary disabled={!val} onClick={apply}>Apply</Btn></div>
        </Modal>
      )}
    </div>
  );
}

/* ══ Reports ══ */
interface Card { key: string; title: string; description: string; blocked?: string }
const REQ_CARDS: Card[] = [
  { key: 'reg', title: 'Attendance Regularizations Summary', description: 'Summary of attendance adjustment and regularization requests made by employees.' },
  { key: 'loc', title: 'Mobile Location Punches', description: "Details of employees' location punches along with coordinates.", blocked: 'Requires GPS/coordinate punch tracking, not yet built.' },
  { key: 'ot', title: 'Overtime Requests', description: 'Summary of overtime requests made by employees.' },
  { key: 'pd', title: 'Partial Day Requests', description: 'Summary of partial day requests made by employees.' },
  { key: 'rem', title: 'Remote Clock-in Requests Summary', description: 'Summary of remote clock-ins and outs requests made by employees.' },
  { key: 'wfh', title: 'Working Remotely (WFH/OD) Requests', description: 'Summary of WFH requests made by employees (no separate OD workflow exists today).' },
];
const CATS: Record<string, Card[]> = {
  'Reports Home': REQ_CARDS, 'Attendance Request Reports': REQ_CARDS,
  'Attendance Reports': [{ key: 'att', title: 'Team Attendance Report', description: 'Daily check-in, check-out, worked hours, and status for your direct reports.' }],
  'Punctuality Reports': [{ key: 'pun', title: 'Team Punctuality Report', description: 'On-time leaderboard for your direct reports: on-time days vs. expected working days.' }],
  'Negligence Reports': [{ key: 'neg', title: 'Team Negligence Report', description: 'Late arrivals, least hours worked, and frequent breaks for your direct reports.' }],
  'Scheduled reports': [],
};
export function ReportsTab({ toast }: { toast: Toast }) {
  const [cat, setCat] = useState('Attendance Request Reports'); const [q, setQ] = useState(''); const [run, setRun] = useState<Card | null>(null);
  const r = useRange(30);
  const cards = (CATS[cat] ?? []).filter(c => !q || c.title.toLowerCase().includes(q.toLowerCase()));
  return (
    <div style={panel}>
      <div className="mt-reports" style={{ display: 'grid', gridTemplateColumns: '220px minmax(0,1fr)' }}>
        <div style={{ borderRight: '1px solid var(--line)', padding: '14px 10px' }}>
          {Object.keys(CATS).map(c => (
            <button type="button" key={c} onClick={() => setCat(c)} style={{ display: 'block', width: '100%', textAlign: 'left', border: 'none', fontFamily: 'inherit', padding: '9px 12px', borderRadius: 6, cursor: 'pointer', fontSize: 12.5, fontWeight: 600, marginBottom: 2, background: cat === c ? 'var(--brand)' : 'transparent', color: cat === c ? '#fff' : 'var(--txt-mut)' }}>{c}</button>
          ))}
        </div>
        <div>
          <div style={head}><span style={title}>{cat}</span><label style={{ ...field, display: 'flex', alignItems: 'center', gap: 6 }}><Search size={13} /><input value={q} onChange={e => setQ(e.target.value)} placeholder="Search reports…" style={{ background: 'none', border: 'none', outline: 'none', color: 'var(--txt)', fontSize: 12.5 }} /></label></div>
          <div style={{ padding: 18 }}>
            {cat === 'Scheduled reports' ? <div style={{ fontSize: 12.5, color: 'var(--txt-dim)' }}>Not available yet.</div>
              : cards.length === 0 ? <div style={{ fontSize: 12.5, color: 'var(--txt-dim)' }}>No reports match your search.</div>
              : <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(240px, 1fr))', gap: 12 }}>{cards.map(c => (
                <button type="button" key={c.key} onClick={() => (c.blocked ? toast(c.blocked) : setRun(c))} style={{ textAlign: 'left', fontFamily: 'inherit', background: 'var(--raised)', border: '1px solid var(--line)', borderRadius: 10, padding: 14, cursor: 'pointer', opacity: c.blocked ? 0.55 : 1, position: 'relative' }}>
                  {c.blocked && <span style={{ position: 'absolute', top: 10, right: 10, fontSize: 9.5, fontWeight: 700, padding: '2px 7px', borderRadius: 20, background: 'var(--raised2)', color: 'var(--txt-mut)' }}>Coming soon</span>}
                  <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--txt)', marginBottom: 5, paddingRight: c.blocked ? 78 : 0 }}>{c.title}</div>
                  <div style={{ fontSize: 11.5, color: 'var(--txt-mut)', lineHeight: 1.4 }}>{c.description}</div>
                </button>))}</div>}
          </div>
        </div>
      </div>
      {run && (
        <Modal title={run.title} onClose={() => setRun(null)}>
          <DateRange {...r} />
          <div style={{ fontSize: 11.5, color: 'var(--txt-dim)' }}>Mock report. Downloads nothing in the preview.</div>
          <div style={{ display: 'flex', gap: 8 }}><Btn onClick={() => setRun(null)}>Close</Btn><Btn primary onClick={() => { toast(`${run.title}: Excel download (preview only)`); setRun(null); }}>Download Excel</Btn></div>
        </Modal>
      )}
    </div>
  );
}

/* ══ Performance (Skills & Growth) ══ */
const SKILLS = ['React', 'TypeScript', 'Java', 'Spring Boot', 'SQL', 'Figma', 'AWS', 'Docker', 'Testing'];
const LEVELS = [['Beginner', 'var(--txt-dim)'], ['Intermediate', 'var(--info)'], ['Advanced', 'var(--brand-bright)'], ['Expert', 'var(--ok)']] as const;
const CERTS = [['AWS Cloud Practitioner', 'var(--ok)'], ['PMP', 'var(--warn)'], ['Scrum Master', 'var(--risk)'], ['ISTQB Foundation', 'var(--ok)']] as const;
const COURSES = ['Advanced TypeScript', 'System Design', 'Leadership Essentials', 'Cloud Security'];
export function PerformanceTab({ people, avatar }: { people: TabPerson[]; avatar: AvatarRenderer }) {
  const [q, setQ] = useState('');
  const [open, setOpen] = useState<Set<string>>(new Set());
  const rows = useMemo(() => people.map((p, i) => {
    const rnd = seeded(i * 31 + 7);
    return {
      p,
      skills: SKILLS.filter(() => rnd() < 0.45).slice(0, 4).map(s => [s, LEVELS[Math.floor(rnd() * 4)]] as const),
      certs: CERTS.filter(() => rnd() < 0.5),
      learning: COURSES.filter(() => rnd() < 0.5),
    };
  }), [people]);
  const shown = rows.filter(r => !q || r.p.fullName.toLowerCase().includes(q.toLowerCase()));
  const chip = (tone: string): React.CSSProperties => ({ display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 11, fontWeight: 600, padding: '3px 9px', borderRadius: 20, color: 'var(--txt)', background: `color-mix(in srgb, ${tone} 13%, transparent)`, border: `1px solid color-mix(in srgb, ${tone} 28%, transparent)` });
  const col = (label: string, children: ReactNode) => (
    <div style={{ flex: '1 1 180px', minWidth: 160 }}>
      <div style={{ fontSize: 10.5, fontWeight: 700, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.05em', marginBottom: 6 }}>{label}</div>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>{children}</div>
    </div>
  );
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
      <label style={{ ...field, display: 'flex', alignItems: 'center', gap: 6, maxWidth: 320 }}><Search size={13} /><input value={q} onChange={e => setQ(e.target.value)} placeholder="Search employee by name or code…" style={{ flex: 1, minWidth: 0, background: 'none', border: 'none', outline: 'none', color: 'var(--txt)', fontSize: 12.5 }} /></label>
      {shown.map(({ p, skills, certs, learning }) => {
        const isOpen = open.has(p.userId);
        const cut = <T,>(a: T[]) => (isOpen ? a : a.slice(0, 2));
        return (
          <div key={p.userId} style={{ ...panel, padding: '16px 18px', display: 'flex', gap: 18, flexWrap: 'wrap', alignItems: 'flex-start' }}>
            <div style={{ minWidth: 170 }}><PersonCell p={p} avatar={avatar} /></div>
            {col('Skills', skills.length ? cut(skills).map(([s, [l, t]]) => <span key={s} style={chip(t)}>{s}<b style={{ fontSize: 9, color: t }}>{l}</b></span>) : <span style={{ fontSize: 11.5, color: 'var(--txt-dim)' }}>None added</span>)}
            {col('Certificates', certs.length ? cut(certs).map(([c, t]) => <span key={c} style={chip(t)}>{c}</span>) : <span style={{ fontSize: 11.5, color: 'var(--txt-dim)' }}>None added</span>)}
            {col('Learning', learning.length ? cut(learning).map(c => <span key={c} style={chip('var(--info)')}>{c}</span>) : <span style={{ fontSize: 11.5, color: 'var(--txt-dim)' }}>None added</span>)}
            {(skills.length > 2 || certs.length > 2 || learning.length > 2) && (
              <button type="button" aria-label={isOpen ? 'Show less' : 'Show all'} onClick={() => setOpen(o => { const n = new Set(o); if (n.has(p.userId)) n.delete(p.userId); else n.add(p.userId); return n; })} style={{ width: 28, height: 28, borderRadius: '50%', border: '1px solid var(--line2)', background: 'var(--raised)', cursor: 'pointer', color: 'var(--txt-mut)', alignSelf: 'center' }}>{isOpen ? '▲' : '▼'}</button>
            )}
          </div>
        );
      })}
      <div style={{ fontSize: 11, color: 'var(--txt-dim)' }}>Mock data. Stand-in for the read-only Skills, Certificates and Learning summary of each direct report.</div>
    </div>
  );
}
