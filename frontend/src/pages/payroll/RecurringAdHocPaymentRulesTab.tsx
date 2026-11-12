import { useEffect, useState } from 'react';
import { Plus, Pause, Play } from 'lucide-react';
import { ActionDock } from '../../components/ActionDock';
import { useAuthStore } from '../../store/authStore';
import { useToast } from '../../context/ToastContext';
import {
  payrollApi, type RecurringAdHocPaymentRuleResponse, type SalaryComponentCatalogResponse, type PayGroupResponse,
} from '../../api/payroll';
import {
  panelStyle, thStyle, tdStyle, fmtCurrency, fmtDateTime, EmptyRow, Modal, FormRow, labelStyle,
  inputStyle, BtnPrimary, BtnGhost, SectionHead, NoPermissionNote, ConfirmDialog,
} from './payrollShared';

function ActiveBadge({ active }: { active: boolean }) {
  const tone = active ? 'var(--ok)' : 'var(--txt-dim)';
  return (
    <span className="nf-asset-pill" style={{
      background: `color-mix(in srgb, ${tone} 13%, transparent)`, color: tone,
      border: `1px solid color-mix(in srgb, ${tone} 30%, transparent)`,
    }}>
      {active ? 'Active' : 'Inactive'}
    </span>
  );
}

function AddRuleModal({ components, payGroups, onClose, onCreated }: {
  components: SalaryComponentCatalogResponse[];
  payGroups: PayGroupResponse[];
  onClose: () => void;
  onCreated: (r: RecurringAdHocPaymentRuleResponse) => void;
}) {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const [componentName, setComponentName] = useState(components[0]?.name ?? '');
  const [payGroupId, setPayGroupId] = useState('');
  const [amount, setAmount] = useState('');
  const [reason, setReason] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const valid = !!componentName && Number(amount) > 0 && !!reason.trim();

  async function submit() {
    if (!valid) return;
    setSubmitting(true);
    try {
      const rule = await payrollApi.createRecurringAdHocPaymentRule(
        { payGroupId: payGroupId || null, componentName, amount: Number(amount), reason: reason.trim() }, token);
      onCreated(rule);
      onClose();
      showToast('success', 'Recurring payment rule created — it will apply from the next payroll run generated');
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Failed to create rule');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Modal title="Add Recurring Payment Rule" subtitle="Applied automatically to every employee on every payroll run generated from now on — no need to re-add it by hand each cycle." onClose={onClose}>
      <FormRow>
        <label style={labelStyle}>Component *</label>
        <select value={componentName} onChange={e => setComponentName(e.target.value)} style={inputStyle}>
          {components.map(c => <option key={c.id} value={c.name}>{c.name}</option>)}
        </select>
      </FormRow>
      <FormRow>
        <label style={labelStyle}>Pay Group</label>
        <select value={payGroupId} onChange={e => setPayGroupId(e.target.value)} style={inputStyle}>
          <option value="">All Pay Groups</option>
          {payGroups.map(pg => <option key={pg.id} value={pg.id}>{pg.name}</option>)}
        </select>
      </FormRow>
      <FormRow>
        <label style={labelStyle}>Amount (per employee) *</label>
        <input type="number" min={0} step="0.01" value={amount} onChange={e => setAmount(e.target.value)} style={inputStyle} />
      </FormRow>
      <FormRow>
        <label style={labelStyle}>Reason *</label>
        <textarea value={reason} onChange={e => setReason(e.target.value)} style={{ ...inputStyle, minHeight: 70, resize: 'vertical' }} />
      </FormRow>
      <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
        <BtnGhost onClick={onClose}>Cancel</BtnGhost>
        <BtnPrimary onClick={submit} disabled={!valid || submitting}>{submitting ? 'Adding…' : 'Add Rule'}</BtnPrimary>
      </div>
    </Modal>
  );
}

export function RecurringAdHocPaymentRulesTab({ catalog, payGroups, canConfigure }: {
  catalog: SalaryComponentCatalogResponse[];
  payGroups: PayGroupResponse[];
  canConfigure: boolean;
}) {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const [rules, setRules] = useState<RecurringAdHocPaymentRuleResponse[]>([]);
  const [showAdd, setShowAdd] = useState(false);
  const [toggling, setToggling] = useState<RecurringAdHocPaymentRuleResponse | null>(null);

  const components = catalog.filter(c => c.type === 'AD_HOC_EARNING' && c.active);

  function reload() {
    payrollApi.listRecurringAdHocPaymentRules(token).then(setRules).catch(() => showToast('error', 'Failed to load recurring payment rules'));
  }

  useEffect(reload, [token]);

  async function toggle(rule: RecurringAdHocPaymentRuleResponse) {
    try {
      const updated = await payrollApi.setRecurringAdHocPaymentRuleActive(rule.id, !rule.active, token);
      setRules(prev => prev.map(r => (r.id === updated.id ? updated : r)));
      showToast('success', updated.active ? 'Rule reactivated' : 'Rule deactivated');
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Failed to update rule');
    }
  }

  return (
    <div>
      <SectionHead
        title="Recurring Payments"
        action={canConfigure ? (
          <BtnPrimary onClick={() => setShowAdd(true)}><Plus size={14} style={{ marginRight: 6, verticalAlign: 'text-bottom' }} />Add Rule</BtnPrimary>
        ) : <NoPermissionNote action="add recurring payment rules" />}
      />
      <p style={{ marginTop: -6, marginBottom: 16, fontSize: 13, color: 'var(--txt-mut)' }}>
        A standing allowance applied to every employee on every new or regenerated payroll run — set it up once here instead of adding it by hand every cycle under a run's Ad-hoc Payments tab.
      </p>
      <div style={panelStyle}>
        <table className="nf-pr-table" style={{ width: '100%', borderCollapse: 'collapse' }}>
          <thead>
            <tr>{['Component', 'Pay Group', 'Amount', 'Reason', 'Status', 'Created By', 'Created At', ''].map(h => <th key={h} style={thStyle}>{h}</th>)}</tr>
          </thead>
          <tbody>
            {rules.map(r => (
              <tr key={r.id}>
                <td style={tdStyle}>{r.componentName}</td>
                <td style={tdStyle}>{r.payGroupName}</td>
                <td style={tdStyle}>{fmtCurrency(r.amount)}</td>
                <td style={tdStyle}>{r.reason}</td>
                <td style={tdStyle}><ActiveBadge active={r.active} /></td>
                <td style={tdStyle}>{r.createdByName}</td>
                <td style={tdStyle}>{fmtDateTime(r.createdAt)}</td>
                <td style={tdStyle}>
                  {canConfigure && (
                    <ActionDock items={[
                      r.active
                        ? { label: 'Deactivate', icon: Pause, danger: true, onClick: () => setToggling(r) }
                        : { label: 'Reactivate', icon: Play, onClick: () => setToggling(r) },
                    ]} />
                  )}
                </td>
              </tr>
            ))}
            {rules.length === 0 && <EmptyRow cols={8} msg="No recurring payment rules configured." />}
          </tbody>
        </table>
      </div>
      {showAdd && (
        <AddRuleModal
          components={components}
          payGroups={payGroups}
          onClose={() => setShowAdd(false)}
          onCreated={r => setRules(prev => [r, ...prev])}
        />
      )}
      {toggling && (
        <ConfirmDialog
          title={toggling.active ? 'Deactivate Rule' : 'Reactivate Rule'}
          message={toggling.active
            ? `Stop applying ${toggling.componentName} (${fmtCurrency(toggling.amount)}) automatically to new payroll runs? Already-generated runs keep what was already added.`
            : `Resume applying ${toggling.componentName} (${fmtCurrency(toggling.amount)}) automatically to every new payroll run?`}
          confirmLabel={toggling.active ? 'Deactivate' : 'Reactivate'}
          onClose={() => setToggling(null)}
          onConfirm={() => toggle(toggling)}
        />
      )}
    </div>
  );
}
