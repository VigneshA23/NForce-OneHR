import { useState } from 'react';
import { Plus, Pencil, Ban, CheckCircle2 } from 'lucide-react';
import { ActionDock } from '../../components/ActionDock';
import { useAuthStore } from '../../store/authStore';
import { useToast } from '../../context/ToastContext';
import { payrollApi, type PayGroupResponse } from '../../api/payroll';
import {
  panelStyle, thStyle, tdStyle, EmptyRow, Modal, FormRow, labelStyle, inputStyle,
  BtnPrimary, BtnGhost, SectionHead,
} from './payrollShared';

interface PayGroupForm {
  name: string; legalEntity: string; currency: string;
  cycleStartDay: number; cycleEndDay: number; processingDay: number;
}

function emptyForm(): PayGroupForm {
  return { name: '', legalEntity: '', currency: 'INR', cycleStartDay: 26, cycleEndDay: 25, processingDay: 26 };
}

function fromExisting(pg: PayGroupResponse): PayGroupForm {
  return { name: pg.name, legalEntity: pg.legalEntity ?? '', currency: pg.currency, cycleStartDay: pg.cycleStartDay, cycleEndDay: pg.cycleEndDay, processingDay: pg.processingDay };
}

function PayGroupFormModal({ title, initial, onClose, onSubmit }: {
  title: string; initial: PayGroupForm; onClose: () => void; onSubmit: (form: PayGroupForm) => Promise<void>;
}) {
  const [form, setForm] = useState(initial);
  const [submitting, setSubmitting] = useState(false);
  const valid = !!form.name.trim() && form.cycleStartDay >= 1 && form.cycleStartDay <= 31
    && form.cycleEndDay >= 1 && form.cycleEndDay <= 31 && form.processingDay >= 1 && form.processingDay <= 31;

  async function submit() {
    if (!valid) return;
    setSubmitting(true);
    try { await onSubmit(form); onClose(); } finally { setSubmitting(false); }
  }

  return (
    <Modal title={title} subtitle="Cycle Start/End define the pay cycle window (e.g. 26–25); Processing Day is when payroll is run for that cycle." onClose={onClose}>
      <FormRow>
        <label style={labelStyle}>Name *</label>
        <input value={form.name} onChange={e => setForm(f => ({ ...f, name: e.target.value }))} style={inputStyle} placeholder="e.g. Default pay group" />
      </FormRow>
      <FormRow>
        <label style={labelStyle}>Legal Entity</label>
        <input value={form.legalEntity} onChange={e => setForm(f => ({ ...f, legalEntity: e.target.value }))} style={inputStyle} placeholder="optional" />
      </FormRow>
      <FormRow>
        <label style={labelStyle}>Currency</label>
        <input value={form.currency} onChange={e => setForm(f => ({ ...f, currency: e.target.value.toUpperCase() }))} style={inputStyle} maxLength={3} />
      </FormRow>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 10 }}>
        <FormRow>
          <label style={labelStyle}>Cycle Start Day *</label>
          <input type="number" min={1} max={31} value={form.cycleStartDay} onChange={e => setForm(f => ({ ...f, cycleStartDay: Number(e.target.value) }))} style={inputStyle} />
        </FormRow>
        <FormRow>
          <label style={labelStyle}>Cycle End Day *</label>
          <input type="number" min={1} max={31} value={form.cycleEndDay} onChange={e => setForm(f => ({ ...f, cycleEndDay: Number(e.target.value) }))} style={inputStyle} />
        </FormRow>
        <FormRow>
          <label style={labelStyle}>Processing Day *</label>
          <input type="number" min={1} max={31} value={form.processingDay} onChange={e => setForm(f => ({ ...f, processingDay: Number(e.target.value) }))} style={inputStyle} />
        </FormRow>
      </div>
      <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
        <BtnGhost onClick={onClose}>Cancel</BtnGhost>
        <BtnPrimary onClick={submit} disabled={!valid || submitting}>{submitting ? 'Saving…' : 'Save'}</BtnPrimary>
      </div>
    </Modal>
  );
}

export function PayGroupsTab({ payGroups, onChanged }: { payGroups: PayGroupResponse[]; onChanged: () => void }) {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const [showCreate, setShowCreate] = useState(false);
  const [editing, setEditing] = useState<PayGroupResponse | null>(null);

  async function create(form: PayGroupForm) {
    try {
      await payrollApi.createPayGroup({ ...form, legalEntity: form.legalEntity.trim() || undefined }, token);
      showToast('success', 'Pay group created');
      onChanged();
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Failed to create pay group');
    }
  }

  async function update(id: string, form: PayGroupForm) {
    try {
      await payrollApi.updatePayGroup(id, { ...form, legalEntity: form.legalEntity.trim() || undefined }, token);
      showToast('success', 'Pay group updated');
      onChanged();
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Failed to update pay group');
    }
  }

  async function toggleActive(pg: PayGroupResponse) {
    try {
      await payrollApi.updatePayGroup(pg.id, { active: !pg.active }, token);
      showToast('success', pg.active ? 'Pay group deactivated' : 'Pay group activated');
      onChanged();
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Failed to update pay group');
    }
  }

  return (
    <div>
      <SectionHead
        title="Pay Groups"
        action={<BtnPrimary onClick={() => setShowCreate(true)}><Plus size={14} style={{ marginRight: 6, verticalAlign: 'text-bottom' }} />Add Pay Group</BtnPrimary>}
      />
      <div style={panelStyle}>
        <table className="nf-pr-table" style={{ width: '100%', borderCollapse: 'collapse' }}>
          <thead>
            <tr>{['Name', 'Legal Entity', 'Currency', 'Cycle', 'Processing Day', 'Status', ''].map(h => <th key={h} style={thStyle}>{h}</th>)}</tr>
          </thead>
          <tbody>
            {payGroups.map(pg => (
              <tr key={pg.id}>
                <td style={tdStyle}>{pg.name}</td>
                <td style={tdStyle}>{pg.legalEntity ?? '–'}</td>
                <td style={tdStyle}>{pg.currency}</td>
                <td style={tdStyle}>{pg.cycleStartDay} – {pg.cycleEndDay}</td>
                <td style={tdStyle}>{pg.processingDay}</td>
                <td style={tdStyle}>{pg.active ? 'Active' : 'Inactive'}</td>
                <td style={tdStyle}>
                  <ActionDock items={[
                    { label: 'Edit', icon: Pencil, onClick: () => setEditing(pg) },
                    { label: pg.active ? 'Deactivate' : 'Activate', icon: pg.active ? Ban : CheckCircle2, onClick: () => toggleActive(pg) },
                  ]} />
                </td>
              </tr>
            ))}
            {payGroups.length === 0 && <EmptyRow cols={7} msg="No pay groups yet — add one above." />}
          </tbody>
        </table>
      </div>

      {showCreate && (
        <PayGroupFormModal title="Add Pay Group" initial={emptyForm()} onClose={() => setShowCreate(false)} onSubmit={create} />
      )}
      {editing && (
        <PayGroupFormModal title="Edit Pay Group" initial={fromExisting(editing)} onClose={() => setEditing(null)} onSubmit={form => update(editing.id, form)} />
      )}
    </div>
  );
}
