import { useEffect, useState } from 'react';
import { ChevronDown, ChevronUp } from 'lucide-react';
import { useAuthStore } from '../store/authStore';
import { useToast } from '../context/ToastContext';
import { exceptionsApi, type ExceptionRecord, type PlaceholderCheckinRecord } from '../api/exceptions';
import { employeesApi, type EmployeeRecord } from '../api/employees';

const inputStyle: React.CSSProperties = { background: 'var(--shell)', border: '1px solid var(--line2)', borderRadius: 6, padding: '8px 10px', color: 'var(--txt)', fontSize: 13, boxSizing: 'border-box', outline: 'none' };
const labelStyle: React.CSSProperties = { display: 'block', fontSize: 11, fontWeight: 600, color: 'var(--txt-mut)', marginBottom: 5, textTransform: 'uppercase', letterSpacing: '.06em' };

function todayIso(): string {
  return new Date().toISOString().slice(0, 10);
}

function daysAgoIso(n: number): string {
  const d = new Date();
  d.setDate(d.getDate() - n);
  return d.toISOString().slice(0, 10);
}

function ExceptionTypeBadge() {
  return (
    <span style={{ fontSize: 11, fontWeight: 600, color: '#E0A93B', background: 'rgba(224,169,59,.1)', border: '1px solid rgba(224,169,59,.25)', borderRadius: 4, padding: '2px 7px' }}>
      Late Arrival
    </span>
  );
}

// TEMPORARY — remove this panel (and the backend endpoints it calls) once
// FR-004 (Attendance Management) ships real check-in data.
function PlaceholderCheckinDevTools({ token, onSeeded }: { token: string; onSeeded: () => void }) {
  const { showToast } = useToast();
  const [expanded, setExpanded] = useState(false);
  const [employees, setEmployees] = useState<EmployeeRecord[]>([]);
  const [seeded, setSeeded] = useState<PlaceholderCheckinRecord[]>([]);
  const [form, setForm] = useState({ employeeUserId: '', workDate: todayIso(), checkinTime: '09:45', shiftStartTime: '09:30', lateThresholdMinutes: 15 });
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (!expanded) return;
    employeesApi.list(token).then(setEmployees);
    exceptionsApi.listPlaceholderCheckins(token).then(setSeeded);
  }, [expanded, token]);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!form.employeeUserId) { showToast('error', 'Select an employee first.'); return; }
    setSubmitting(true);
    try {
      const record = await exceptionsApi.seedPlaceholderCheckin({
        employeeUserId: form.employeeUserId,
        workDate: form.workDate,
        checkinTime: form.checkinTime,
        shiftStartTime: form.shiftStartTime,
        lateThresholdMinutes: form.lateThresholdMinutes,
      }, token);
      setSeeded(prev => [record, ...prev.filter(r => r.id !== record.id)]);
      showToast('success', 'Placeholder check-in seeded.');
      onSeeded();
    } catch (err) {
      showToast('error', err instanceof Error ? err.message : 'Failed to seed check-in');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div style={{ marginTop: 22, background: 'rgba(228,55,61,.08)', border: '1px solid rgba(228,55,61,.2)', borderRadius: 10, overflow: 'hidden' }}>
      <button
        onClick={() => setExpanded(v => !v)}
        style={{ width: '100%', display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '12px 16px', background: 'none', border: 'none', cursor: 'pointer', textAlign: 'left' }}
      >
        <div>
          <div style={{ fontSize: 11, fontWeight: 700, color: 'var(--risk)', textTransform: 'uppercase', letterSpacing: '.06em' }}>Dev Tools — Seed Placeholder Check-in</div>
          <div style={{ fontSize: 12, color: 'var(--txt-mut)', marginTop: 2 }}>Temporary scaffolding for testing Late Arrival detection before real Attendance data exists (FR-004). Remove once Attendance Management ships.</div>
        </div>
        {expanded ? <ChevronUp size={16} color="var(--txt-mut)" /> : <ChevronDown size={16} color="var(--txt-mut)" />}
      </button>
      {expanded && (
        <div style={{ padding: '0 16px 16px' }}>
          <form onSubmit={handleSubmit} style={{ display: 'grid', gridTemplateColumns: 'repeat(5, 1fr)', gap: 12, alignItems: 'end' }}>
            <div>
              <label style={labelStyle}>Employee</label>
              <select style={{ ...inputStyle, width: '100%' }} value={form.employeeUserId} onChange={e => setForm(f => ({ ...f, employeeUserId: e.target.value }))}>
                <option value="">— Select —</option>
                {employees.map(emp => <option key={emp.userId} value={emp.userId}>{emp.fullName}</option>)}
              </select>
            </div>
            <div>
              <label style={labelStyle}>Work Date</label>
              <input type="date" style={{ ...inputStyle, width: '100%' }} value={form.workDate} onChange={e => setForm(f => ({ ...f, workDate: e.target.value }))} />
            </div>
            <div>
              <label style={labelStyle}>Shift Start</label>
              <input type="time" style={{ ...inputStyle, width: '100%' }} value={form.shiftStartTime} onChange={e => setForm(f => ({ ...f, shiftStartTime: e.target.value }))} />
            </div>
            <div>
              <label style={labelStyle}>Check-in Time</label>
              <input type="time" style={{ ...inputStyle, width: '100%' }} value={form.checkinTime} onChange={e => setForm(f => ({ ...f, checkinTime: e.target.value }))} />
            </div>
            <button type="submit" disabled={submitting} style={{ background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 7, padding: '9px 14px', fontSize: 13, fontWeight: 600, cursor: submitting ? 'not-allowed' : 'pointer', opacity: submitting ? 0.7 : 1 }}>
              {submitting ? 'Seeding…' : 'Seed Check-in'}
            </button>
          </form>

          {seeded.length > 0 && (
            <div style={{ marginTop: 14, fontSize: 12, color: 'var(--txt-dim)' }}>
              Currently seeded: {seeded.map(s => `${s.employeeFullName ?? s.employeeUserId} (${s.workDate} @ ${s.checkinTime})`).join(', ')}
            </div>
          )}
        </div>
      )}
    </div>
  );
}

export default function ExceptionDashboardPage() {
  const token = useAuthStore(s => s.token)!;
  const role = useAuthStore(s => s.user?.role);
  const { showToast } = useToast();
  const [from, setFrom] = useState(daysAgoIso(6));
  const [to, setTo] = useState(todayIso());
  const [exceptions, setExceptions] = useState<ExceptionRecord[]>([]);
  const [loading, setLoading] = useState(true);

  const isHrOrSuperAdmin = role === 'HR_ADMIN' || role === 'SUPER_ADMIN';
  const subtitle = isHrOrSuperAdmin
    ? 'Company-wide attendance & leave exceptions.'
    : 'Your team’s attendance & leave exceptions.';

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
          <select style={inputStyle} value="LATE_ARRIVAL" disabled>
            <option value="LATE_ARRIVAL">Late Arrival</option>
          </select>
        </div>
        <div style={{ fontSize: 12, color: 'var(--txt-dim)', paddingBottom: 8 }}>
          Missing Punch — coming with Attendance Management
        </div>
      </div>

      <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, overflow: 'hidden' }}>
        {loading ? (
          <div style={{ padding: 40, textAlign: 'center', color: 'var(--txt-dim)' }}>Loading…</div>
        ) : exceptions.length === 0 ? (
          <div style={{ padding: 48, textAlign: 'center' }}>
            <div style={{ fontSize: 15, color: 'var(--txt-mut)', marginBottom: 8 }}>No exceptions in this range</div>
            <div style={{ fontSize: 13, color: 'var(--txt-dim)' }}>Late arrivals will show up here once check-in data crosses the shift-start threshold.</div>
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
                {exceptions.map(exc => (
                  <tr key={exc.id}>
                    <td style={{ ...tdStyle, color: 'var(--txt)', fontWeight: 600 }}>{exc.employeeFullName ?? exc.employeeUserId}</td>
                    <td style={tdStyle}><ExceptionTypeBadge /></td>
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

      {isHrOrSuperAdmin && <PlaceholderCheckinDevTools token={token} onSeeded={load} />}
    </div>
  );
}
