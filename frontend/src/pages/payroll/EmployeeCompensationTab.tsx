import { useEffect, useState } from 'react';
import { useAuthStore } from '../../store/authStore';
import { useToast } from '../../context/ToastContext';
import { payrollApi, type SalaryStructureResponse, type EmployeeCompensationResponse, type PayGroupResponse } from '../../api/payroll';
import {
  panelStyle, thStyle, tdStyle, fmtCurrency, fmtDate, EmptyRow, FormRow, labelStyle, inputStyle,
  BtnPrimary, SectionHead, useEmployeeDirectory,
} from './payrollShared';

export function EmployeeCompensationTab({ structures, payGroups }: { structures: SalaryStructureResponse[]; payGroups: PayGroupResponse[] }) {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const { employees } = useEmployeeDirectory();
  const [employeeId, setEmployeeId] = useState('');
  const [payGroupId, setPayGroupId] = useState(payGroups[0]?.id ?? '');
  const [structureId, setStructureId] = useState(structures[0]?.id ?? '');
  const [ctc, setCtc] = useState('');
  const [effectiveFrom, setEffectiveFrom] = useState('2025-01-01');
  const [current, setCurrent] = useState<EmployeeCompensationResponse | null>(null);
  const [history, setHistory] = useState<EmployeeCompensationResponse[]>([]);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (!structureId && structures[0]) setStructureId(structures[0].id);
  }, [structures, structureId]);

  useEffect(() => {
    if (!payGroupId && payGroups[0]) setPayGroupId(payGroups[0].id);
  }, [payGroups, payGroupId]);

  useEffect(() => {
    if (!employeeId) { setCurrent(null); setHistory([]); return; }
    payrollApi.getCurrentCompensation(employeeId, token).then(setCurrent).catch(() => setCurrent(null));
    payrollApi.getCompensationHistory(employeeId, token).then(setHistory).catch(() => setHistory([]));
  }, [employeeId, token]);

  const valid = !!employeeId && !!payGroupId && !!structureId && Number(ctc) > 0 && !!effectiveFrom;

  async function submit() {
    if (!valid) return;
    setSubmitting(true);
    try {
      const comp = await payrollApi.assignCompensation({ employeeId, payGroupId, structureId, ctc: Number(ctc), effectiveFrom }, token);
      setCurrent(comp);
      setHistory(prev => [comp, ...prev.filter(h => h.id !== comp.id)]);
      showToast('success', 'Compensation assigned');
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Failed to assign compensation');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div>
      <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 14, padding: '18px 22px', marginBottom: 20 }}>
        <SectionHead title="Assign Compensation" />
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: 12, marginBottom: 14 }}>
          <FormRow>
            <label style={labelStyle}>Employee *</label>
            <select value={employeeId} onChange={e => setEmployeeId(e.target.value)} style={inputStyle}>
              <option value="">Select…</option>
              {employees.map(e => <option key={e.userId} value={e.userId}>{e.fullName} ({e.departmentName ?? 'No dept'})</option>)}
            </select>
          </FormRow>
          <FormRow>
            <label style={labelStyle}>Pay Group *</label>
            <select value={payGroupId} onChange={e => setPayGroupId(e.target.value)} style={inputStyle}>
              {payGroups.map(pg => <option key={pg.id} value={pg.id}>{pg.name}</option>)}
            </select>
          </FormRow>
          <FormRow>
            <label style={labelStyle}>Salary Structure *</label>
            <select value={structureId} onChange={e => setStructureId(e.target.value)} style={inputStyle}>
              {structures.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}
            </select>
          </FormRow>
          <FormRow>
            <label style={labelStyle}>Annual CTC *</label>
            <input type="number" min={0} value={ctc} onChange={e => setCtc(e.target.value)} style={inputStyle} placeholder="e.g. 1200000" />
          </FormRow>
          <FormRow>
            <label style={labelStyle}>Effective From *</label>
            <input type="date" value={effectiveFrom} onChange={e => setEffectiveFrom(e.target.value)} style={inputStyle} />
          </FormRow>
        </div>
        {current && (
          <div style={{ fontSize: 13, color: 'var(--txt-mut)', marginBottom: 14 }}>
            Current: <strong style={{ color: 'var(--txt)' }}>{current.structureName}</strong>, {fmtCurrency(current.ctc)} CTC, effective {fmtDate(current.effectiveFrom)}
          </div>
        )}
        <BtnPrimary onClick={submit} disabled={!valid || submitting}>{submitting ? 'Assigning…' : 'Assign Compensation'}</BtnPrimary>
      </div>

      {employeeId && (
        <div>
          <SectionHead title="Compensation History" />
          <div style={panelStyle}>
            <table className="nf-pr-table" style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead><tr>{['Structure', 'CTC', 'Effective From', 'Effective To'].map(h => <th key={h} style={thStyle}>{h}</th>)}</tr></thead>
              <tbody>
                {history.map(h => (
                  <tr key={h.id}>
                    <td style={tdStyle}>{h.structureName}</td>
                    <td style={tdStyle}>{fmtCurrency(h.ctc)}</td>
                    <td style={tdStyle}>{fmtDate(h.effectiveFrom)}</td>
                    <td style={tdStyle}>{h.effectiveTo ? fmtDate(h.effectiveTo) : 'Current'}</td>
                  </tr>
                ))}
                {history.length === 0 && <EmptyRow cols={4} msg="No compensation history for this employee yet." />}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </div>
  );
}
