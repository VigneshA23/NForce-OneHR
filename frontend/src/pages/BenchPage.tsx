import { useEffect, useMemo, useState } from 'react';
import { useAuthStore } from '../store/authStore';
import { employeesApi, type EmployeeRecord } from '../api/employees';
import {
  benchApi, BENCH_REASON_LABELS,
  type BenchReasonCode, type BenchStatusRecord, type TeamBenchRecord,
} from '../api/bench';

const card: React.CSSProperties = { background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '14px 16px' };
const inputStyle: React.CSSProperties = {
  background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6,
  padding: '7px 10px', fontSize: 12.5, color: 'var(--txt)', outline: 'none', fontFamily: 'inherit',
  width: '100%', boxSizing: 'border-box',
};
const labelStyle: React.CSSProperties = { fontSize: 11, fontWeight: 600, color: 'var(--txt-mut)', display: 'block', marginBottom: 5 };
const th: React.CSSProperties = { textAlign: 'left', padding: '6px 8px', fontSize: 11, color: 'var(--txt-dim)', textTransform: 'uppercase', borderBottom: '1px solid var(--line)' };
const td: React.CSSProperties = { padding: '7px 8px', fontSize: 12.5, color: 'var(--txt)', borderBottom: '1px solid var(--line)' };

const REASONS = Object.keys(BENCH_REASON_LABELS) as BenchReasonCode[];

/** US-A01: HR marks an employee On Bench, and can end an episode they just opened. */
function MarkBenchSection({ token }: { token: string }) {
  const [employees, setEmployees] = useState<EmployeeRecord[]>([]);
  const [employeeId, setEmployeeId] = useState('');
  const [reasonCode, setReasonCode] = useState<BenchReasonCode>('PROJECT_ENDED');
  const [startDate, setStartDate] = useState(() => new Date().toISOString().slice(0, 10));
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState('');
  const [episodes, setEpisodes] = useState<BenchStatusRecord[]>([]);

  useEffect(() => {
    employeesApi.list(token).then(list => setEmployees(list.filter(e => e.active))).catch(() => setEmployees([]));
  }, [token]);

  async function submit() {
    setError('');
    if (!employeeId) { setError('Select an employee'); return; }
    setSubmitting(true);
    try {
      const created = await benchApi.start(token, { employeeId, reasonCode, startDate });
      setEpisodes(prev => [created, ...prev]);
      setEmployeeId('');
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to mark employee on bench');
    } finally {
      setSubmitting(false);
    }
  }

  async function end(id: string) {
    setError('');
    try {
      const ended = await benchApi.end(token, id);
      setEpisodes(prev => prev.map(b => (b.id === id ? ended : b)));
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to end bench status');
    }
  }

  return (
    <div style={{ display: 'grid', gridTemplateColumns: '340px 1fr', gap: 16, alignItems: 'start', marginBottom: 20 }}>
      <div style={card}>
        <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--txt)', marginBottom: 10 }}>Mark employee On Bench</div>

        <label style={labelStyle}>Employee</label>
        <select value={employeeId} onChange={e => setEmployeeId(e.target.value)} style={{ ...inputStyle, marginBottom: 8 }}>
          <option value="">Select an employee</option>
          {employees.map(e => <option key={e.userId} value={e.userId}>{e.fullName} ({e.employeeCode})</option>)}
        </select>

        <label style={labelStyle}>Reason</label>
        <select value={reasonCode} onChange={e => setReasonCode(e.target.value as BenchReasonCode)} style={{ ...inputStyle, marginBottom: 8 }}>
          {REASONS.map(r => <option key={r} value={r}>{BENCH_REASON_LABELS[r]}</option>)}
        </select>

        <label style={labelStyle}>Start date</label>
        <input type="date" value={startDate} onChange={e => setStartDate(e.target.value)} style={{ ...inputStyle, marginBottom: 14 }} />

        {error && (
          <div role="alert" style={{ background: 'rgba(228,55,61,.1)', border: '1px solid rgba(228,55,61,.3)', borderRadius: 6, padding: '8px 10px', marginBottom: 12, color: 'var(--risk)', fontSize: 12 }}>
            {error}
          </div>
        )}

        <button type="button" onClick={submit} disabled={submitting}
          style={{ width: '100%', padding: '9px 14px', background: 'var(--brand)', border: 'none', borderRadius: 6, fontSize: 13, fontWeight: 600, color: '#fff', cursor: submitting ? 'not-allowed' : 'pointer', opacity: submitting ? 0.7 : 1 }}>
          {submitting ? 'Saving…' : 'Mark On Bench'}
        </button>
      </div>

      <div style={card}>
        <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--txt)', marginBottom: 10 }}>Changes made this session</div>
        {episodes.length === 0 ? (
          <div style={{ padding: 16, fontSize: 12.5, color: 'var(--txt-dim)', textAlign: 'center' }}>No bench changes yet</div>
        ) : (
          <table style={{ width: '100%', borderCollapse: 'collapse' }}>
            <thead>
              <tr><th style={th}>Employee</th><th style={th}>Reason</th><th style={th}>Start</th><th style={th}>Status</th><th style={th} /></tr>
            </thead>
            <tbody>
              {episodes.map(b => (
                <tr key={b.id}>
                  <td style={td}>{b.employeeName}</td>
                  <td style={td}>{BENCH_REASON_LABELS[b.reasonCode]}</td>
                  <td style={td}>{b.startDate}</td>
                  <td style={td}>
                    <span style={{
                      fontSize: 10.5, fontWeight: 700, padding: '2px 8px', borderRadius: 20,
                      background: b.active ? 'rgba(228,150,40,.15)' : 'rgba(47,182,124,.15)',
                      color: b.active ? 'var(--warn, #e49628)' : 'var(--ok)',
                    }}>{b.active ? 'ON BENCH' : `ENDED ${b.endDate}`}</span>
                  </td>
                  <td style={{ ...td, textAlign: 'right' }}>
                    {b.active && (
                      <button type="button" onClick={() => end(b.id)}
                        style={{ padding: '4px 10px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, fontSize: 12, color: 'var(--txt)', cursor: 'pointer' }}>
                        End bench
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}

/** US-A02: the caller's benched direct reports. */
function TeamBenchSection({ token }: { token: string }) {
  const [rows, setRows] = useState<TeamBenchRecord[] | null>(null);
  const [error, setError] = useState('');

  useEffect(() => {
    benchApi.team(token).then(setRows).catch(e => setError(e instanceof Error ? e.message : 'Failed to load team bench'));
  }, [token]);

  const content = useMemo(() => {
    if (error) return <div style={{ padding: 16, color: 'var(--risk)', fontSize: 12.5 }}>{error}</div>;
    if (!rows) return <div style={{ padding: 16, color: 'var(--txt-mut)', fontSize: 12.5 }}>Loading…</div>;
    if (rows.length === 0) return <div style={{ padding: 16, fontSize: 12.5, color: 'var(--txt-dim)', textAlign: 'center' }}>None of your direct reports are on bench</div>;
    return (
      <table style={{ width: '100%', borderCollapse: 'collapse' }}>
        <thead>
          <tr><th style={th}>Name</th><th style={th}>Bench reason</th><th style={th}>Days on bench</th><th style={th}>Last project</th></tr>
        </thead>
        <tbody>
          {rows.map(r => (
            <tr key={r.employeeId}>
              <td style={td}>{r.employeeName}</td>
              <td style={td}>{BENCH_REASON_LABELS[r.reasonCode]}</td>
              <td style={td}>{r.daysOnBench}</td>
              <td style={{ ...td, color: r.lastProject ? 'var(--txt)' : 'var(--txt-dim)' }}>{r.lastProject ?? '—'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    );
  }, [rows, error]);

  return (
    <div style={card}>
      <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--txt)', marginBottom: 10 }}>Benched direct reports</div>
      {content}
    </div>
  );
}

export default function BenchPage() {
  const token = useAuthStore(s => s.token) ?? '';
  const role = useAuthStore(s => s.user?.role);
  const canManage = role === 'HR_ADMIN' || role === 'SUPER_ADMIN';

  return (
    <div>
      <div style={{ marginBottom: 18 }}>
        <h1 style={{ fontSize: 20, fontWeight: 700, color: 'var(--txt)', margin: 0, fontFamily: 'Inter, sans-serif' }}>Bench Management</h1>
        <p style={{ margin: '4px 0 0', color: 'var(--txt-mut)', fontSize: 13 }}>
          {canManage
            ? 'Mark employees as on bench with a reason, and see your own benched reports.'
            : 'See which of your direct reports are currently on bench.'}
        </p>
      </div>
      {canManage && <MarkBenchSection token={token} />}
      <TeamBenchSection token={token} />
    </div>
  );
}
