import { useEffect, useState } from 'react';
import { benchApi, BENCH_REASON_LABELS, type TeamBenchRecord } from '../../api/bench';
import { card, CardTitle, Empty, td, th } from './ui';

/** US-A02: the caller's benched direct reports (the server scopes it to their own hierarchy). */
export default function TeamBenchSection({ token }: { token: string }) {
  const [rows, setRows] = useState<TeamBenchRecord[] | null>(null);
  const [error, setError] = useState('');

  useEffect(() => {
    benchApi.team(token).then(setRows).catch(e => setError(e instanceof Error ? e.message : 'Failed to load team bench'));
  }, [token]);

  return (
    <div style={card}>
      <CardTitle>Benched direct reports</CardTitle>
      {error ? <div style={{ padding: 16, color: 'var(--risk)', fontSize: 12.5 }}>{error}</div>
        : !rows ? <Empty>Loading…</Empty>
        : rows.length === 0 ? <Empty>None of your direct reports are on bench</Empty>
        : (
          <table style={{ width: '100%', borderCollapse: 'collapse' }}>
            <thead><tr><th style={th}>Name</th><th style={th}>Bench reason</th><th style={th}>Days on bench</th><th style={th}>Last project</th></tr></thead>
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
        )}
    </div>
  );
}
