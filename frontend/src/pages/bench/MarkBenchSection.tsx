import { useEffect, useState } from 'react';
import { employeesApi, type EmployeeRecord } from '../../api/employees';
import { benchApi, BENCH_REASON_LABELS, type BenchReasonCode } from '../../api/bench';
import { card, CardTitle, ErrorBox, inputStyle, labelStyle, primaryBtn, todayLocal } from './ui';

const REASONS = Object.keys(BENCH_REASON_LABELS) as BenchReasonCode[];

/** US-A01: HR marks an employee On Bench with a controlled reason and an editable start date. */
export default function MarkBenchSection({ token, onMarked }: { token: string; onMarked: (employeeName: string) => void }) {
  const [employees, setEmployees] = useState<EmployeeRecord[]>([]);
  const [employeeId, setEmployeeId] = useState('');
  const [reasonCode, setReasonCode] = useState<BenchReasonCode>('PROJECT_ENDED');
  const [startDate, setStartDate] = useState(() => todayLocal());
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    employeesApi.list(token).then(list => setEmployees(list.filter(e => e.active))).catch(() => setEmployees([]));
  }, [token]);

  async function submit() {
    setError('');
    if (!employeeId) { setError('Select an employee'); return; }
    setSubmitting(true);
    try {
      const created = await benchApi.start(token, { employeeId, reasonCode, startDate });
      setEmployeeId('');
      onMarked(created.employeeName);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to mark employee on bench');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div style={{ ...card, maxWidth: 420 }}>
      <CardTitle>Mark employee On Bench</CardTitle>

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

      <ErrorBox message={error} />
      <button type="button" onClick={submit} disabled={submitting} style={{ ...primaryBtn(submitting), width: '100%' }}>
        {submitting ? 'Saving…' : 'Mark On Bench'}
      </button>
    </div>
  );
}
