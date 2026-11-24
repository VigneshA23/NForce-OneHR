import { useState } from 'react';
import { Plus, Pencil, Ban, CheckCircle2, Wallet } from 'lucide-react';
import { ActionDock } from '../../components/ActionDock';
import { useAuthStore } from '../../store/authStore';
import { useToast } from '../../context/ToastContext';
import { payrollApi, type PayGroupResponse } from '../../api/payroll';
import {
  panelStyle, thStyle, tdStyle, EmptyRow, Drawer, FormRow, labelStyle, inputStyle,
  BtnPrimary, BtnGhost, SectionHead, ToggleSwitch, FilterBar, WithFaqPanel, type FaqItem,
} from './payrollShared';

const FAQ_ITEMS: FaqItem[] = [
  { q: 'What is a pay group, and do I need more than one?', a: 'A pay group defines a pay cycle (start/end/processing day), currency and legal entity that a set of employees is paid under. Most companies need just one - add more only for separate legal entities or genuinely different pay cycles/currencies.' },
  { q: 'What do Cycle Start/End Day and Processing Day control?', a: 'Cycle Start/End define the pay period window (e.g. 26 to 25 means the 26th of the previous month through the 25th of this one). Processing Day is when payroll is actually run for that cycle - they don’t have to be the same day.' },
  { q: 'What happens if I deactivate a pay group?', a: 'It stops appearing as an option for new assignments, but existing employees, runs and payslips already tied to it are untouched. Reactivate it any time with the same action.' },
  { q: 'Can I change Legal Entity or Currency later?', a: 'No - the drawer shows them as a read-only reference once a pay group exists, since changing currency or entity after payslips have been generated under it would be misleading. Create a new pay group instead. Address is the exception - it’s just letterhead text, so it stays editable any time.' },
];

interface PayGroupForm {
  name: string; legalEntity: string; address: string; currency: string;
  cycleStartDay: number; cycleEndDay: number; processingDay: number; active: boolean;
}

function emptyForm(): PayGroupForm {
  return { name: '', legalEntity: '', address: '', currency: 'INR', cycleStartDay: 26, cycleEndDay: 25, processingDay: 26, active: true };
}

function fromExisting(pg: PayGroupResponse): PayGroupForm {
  return {
    name: pg.name, legalEntity: pg.legalEntity ?? '', address: pg.address ?? '', currency: pg.currency,
    cycleStartDay: pg.cycleStartDay, cycleEndDay: pg.cycleEndDay, processingDay: pg.processingDay, active: pg.active,
  };
}

/** Keka's Pay Group header card shows Legal Entity/Currency as fixed label/value pairs rather
 * than editable fields on the day-to-day screen - mirrored here as the drawer's grey read-only
 * block when editing an existing pay group (both stay editable while creating a new one). */
function PayGroupDrawer({ title, initial, isNew, onClose, onSubmit }: {
  title: string; initial: PayGroupForm; isNew: boolean; onClose: () => void; onSubmit: (form: PayGroupForm) => Promise<void>;
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
    <Drawer
      title={title}
      subtitle="Set the pay cycle and when payroll is processed."
      onClose={onClose}
      readOnlyBlock={!isNew ? (
        <div style={{ display: 'flex', gap: 28, flexWrap: 'wrap' }}>
          <div><div style={labelStyle}>Legal Entity</div><div style={{ fontSize: 14, color: 'var(--txt)' }}>{form.legalEntity || '-'}</div></div>
          <div><div style={labelStyle}>Currency</div><div style={{ fontSize: 14, color: 'var(--txt)' }}>{form.currency}</div></div>
        </div>
      ) : undefined}
      footer={(
        <>
          <BtnGhost onClick={onClose}>Discard</BtnGhost>
          <BtnPrimary onClick={submit} disabled={!valid || submitting}>{submitting ? 'Saving…' : isNew ? 'Create' : 'Update'}</BtnPrimary>
        </>
      )}
    >
      {!isNew && (
        <div style={{ marginBottom: 18 }}>
          <ToggleSwitch
            checked={form.active} onChange={v => setForm(f => ({ ...f, active: v }))}
            label={<span style={{ fontSize: 13.5, color: 'var(--txt)' }}>{form.active ? 'This pay group is Active' : 'This pay group is Inactive'}</span>}
          />
        </div>
      )}
      <div className="nf-pr-dsec-title">Basics</div>
      <FormRow>
        <label style={labelStyle}>Name</label>
        <input value={form.name} onChange={e => setForm(f => ({ ...f, name: e.target.value }))} style={inputStyle} placeholder="e.g. Default pay group" />
      </FormRow>
      <FormRow>
        <label style={labelStyle}>Address <span style={{ color: 'var(--txt-dim)', fontWeight: 400, textTransform: 'none', letterSpacing: 0 }}>(Optional)</span></label>
        <textarea
          value={form.address} onChange={e => setForm(f => ({ ...f, address: e.target.value }))}
          style={{ ...inputStyle, minHeight: 64, resize: 'vertical' }}
          placeholder={'e.g. 4th Floor, Example Towers\nHyderabad, Telangana 500081'}
        />
        <div style={{ fontSize: 11.5, color: 'var(--txt-dim)', marginTop: 4 }}>Printed under the company name on every payslip for this pay group.</div>
      </FormRow>
      {isNew && (
        <>
          <FormRow>
            <label style={labelStyle}>Legal Entity <span style={{ color: 'var(--txt-dim)', fontWeight: 400, textTransform: 'none', letterSpacing: 0 }}>(Optional)</span></label>
            <input value={form.legalEntity} onChange={e => setForm(f => ({ ...f, legalEntity: e.target.value }))} style={inputStyle} />
          </FormRow>
          <FormRow>
            <label style={labelStyle}>Currency</label>
            <input value={form.currency} onChange={e => setForm(f => ({ ...f, currency: e.target.value.toUpperCase() }))} style={inputStyle} maxLength={3} />
          </FormRow>
        </>
      )}
      <div style={{ marginTop: 22 }}>
        <div className="nf-pr-dsec-title">Pay schedule</div>
        <div style={{ fontSize: 12, color: 'var(--txt-dim)', marginBottom: 12 }}>Cycle Start and End define the pay cycle window (for example 26 to 25). Processing Day is when payroll is run for that cycle.</div>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 10 }}>
          <FormRow>
            <label style={labelStyle}>Cycle Start Day</label>
            <input type="number" min={1} max={31} value={form.cycleStartDay} onChange={e => setForm(f => ({ ...f, cycleStartDay: Number(e.target.value) }))} style={inputStyle} />
          </FormRow>
          <FormRow>
            <label style={labelStyle}>Cycle End Day</label>
            <input type="number" min={1} max={31} value={form.cycleEndDay} onChange={e => setForm(f => ({ ...f, cycleEndDay: Number(e.target.value) }))} style={inputStyle} />
          </FormRow>
          <FormRow>
            <label style={labelStyle}>Processing Day</label>
            <input type="number" min={1} max={31} value={form.processingDay} onChange={e => setForm(f => ({ ...f, processingDay: Number(e.target.value) }))} style={inputStyle} />
          </FormRow>
        </div>
        <div className="nf-pr-formula">
          <span className="nf-pr-formula-k">Schedule</span>
          <span className="nf-pr-formula-v">Cycle runs from day {form.cycleStartDay} to day {form.cycleEndDay}, processed on day {form.processingDay}</span>
        </div>
      </div>
    </Drawer>
  );
}

export function PayGroupsTab({ payGroups, onChanged }: { payGroups: PayGroupResponse[]; onChanged: () => void }) {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const [showCreate, setShowCreate] = useState(false);
  const [editing, setEditing] = useState<PayGroupResponse | null>(null);
  const [search, setSearch] = useState('');

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

  const filtered = payGroups.filter(pg => pg.name.toLowerCase().includes(search.trim().toLowerCase()));

  return (
    <WithFaqPanel items={FAQ_ITEMS} title="Pay Groups Help">
    <div>
      <SectionHead
        icon={Wallet}
        title="Pay Groups"
        subtitle="Each pay group has its own pay cycle, processing day and payslip settings."
        action={<BtnPrimary onClick={() => setShowCreate(true)}><Plus size={14} style={{ marginRight: 6, verticalAlign: 'text-bottom' }} />Add Pay Group</BtnPrimary>}
      />
      <FilterBar search={search} onSearchChange={setSearch} placeholder="Search pay group" />
      <div style={panelStyle}>
        <table className="nf-pr-table nf-pr-center" style={{ width: '100%', borderCollapse: 'collapse' }}>
          <thead>
            <tr>{['Name', 'Legal Entity', 'Currency', 'Cycle', 'Processing Day', 'Status', ''].map(h => <th key={h} style={thStyle}>{h}</th>)}</tr>
          </thead>
          <tbody>
            {filtered.map(pg => (
              <tr key={pg.id}>
                <td style={tdStyle}>{pg.name}</td>
                <td style={tdStyle}>{pg.legalEntity ?? '-'}</td>
                <td style={tdStyle}>{pg.currency}</td>
                <td style={tdStyle}>{pg.cycleStartDay} to {pg.cycleEndDay}</td>
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
            {filtered.length === 0 && (
              <EmptyRow cols={7} msg={payGroups.length === 0 ? 'No pay groups yet - add one above.' : 'No matches for your search.'} />
            )}
          </tbody>
        </table>
      </div>

      {showCreate && (
        <PayGroupDrawer title="Add Pay Group" initial={emptyForm()} isNew onClose={() => setShowCreate(false)} onSubmit={create} />
      )}
      {editing && (
        <PayGroupDrawer title={`Edit Pay Group - ${editing.name}`} initial={fromExisting(editing)} isNew={false} onClose={() => setEditing(null)} onSubmit={form => update(editing.id, form)} />
      )}
    </div>
    </WithFaqPanel>
  );
}
