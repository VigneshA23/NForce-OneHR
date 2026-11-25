import { useEffect, useState } from 'react';
import { History } from 'lucide-react';
import { usePaged } from '../../components/usePaged';
import { TablePagination } from '../../components/TablePagination';
import { benchApi, BENCH_REASON_LABELS, CLOSE_REASON_LABELS, type BenchHistoryRow } from '../../api/bench';
import { card, CardTitle, Empty, ErrorBox, PersonCell, Pill, td, th } from './ui';

/** US-A06 AC-2: every ended bench episode with its start, end and duration. Episodes are never deleted, so this is the reporting history. */
export default function BenchHistorySection({ token }: { token: string }) {
  const [rows, setRows] = useState<BenchHistoryRow[] | null>(null);
  const [error, setError] = useState('');
  const paged = usePaged(rows ?? []);

  useEffect(() => {
    benchApi.history(token).then(setRows).catch(e => { setError(e instanceof Error ? e.message : 'Failed to load bench history'); setRows([]); });
  }, [token]);

  return (
    <div style={card}>
      <CardTitle icon={<History size={16} />}>Bench history {rows ? `(${rows.length})` : ''}</CardTitle>
      <ErrorBox message={error} />
      {!rows ? <Empty>Loading...</Empty> : rows.length === 0 ? <Empty>No bench episodes have ended yet</Empty> : (
        <>
          <table className="nf-bn-table" style={{ width: '100%', borderCollapse: 'collapse' }}>
            <thead>
              <tr>
                <th style={th}>Employee</th><th style={th}>Bench reason</th><th style={th}>Start</th><th style={th}>End</th>
                <th style={th}>Duration</th><th style={th}>Ended by</th><th style={th}>Note</th>
              </tr>
            </thead>
            <tbody>
              {paged.pageRows.map(r => (
                <tr key={r.id}>
                  <td style={td}><PersonCell userId={r.employeeId} name={r.employeeName ?? 'Unknown'} /></td>
                  <td style={td}>{BENCH_REASON_LABELS[r.reasonCode]}</td>
                  <td style={td}>{r.startDate}</td>
                  <td style={td}>{r.endDate}</td>
                  <td style={td}><Pill tone="info">{r.durationDays} {r.durationDays === 1 ? 'day' : 'days'}</Pill></td>
                  <td style={td}>{r.projectName ? `Allocated to ${r.projectName}` : r.closeReason ? CLOSE_REASON_LABELS[r.closeReason] : '-'}</td>
                  <td style={{ ...td, color: 'var(--txt-mut)' }}>{r.closeNote || '-'}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <TablePagination {...paged.pagerProps} noun="episodes" />
        </>
      )}
    </div>
  );
}
