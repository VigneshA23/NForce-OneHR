import { useEffect, useState } from 'react';
import { Ban } from 'lucide-react';
import { ActionDock } from '../../components/ActionDock';
import { useAuthStore } from '../../store/authStore';
import { useToast } from '../../context/ToastContext';
import { payrollApi, type StatutoryOverrideResponse, type StatutoryPreviewResponse } from '../../api/payroll';
import {
  panelStyle, thStyle, tdStyle, fmtCurrency, fmtDate, EmptyRow, FormRow, labelStyle, inputStyle,
  BtnPrimary, SectionHead, useEmployeeDirectory,
} from './payrollShared';

const RULE_TYPES = ['PF', 'ESI', 'PROFESSIONAL_TAX'];

/** Keka's "Statutory Override" step — one employee's computed PF/ESI/Professional Tax amount for
 * one cycle. Stored independent of any specific run (see StatutoryDeductionOverride's own doc
 * comment), so it survives a regenerate the same way an LOP override does. */
export function StatutoryOverrideTab({ payGroupId, cycleStart, canConfigure }: { payGroupId: string; cycleStart: string; canConfigure: boolean }) {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const { employees, nameFor } = useEmployeeDirectory();
  const [employeeId, setEmployeeId] = useState('');
  const [ruleType, setRuleType] = useState('PF');
  const [overrideAmount, setOverrideAmount] = useState('');
  const [overrideReason, setOverrideReason] = useState('');
  const [overrides, setOverrides] = useState<StatutoryOverrideResponse[]>([]);
  const [submitting, setSubmitting] = useState(false);
  const [preview, setPreview] = useState<StatutoryPreviewResponse | null>(null);
  const [previewLoading, setPreviewLoading] = useState(false);

  function reload(forEmployeeId: string) {
    if (!forEmployeeId) { setOverrides([]); return; }
    payrollApi.listStatutoryOverridesForEmployee(forEmployeeId, token).then(setOverrides).catch(() => showToast('error', 'Failed to load overrides'));
  }

  useEffect(() => reload(employeeId), [employeeId, token]);

  // "What you'd be overriding FROM" — refetched whenever employee/rule type changes, since the
  // formula depends on both. Not kept in sync with overrideAmount itself (that's the admin's new
  // value, this is the system's current one) — see payrollApi.previewStatutoryAmount's own comment.
  useEffect(() => {
    if (!employeeId) { setPreview(null); return; }
    setPreviewLoading(true);
    payrollApi.previewStatutoryAmount(employeeId, ruleType, payGroupId, cycleStart, token)
      .then(setPreview)
      .catch(() => setPreview({ computedAmount: null, note: 'Could not load the computed amount' }))
      .finally(() => setPreviewLoading(false));
  }, [employeeId, ruleType, payGroupId, cycleStart, token]);

  const valid = !!employeeId && !!ruleType && Number(overrideAmount) >= 0 && overrideAmount !== '' && !!overrideReason.trim();

  async function submit() {
    if (!valid) return;
    setSubmitting(true);
    try {
      await payrollApi.createStatutoryOverride({
        employeeId, payGroupId, referenceDate: cycleStart, ruleType,
        overrideAmount: Number(overrideAmount), overrideReason: overrideReason.trim(),
      }, token);
      showToast('success', 'Override saved — regenerate the run to reflect it in the payslip');
      setOverrideAmount(''); setOverrideReason('');
      reload(employeeId);
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Failed to save override');
    } finally {
      setSubmitting(false);
    }
  }

  async function clear(o: StatutoryOverrideResponse) {
    try {
      await payrollApi.clearStatutoryOverride(o.employeeId, o.ruleType, payGroupId, o.cycleStart, token);
      showToast('success', 'Override cleared');
      reload(employeeId);
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Failed to clear override');
    }
  }

  return (
    <div>
      {canConfigure && (
        <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 14, padding: '18px 22px', marginBottom: 20 }}>
          <SectionHead title="Override Statutory Deduction" />
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: 12, marginBottom: 14 }}>
            <FormRow>
              <label style={labelStyle}>Employee *</label>
              <select value={employeeId} onChange={e => setEmployeeId(e.target.value)} style={inputStyle}>
                <option value="">Select…</option>
                {employees.map(e => <option key={e.userId} value={e.userId}>{e.fullName}</option>)}
              </select>
            </FormRow>
            <FormRow>
              <label style={labelStyle}>Rule Type *</label>
              <select value={ruleType} onChange={e => setRuleType(e.target.value)} style={inputStyle}>
                {RULE_TYPES.map(t => <option key={t} value={t}>{t.replace(/_/g, ' ')}</option>)}
              </select>
            </FormRow>
            <FormRow>
              <label style={labelStyle}>Override Amount *</label>
              <input type="number" min={0} value={overrideAmount} onChange={e => setOverrideAmount(e.target.value)} style={inputStyle} />
            </FormRow>
          </div>
          {employeeId && (
            <div style={{ fontSize: 12.5, color: 'var(--txt-mut)', marginBottom: 14, marginTop: -4 }}>
              {previewLoading
                ? 'Checking the currently computed amount…'
                : preview?.computedAmount != null
                  ? <>Currently computed (formula): <strong style={{ color: 'var(--txt)' }}>{fmtCurrency(preview.computedAmount)}</strong> — this is what you'd be overriding.</>
                  : preview?.note ?? null}
            </div>
          )}
          <FormRow>
            <label style={labelStyle}>Reason *</label>
            <textarea value={overrideReason} onChange={e => setOverrideReason(e.target.value)} style={{ ...inputStyle, minHeight: 70, resize: 'vertical' }} />
          </FormRow>
          <BtnPrimary onClick={submit} disabled={!valid || submitting}>{submitting ? 'Saving…' : 'Save Override'}</BtnPrimary>
        </div>
      )}

      {employeeId && (
        <div>
          <SectionHead title={`Overrides for ${nameFor(employeeId)}`} />
          <div style={panelStyle}>
            <table className="nf-pr-table" style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead><tr>{['Rule Type', 'Cycle', 'Amount', 'Reason', 'Overridden At', ''].map(h => <th key={h} style={thStyle}>{h}</th>)}</tr></thead>
              <tbody>
                {overrides.map(o => (
                  <tr key={o.id}>
                    <td style={tdStyle}>{o.ruleType.replace(/_/g, ' ')}</td>
                    <td style={tdStyle}>{fmtDate(o.cycleStart)} – {fmtDate(o.cycleEnd)}</td>
                    <td style={tdStyle}>{fmtCurrency(o.overrideAmount)}</td>
                    <td style={tdStyle}>{o.overrideReason}</td>
                    <td style={tdStyle}>{fmtDate(o.overriddenAt)}</td>
                    <td style={tdStyle}>
                      {canConfigure && <ActionDock items={[{ label: 'Clear', icon: Ban, onClick: () => clear(o) }]} />}
                    </td>
                  </tr>
                ))}
                {overrides.length === 0 && <EmptyRow cols={6} msg="No overrides for this employee." />}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </div>
  );
}
