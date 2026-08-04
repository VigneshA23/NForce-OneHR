import { useEffect, useMemo, useState } from 'react';
import { useAuthStore } from '../store/authStore';
import { useToast } from '../context/ToastContext';
import { exceptionsApi, type ExceptionRecord } from '../api/exceptions';

const inputStyle: React.CSSProperties = { background: 'var(--shell)', border: '1px solid var(--line2)', borderRadius: 6, padding: '8px 10px', color: 'var(--txt)', fontSize: 13, boxSizing: 'border-box', outline: 'none' };
const labelStyle: React.CSSProperties = { display: 'block', fontSize: 11, fontWeight: 600, color: 'var(--txt-mut)', marginBottom: 5, textTransform: 'uppercase', letterSpacing: '.06em' };

const EXCEPTION_TYPE_LABELS: Record<string, string> = {
  LATE_ARRIVAL: 'Late Arrival',
  MISSING_PUNCH: 'Missing Punch',
  LEAVE_ATTENDANCE_CONFLICT: 'Leave/Attendance Conflict',
};

const EXCEPTION_TYPE_STYLES: Record<string, { color: string; background: string; border: string }> = {
  LATE_ARRIVAL: { color: '#E0A93B', background: 'rgba(224,169,59,.1)', border: 'rgba(224,169,59,.25)' },
  MISSING_PUNCH: { color: 'var(--risk)', background: 'rgba(228,55,61,.1)', border: 'rgba(228,55,61,.25)' },
  LEAVE_ATTENDANCE_CONFLICT: { color: '#8B5CF6', background: 'rgba(139,92,246,.1)', border: 'rgba(139,92,246,.25)' },
};

function todayIso(): string {
  return new Date().toISOString().slice(0, 10);
}

function daysAgoIso(n: number): string {
  const d = new Date();
  d.setDate(d.getDate() - n);
  return d.toISOString().slice(0, 10);
}

function ExceptionTypeBadge({ type }: { type: string }) {
  const s = EXCEPTION_TYPE_STYLES[type] ?? { color: 'var(--txt-mut)', background: 'var(--shell)', border: 'var(--line2)' };
  return (
    <span style={{ fontSize: 11, fontWeight: 600, color: s.color, background: s.background, border: `1px solid ${s.border}`, borderRadius: 4, padding: '2px 7px' }}>
      {EXCEPTION_TYPE_LABELS[type] ?? type}
    </span>
  );
}

export default function ExceptionDashboardPage() {
  const token = useAuthStore(s => s.token)!;
  const role = useAuthStore(s => s.user?.role);
  const { showToast } = useToast();
  const [from, setFrom] = useState(daysAgoIso(6));
  const [to, setTo] = useState(todayIso());
  const [typeFilter, setTypeFilter] = useState<'ALL' | 'LATE_ARRIVAL' | 'MISSING_PUNCH' | 'LEAVE_ATTENDANCE_CONFLICT'>('ALL');
  const [exceptions, setExceptions] = useState<ExceptionRecord[]>([]);
  const [loading, setLoading] = useState(true);

  const isHrOrSuperAdmin = role === 'HR_ADMIN' || role === 'SUPER_ADMIN';
  const subtitle = isHrOrSuperAdmin
    ? 'Company-wide attendance exceptions.'
    : 'Your team’s attendance exceptions.';

  function load() {
    setLoading(true);
    exceptionsApi.list(token, from, to)
      .then(setExceptions)
      .catch(err => showToast('error', err instanceof Error ? err.message : 'Failed to load exceptions'))
      .finally(() => setLoading(false));
  }

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token, from, to]);

  const visibleExceptions = useMemo(
    () => typeFilter === 'ALL' ? exceptions : exceptions.filter(exc => exc.exceptionType === typeFilter),
    [exceptions, typeFilter],
  );

  const thStyle: React.CSSProperties = { padding: '10px 14px', textAlign: 'left', fontSize: 11, fontWeight: 700, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.07em', borderBottom: '1px solid var(--line)', whiteSpace: 'nowrap' };
  const tdStyle: React.CSSProperties = { padding: '12px 14px', fontSize: 13, color: 'var(--txt-mut)', borderBottom: '1px solid var(--line)', verticalAlign: 'middle' };

  return (
    <div>
      <div style={{ marginBottom: 22 }}>
        <h1 style={{ fontFamily: '"Space Grotesk", sans-serif', fontSize: 20, fontWeight: 700, color: 'var(--txt)', margin: 0 }}>Exception Dashboard</h1>
        <p style={{ fontSize: 13, color: 'var(--txt-mut)', marginTop: 4 }}>{subtitle}</p>
      </div>

      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 14, alignItems: 'end', marginBottom: 16 }}>
        <div>
          <label style={labelStyle}>From</label>
          <input type="date" style={inputStyle} value={from} onChange={e => setFrom(e.target.value)} />
        </div>
        <div>
          <label style={labelStyle}>To</label>
          <input type="date" style={inputStyle} value={to} onChange={e => setTo(e.target.value)} />
        </div>
        <div>
          <label style={labelStyle}>Exception Type</label>
          <select style={inputStyle} value={typeFilter} onChange={e => setTypeFilter(e.target.value as typeof typeFilter)}>
            <option value="ALL">All</option>
            <option value="LATE_ARRIVAL">Late Arrival</option>
            <option value="MISSING_PUNCH">Missing Punch</option>
            <option value="LEAVE_ATTENDANCE_CONFLICT">Leave/Attendance Conflict</option>
          </select>
        </div>
      </div>

      <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, overflow: 'hidden' }}>
        {loading ? (
          <div style={{ padding: 40, textAlign: 'center', color: 'var(--txt-dim)' }}>Loading…</div>
        ) : visibleExceptions.length === 0 ? (
          <div style={{ padding: 48, textAlign: 'center' }}>
            <div style={{ fontSize: 15, color: 'var(--txt-mut)', marginBottom: 8 }}>No exceptions in this range</div>
            <div style={{ fontSize: 13, color: 'var(--txt-dim)' }}>Late arrivals and missing punches will show up here as attendance is recorded.</div>
          </div>
        ) : (
          <div style={{ overflowX: 'auto' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead>
                <tr>
                  {['Employee', 'Type', 'Date', 'Expected', 'Actual', 'Minutes Late', 'Status'].map(h => (
                    <th key={h} style={thStyle}>{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {visibleExceptions.map(exc => (
                  <tr key={exc.id}>
                    <td style={{ ...tdStyle, color: 'var(--txt)', fontWeight: 600 }}>{exc.employeeFullName ?? exc.employeeUserId}</td>
                    <td style={tdStyle}><ExceptionTypeBadge type={exc.exceptionType} /></td>
                    <td style={tdStyle}>{exc.exceptionDate}</td>
                    <td style={tdStyle}>{exc.expectedTime ?? <span style={{ color: 'var(--txt-dim)' }}>—</span>}</td>
                    <td style={tdStyle}>{exc.actualTime ?? <span style={{ color: 'var(--txt-dim)' }}>—</span>}</td>
                    <td style={tdStyle}>{exc.minutesLate ?? <span style={{ color: 'var(--txt-dim)' }}>—</span>}</td>
                    <td style={tdStyle}>{exc.status}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}
