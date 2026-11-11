import { useState } from 'react';
import { Plus, Pencil, Ban, CheckCircle2 } from 'lucide-react';
import { ActionDock } from '../../components/ActionDock';
import { useAuthStore } from '../../store/authStore';
import { useToast } from '../../context/ToastContext';
import { payrollApi, type SalaryComponentCatalogResponse } from '../../api/payroll';
import {
  panelStyle, thStyle, tdStyle, EmptyRow, Modal, FormRow, labelStyle, inputStyle,
  BtnPrimary, BtnGhost, SectionHead,
} from './payrollShared';

const TYPE_LABEL: Record<string, string> = {
  EARNING: 'Earning', AD_HOC_EARNING: 'Ad-hoc Earning', DEDUCTION: 'Deduction',
  AD_HOC_DEDUCTION: 'Ad-hoc Deduction', REIMBURSEMENT: 'Reimbursement',
};

interface ComponentForm {
  name: string; type: string; taxable: boolean; taxSection: string;
  includedInCtc: boolean; shownOnPayslip: boolean; prorateOnLop: boolean;
}

function emptyForm(): ComponentForm {
  return { name: '', type: 'EARNING', taxable: true, taxSection: '', includedInCtc: true, shownOnPayslip: true, prorateOnLop: true };
}

function fromExisting(c: SalaryComponentCatalogResponse): ComponentForm {
  return { name: c.name, type: c.type, taxable: c.taxable, taxSection: c.taxSection ?? '', includedInCtc: c.includedInCtc, shownOnPayslip: c.shownOnPayslip, prorateOnLop: c.prorateOnLop };
}

function ComponentFormModal({ title, initial, onClose, onSubmit }: {
  title: string; initial: ComponentForm; onClose: () => void; onSubmit: (form: ComponentForm) => Promise<void>;
}) {
  const [form, setForm] = useState(initial);
  const [submitting, setSubmitting] = useState(false);
  const valid = !!form.name.trim();

  async function submit() {
    if (!valid) return;
    setSubmitting(true);
    try { await onSubmit(form); onClose(); } finally { setSubmitting(false); }
  }

  return (
    <Modal title={title} onClose={onClose}>
      <FormRow>
        <label style={labelStyle}>Name *</label>
        <input value={form.name} onChange={e => setForm(f => ({ ...f, name: e.target.value }))} style={inputStyle} placeholder="e.g. HRA" />
      </FormRow>
      <FormRow>
        <label style={labelStyle}>Type *</label>
        <select value={form.type} onChange={e => setForm(f => ({ ...f, type: e.target.value }))} style={inputStyle}>
          {Object.entries(TYPE_LABEL).map(([val, label]) => <option key={val} value={val}>{label}</option>)}
        </select>
      </FormRow>
      <FormRow>
        <label style={labelStyle}>Tax Section</label>
        <input value={form.taxSection} onChange={e => setForm(f => ({ ...f, taxSection: e.target.value }))} style={inputStyle} placeholder="e.g. 10(13A) — optional" />
      </FormRow>
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8, marginBottom: 16 }}>
        {([
          ['taxable', 'Taxable'], ['includedInCtc', 'Included in CTC'],
          ['shownOnPayslip', 'Shown on Payslip'], ['prorateOnLop', 'Prorate on LOP'],
        ] as const).map(([key, label]) => (
          <label key={key} style={{ display: 'flex', alignItems: 'center', gap: 7, fontSize: 13, color: 'var(--txt-mut)' }}>
            <input type="checkbox" checked={form[key]} onChange={e => setForm(f => ({ ...f, [key]: e.target.checked }))} />
            {label}
          </label>
        ))}
      </div>
      <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
        <BtnGhost onClick={onClose}>Cancel</BtnGhost>
        <BtnPrimary onClick={submit} disabled={!valid || submitting}>{submitting ? 'Saving…' : 'Save'}</BtnPrimary>
      </div>
    </Modal>
  );
}

export function SalaryComponentsTab({ catalog, onChanged }: { catalog: SalaryComponentCatalogResponse[]; onChanged: () => void }) {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const [showCreate, setShowCreate] = useState(false);
  const [editing, setEditing] = useState<SalaryComponentCatalogResponse | null>(null);

  async function create(form: ComponentForm) {
    try {
      await payrollApi.createSalaryComponent({ ...form, taxSection: form.taxSection.trim() || undefined }, token);
      showToast('success', 'Component created');
      onChanged();
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Failed to create component');
    }
  }

  async function update(id: string, form: ComponentForm) {
    try {
      await payrollApi.updateSalaryComponent(id, { ...form, taxSection: form.taxSection.trim() || undefined }, token);
      showToast('success', 'Component updated');
      onChanged();
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Failed to update component');
    }
  }

  async function toggleActive(c: SalaryComponentCatalogResponse) {
    try {
      await payrollApi.updateSalaryComponent(c.id, { active: !c.active }, token);
      showToast('success', c.active ? 'Component deactivated' : 'Component activated');
      onChanged();
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Failed to update component');
    }
  }

  return (
    <div>
      <SectionHead
        title="Salary Components"
        action={<BtnPrimary onClick={() => setShowCreate(true)}><Plus size={14} style={{ marginRight: 6, verticalAlign: 'text-bottom' }} />Add Component</BtnPrimary>}
      />
      <div style={panelStyle}>
        <table className="nf-pr-table" style={{ width: '100%', borderCollapse: 'collapse' }}>
          <thead>
            <tr>{['Name', 'Type', 'Taxable', 'In CTC', 'On Payslip', 'Prorate on LOP', 'Status', ''].map(h => <th key={h} style={thStyle}>{h}</th>)}</tr>
          </thead>
          <tbody>
            {catalog.map(c => (
              <tr key={c.id}>
                <td style={tdStyle}>{c.name}</td>
                <td style={tdStyle}>{TYPE_LABEL[c.type] ?? c.type}</td>
                <td style={tdStyle}>{c.taxable ? 'Yes' : 'No'}</td>
                <td style={tdStyle}>{c.includedInCtc ? 'Yes' : 'No'}</td>
                <td style={tdStyle}>{c.shownOnPayslip ? 'Yes' : 'No'}</td>
                <td style={tdStyle}>{c.prorateOnLop ? 'Yes' : 'No'}</td>
                <td style={tdStyle}>{c.active ? 'Active' : 'Inactive'}</td>
                <td style={tdStyle}>
                  <ActionDock items={[
                    { label: 'Edit', icon: Pencil, onClick: () => setEditing(c) },
                    { label: c.active ? 'Deactivate' : 'Activate', icon: c.active ? Ban : CheckCircle2, onClick: () => toggleActive(c) },
                  ]} />
                </td>
              </tr>
            ))}
            {catalog.length === 0 && <EmptyRow cols={8} msg="No salary components yet — add one above." />}
          </tbody>
        </table>
      </div>

      {showCreate && (
        <ComponentFormModal title="Add Salary Component" initial={emptyForm()} onClose={() => setShowCreate(false)} onSubmit={create} />
      )}
      {editing && (
        <ComponentFormModal title="Edit Salary Component" initial={fromExisting(editing)} onClose={() => setEditing(null)} onSubmit={form => update(editing.id, form)} />
      )}
    </div>
  );
}
