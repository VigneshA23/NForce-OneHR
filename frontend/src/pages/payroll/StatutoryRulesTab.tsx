import { useEffect, useState } from 'react';
import { Plus, History, CheckCircle2, AlertCircle } from 'lucide-react';
import { ActionDock } from '../../components/ActionDock';
import { useAuthStore } from '../../store/authStore';
import { useToast } from '../../context/ToastContext';
import { payrollApi, type StatutoryRuleResponse } from '../../api/payroll';
import {
  panelStyle, thStyle, tdStyle, fmtDate, EmptyRow, Modal, FormRow, labelStyle, inputStyle,
  BtnPrimary, BtnGhost, SectionHead, StatTile,
} from './payrollShared';

// Only these three are actually read by StatutoryDeductionService.computeDeductions — TDS and
// Gratuity are explicitly out of scope (see that service's own Javadoc: TDS needs a full annual
// income-tax slab computation, Gratuity is an employer-side accrual, never a payslip line item).
// A rule saved under any other name is valid data but will never be computed by payroll — the
// note below the field says so; this list is deliberately not open to suggesting them.
const RULE_TYPE_SUGGESTIONS = ['PF', 'ESI', 'PROFESSIONAL_TAX'];
const RULE_TYPE_LABEL: Record<string, string> = { PF: 'Provident Fund', ESI: 'ESI', PROFESSIONAL_TAX: 'Professional Tax' };

function ReviseRuleModal({ onClose, onCreated }: { onClose: () => void; onCreated: (r: StatutoryRuleResponse) => void }) {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const [country, setCountry] = useState('IN');
  const [ruleType, setRuleType] = useState('');
  const [slabConfig, setSlabConfig] = useState('{\n  \n}');
  const [effectiveFrom, setEffectiveFrom] = useState('');
  const [jsonError, setJsonError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  function checkJson(value: string) {
    setSlabConfig(value);
    try { JSON.parse(value); setJsonError(null); } catch { setJsonError('Not valid JSON'); }
  }

  const valid = !!country.trim() && !!ruleType.trim() && !!slabConfig.trim() && !jsonError && !!effectiveFrom;

  async function submit() {
    if (!valid) return;
    setSubmitting(true);
    try {
      const rule = await payrollApi.createOrReviseStatutoryRule({ country: country.trim(), ruleType: ruleType.trim(), slabConfig: slabConfig.trim(), effectiveFrom }, token);
      onCreated(rule);
      onClose();
      showToast('success', 'Statutory rule saved');
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Failed to save rule');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Modal
      title="Add / Revise Statutory Rule"
      subtitle="Creates a new version effective from the date below. An existing current rule for the same country + rule type is automatically closed out, not overwritten — its history is preserved."
      onClose={onClose}
    >
      <FormRow>
        <label style={labelStyle}>Country *</label>
        <input value={country} onChange={e => setCountry(e.target.value.toUpperCase())} style={inputStyle} maxLength={2} placeholder="e.g. IN" />
      </FormRow>
      <FormRow>
        <label style={labelStyle}>Rule Type *</label>
        <input list="rule-type-suggestions" value={ruleType} onChange={e => setRuleType(e.target.value.toUpperCase())} style={inputStyle} placeholder="e.g. PF" />
        <datalist id="rule-type-suggestions">
          {RULE_TYPE_SUGGESTIONS.map(t => <option key={t} value={t} />)}
        </datalist>
        {ruleType && !RULE_TYPE_SUGGESTIONS.includes(ruleType) && (
          <div style={{ fontSize: 12, color: 'var(--warn)', marginTop: 4 }}>
            Payroll only computes PF, ESI and Professional Tax today — a rule saved under "{ruleType}" will be stored but never applied to a payslip.
          </div>
        )}
      </FormRow>
      <FormRow>
        <label style={labelStyle}>Effective From *</label>
        <input type="date" value={effectiveFrom} onChange={e => setEffectiveFrom(e.target.value)} style={inputStyle} />
      </FormRow>
      <FormRow>
        <label style={labelStyle}>Slab Config (JSON) *</label>
        <textarea
          value={slabConfig}
          onChange={e => checkJson(e.target.value)}
          style={{ ...inputStyle, minHeight: 140, resize: 'vertical', fontFamily: 'monospace', fontSize: 12 }}
        />
        {jsonError && <div style={{ color: 'var(--risk)', fontSize: 12, marginTop: 4 }}>{jsonError}</div>}
      </FormRow>
      <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
        <BtnGhost onClick={onClose}>Cancel</BtnGhost>
        <BtnPrimary onClick={submit} disabled={!valid || submitting}>{submitting ? 'Saving…' : 'Save'}</BtnPrimary>
      </div>
    </Modal>
  );
}

function HistoryModal({ country, ruleType, onClose }: { country: string; ruleType: string; onClose: () => void }) {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const [history, setHistory] = useState<StatutoryRuleResponse[]>([]);

  useEffect(() => {
    payrollApi.getStatutoryRuleHistory(country, ruleType, token).then(setHistory).catch(() => showToast('error', 'Failed to load history'));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [country, ruleType, token]);

  return (
    <Modal title={`${ruleType} (${country}) — History`} onClose={onClose}>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
        {history.map(r => (
          <div key={r.id} style={{ border: '1px solid var(--line)', borderRadius: 8, padding: 10 }}>
            <div style={{ fontSize: 12, color: 'var(--txt-mut)', marginBottom: 6 }}>
              {fmtDate(r.effectiveFrom)} – {r.effectiveTo ? fmtDate(r.effectiveTo) : 'Current'}
            </div>
            <pre style={{ margin: 0, fontSize: 11, whiteSpace: 'pre-wrap', color: 'var(--txt)' }}>{r.slabConfig}</pre>
          </div>
        ))}
        {history.length === 0 && <div style={{ color: 'var(--txt-dim)', fontSize: 13 }}>No history found.</div>}
      </div>
    </Modal>
  );
}

export function StatutoryRulesTab() {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const [rules, setRules] = useState<StatutoryRuleResponse[]>([]);
  const [showCreate, setShowCreate] = useState(false);
  const [historyFor, setHistoryFor] = useState<{ country: string; ruleType: string } | null>(null);

  function reload() {
    payrollApi.listStatutoryRules(token).then(setRules).catch(() => showToast('error', 'Failed to load statutory rules'));
  }

  useEffect(reload, [token]);

  return (
    <div>
      {/* At-a-glance: is each statutory deduction actually configured, so a rule saved under the
          wrong country/name (or just never created) is obvious without reading the table below. */}
      <div className="nf-pr-tiles" style={{ marginBottom: 22 }}>
        {RULE_TYPE_SUGGESTIONS.map(type => {
          const rule = rules.find(r => r.ruleType === type);
          return (
            <StatTile
              key={type}
              tone={rule ? 'var(--ok)' : 'var(--warn)'}
              icon={rule ? <CheckCircle2 size={20} /> : <AlertCircle size={20} />}
              label={RULE_TYPE_LABEL[type] ?? type}
              value={rule ? 'Configured' : 'Not Set Up'}
              hint={rule ? `${rule.country} · effective ${fmtDate(rule.effectiveFrom)}` : 'No rule saved for this yet — payslips will skip this deduction'}
            />
          );
        })}
      </div>

      <SectionHead
        title="Statutory Rules"
        action={<BtnPrimary onClick={() => setShowCreate(true)}><Plus size={14} style={{ marginRight: 6, verticalAlign: 'text-bottom' }} />Add / Revise Rule</BtnPrimary>}
      />
      <div style={{ fontSize: 13, color: 'var(--txt-mut)', marginBottom: 14 }}>
        HR-editable config for statutory slabs (PF, ESI, Professional Tax, TDS, etc.) — version-dated, never hardcoded in application code.
      </div>
      <div style={panelStyle}>
        <table className="nf-pr-table" style={{ width: '100%', borderCollapse: 'collapse' }}>
          <thead>
            <tr>{['Country', 'Rule Type', 'Effective From', 'Created', ''].map(h => <th key={h} style={thStyle}>{h}</th>)}</tr>
          </thead>
          <tbody>
            {rules.map(r => (
              <tr key={r.id}>
                <td style={tdStyle}>{r.country}</td>
                <td style={tdStyle}>{r.ruleType}</td>
                <td style={tdStyle}>{fmtDate(r.effectiveFrom)}</td>
                <td style={tdStyle}>{fmtDate(r.createdAt)}</td>
                <td style={tdStyle}>
                  <ActionDock items={[{ label: 'History', icon: History, onClick: () => setHistoryFor({ country: r.country, ruleType: r.ruleType }) }]} />
                </td>
              </tr>
            ))}
            {rules.length === 0 && <EmptyRow cols={5} msg="No statutory rules configured yet." />}
          </tbody>
        </table>
      </div>

      {showCreate && (
        <ReviseRuleModal onClose={() => setShowCreate(false)} onCreated={() => reload()} />
      )}
      {historyFor && (
        <HistoryModal country={historyFor.country} ruleType={historyFor.ruleType} onClose={() => setHistoryFor(null)} />
      )}
    </div>
  );
}
