import { useEffect, useState } from 'react';
import { Plus, Pencil, Ban, CheckCircle2, ArrowUp, ArrowDown } from 'lucide-react';
import { ActionDock } from '../../components/ActionDock';
import { TabBar, type TabDef } from '../../components/TabBar';
import { useAuthStore } from '../../store/authStore';
import { useToast } from '../../context/ToastContext';
import { payrollApi, type SalaryComponentCatalogResponse } from '../../api/payroll';
import {
  panelStyle, thStyle, tdStyle, EmptyRow, Drawer, FormRow, labelStyle, inputStyle,
  BtnPrimary, BtnGhost, SectionHead, ToggleSwitch, FilterBar, WithFaqPanel, type FaqItem,
} from './payrollShared';

const FAQ_ITEMS: FaqItem[] = [
  { q: 'What’s the difference between the three tabs here?', a: 'Components is the master catalog - create, edit, activate or deactivate. Order of Components sets the sequence components print in. Accounting Code records an optional ledger/GL reference per component.' },
  { q: 'What does "Shown on Payslip" actually control?', a: "When off, that component is left off the generated payslip PDF entirely - even though it still counts toward CTC and still appears in the salary structure. Useful for internal-only deductions you don't want an employee to see as a line item." },
  { q: 'What does the Order of Components sequence actually affect?', a: 'The order components print in everywhere a component list is shown - payslip, pay register, reports and salary breakup. Drag the arrows into the order you want, then Save Order; it only takes effect once saved.' },
  { q: 'Can I change a component’s type after creating it?', a: 'No - Component Type is fixed once a component exists (shown read-only in the Edit drawer). If you got it wrong, deactivate it and create a new one instead; a component already used by a structure or a processed payroll run can’t be deleted.' },
  { q: 'What is Accounting Code used for?', a: 'Just a reference ledger/GL code for your own accounting system - this app doesn’t validate or sync it against anything external.' },
];

const TYPE_LABEL: Record<string, string> = {
  EARNING: 'Earning', AD_HOC_EARNING: 'Ad-hoc Earning', DEDUCTION: 'Deduction',
  AD_HOC_DEDUCTION: 'Ad-hoc Deduction', REIMBURSEMENT: 'Reimbursement', CONTRIBUTION: 'Contribution',
};

interface ComponentForm {
  name: string; type: string; taxable: boolean; taxSection: string;
  includedInCtc: boolean; shownOnPayslip: boolean; prorateOnLop: boolean; active: boolean;
}

function emptyForm(): ComponentForm {
  return { name: '', type: 'EARNING', taxable: true, taxSection: '', includedInCtc: true, shownOnPayslip: true, prorateOnLop: true, active: true };
}

function fromExisting(c: SalaryComponentCatalogResponse): ComponentForm {
  return {
    name: c.name, type: c.type, taxable: c.taxable, taxSection: c.taxSection ?? '',
    includedInCtc: c.includedInCtc, shownOnPayslip: c.shownOnPayslip, prorateOnLop: c.prorateOnLop, active: c.active,
  };
}

/** Keka's "Edit Salary Component" right drawer (§1.4) - a grey read-only COMPONENT TYPE block
 * (type can't change once a component exists; it's a free choice only while creating), an Enabled
 * toggle, then the component's own fields as a checklist. */
function ComponentDrawer({ title, initial, isNew, onClose, onSubmit }: {
  title: string; initial: ComponentForm; isNew: boolean; onClose: () => void; onSubmit: (form: ComponentForm) => Promise<void>;
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
    <Drawer
      title={title}
      onClose={onClose}
      readOnlyBlock={!isNew ? (
        <div><div style={labelStyle}>Component Type</div><div style={{ fontSize: 14, color: 'var(--txt)' }}>{TYPE_LABEL[form.type] ?? form.type}</div></div>
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
            label={<span style={{ fontSize: 13.5, color: 'var(--txt)' }}>{form.active ? 'This component is Enabled' : 'This component is Disabled'}</span>}
          />
        </div>
      )}
      <FormRow>
        <label style={labelStyle}>Component Name</label>
        <input value={form.name} onChange={e => setForm(f => ({ ...f, name: e.target.value }))} style={inputStyle} placeholder="e.g. HRA" />
      </FormRow>
      {isNew && (
        <FormRow>
          <label style={labelStyle}>Component Type</label>
          <select value={form.type} onChange={e => setForm(f => ({ ...f, type: e.target.value }))} style={inputStyle}>
            {Object.entries(TYPE_LABEL).map(([val, label]) => <option key={val} value={val}>{label}</option>)}
          </select>
        </FormRow>
      )}
      <FormRow>
        <label style={labelStyle}>Tax Section <span style={{ color: 'var(--txt-dim)', fontWeight: 400, textTransform: 'none', letterSpacing: 0 }}>(Optional)</span></label>
        <input value={form.taxSection} onChange={e => setForm(f => ({ ...f, taxSection: e.target.value }))} style={inputStyle} placeholder="e.g. 10(13A)" />
      </FormRow>

      <div style={{ marginTop: 18, paddingTop: 16, borderTop: '1px solid var(--line)' }}>
        <div style={{ ...labelStyle, marginBottom: 10 }}>Characteristics</div>
        {([
          ['taxable', 'Taxable'], ['includedInCtc', 'Included in CTC'],
          ['shownOnPayslip', 'Shown on Payslip'], ['prorateOnLop', 'Prorate on LOP'],
        ] as const).map(([key, label]) => (
          <label key={key} style={{ display: 'flex', alignItems: 'center', gap: 9, fontSize: 13.5, color: 'var(--txt)', marginBottom: 12, cursor: 'pointer' }}>
            <input type="checkbox" checked={form[key]} onChange={e => setForm(f => ({ ...f, [key]: e.target.checked }))} />
            {label}
          </label>
        ))}
      </div>
    </Drawer>
  );
}

/** Keka's "Order of components" screen (§3.8) - a single flat sequence across the whole catalog
 * (not per-category: SalaryComponentService#reorder assigns position = index in whatever list it
 * gets, so this always sends every id, never a filtered subset, to avoid two categories' positions
 * colliding). Up/down arrows instead of drag-and-drop - no DnD library is already a dependency
 * here, and arrows are just as usable for a list this short. */
function OrderOfComponentsPanel({ catalog, onChanged }: { catalog: SalaryComponentCatalogResponse[]; onChanged: () => void }) {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const [order, setOrder] = useState<SalaryComponentCatalogResponse[]>([]);
  const [saving, setSaving] = useState(false);
  const [dirty, setDirty] = useState(false);

  useEffect(() => {
    setOrder([...catalog].sort((a, b) => a.displayOrder - b.displayOrder || a.name.localeCompare(b.name)));
    setDirty(false);
  }, [catalog]);

  function move(index: number, delta: number) {
    const target = index + delta;
    if (target < 0 || target >= order.length) return;
    setOrder(prev => {
      const next = [...prev];
      [next[index], next[target]] = [next[target], next[index]];
      return next;
    });
    setDirty(true);
  }

  async function save() {
    setSaving(true);
    try {
      await payrollApi.reorderSalaryComponents(order.map(c => c.id), token);
      showToast('success', 'Component order saved');
      setDirty(false);
      onChanged();
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Failed to save order');
    } finally {
      setSaving(false);
    }
  }

  return (
    <div>
      <SectionHead
        title="Order of Components"
        action={<BtnPrimary onClick={save} disabled={!dirty || saving}>{saving ? 'Saving…' : 'Save Order'}</BtnPrimary>}
      />
      <div style={{ fontSize: 13, color: 'var(--txt-mut)', marginBottom: 14 }}>
        This order is used everywhere a component list appears - payslip, pay register, reports and salary breakup.
      </div>
      <div style={panelStyle}>
        <table className="nf-pr-table" style={{ width: '100%', borderCollapse: 'collapse' }}>
          <thead><tr>{['Sequence', 'Component Name', 'Component Type', ''].map(h => <th key={h} style={thStyle}>{h}</th>)}</tr></thead>
          <tbody>
            {order.map((c, i) => (
              <tr key={c.id}>
                <td style={tdStyle}>{i + 1}</td>
                <td style={tdStyle}>{c.name}</td>
                <td style={tdStyle}>{TYPE_LABEL[c.type] ?? c.type}</td>
                <td style={tdStyle}>
                  <div style={{ display: 'flex', gap: 4 }}>
                    <button type="button" onClick={() => move(i, -1)} disabled={i === 0} aria-label="Move up"
                      style={{ display: 'inline-flex', alignItems: 'center', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, padding: 5, cursor: i === 0 ? 'not-allowed' : 'pointer', color: i === 0 ? 'var(--txt-dim)' : 'var(--txt-mut)' }}>
                      <ArrowUp size={13} />
                    </button>
                    <button type="button" onClick={() => move(i, 1)} disabled={i === order.length - 1} aria-label="Move down"
                      style={{ display: 'inline-flex', alignItems: 'center', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, padding: 5, cursor: i === order.length - 1 ? 'not-allowed' : 'pointer', color: i === order.length - 1 ? 'var(--txt-dim)' : 'var(--txt-mut)' }}>
                      <ArrowDown size={13} />
                    </button>
                  </div>
                </td>
              </tr>
            ))}
            {order.length === 0 && <EmptyRow cols={4} msg="No salary components yet." />}
          </tbody>
        </table>
      </div>
    </div>
  );
}

/** Keka's "Accounting code" screen (§3.8) - an optional ledger/GL code per component, admin-
 * entered only (no accounting-system integration exists to validate or fetch it from). */
function AccountingCodePanel({ catalog, onChanged }: { catalog: SalaryComponentCatalogResponse[]; onChanged: () => void }) {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const [drafts, setDrafts] = useState<Record<string, string>>({});
  const [savingId, setSavingId] = useState<string | null>(null);

  function valueFor(c: SalaryComponentCatalogResponse): string {
    return drafts[c.id] ?? c.accountingCode ?? '';
  }

  async function save(c: SalaryComponentCatalogResponse) {
    const value = valueFor(c).trim();
    setSavingId(c.id);
    try {
      await payrollApi.updateSalaryComponent(c.id, { accountingCode: value }, token);
      showToast('success', 'Accounting code saved');
      onChanged();
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Failed to save accounting code');
    } finally {
      setSavingId(null);
    }
  }

  return (
    <div>
      <SectionHead title="Accounting Code" />
      <div style={{ fontSize: 13, color: 'var(--txt-mut)', marginBottom: 14 }}>
        An optional ledger code per component, for your own accounting system's reference - not validated against anything.
      </div>
      <div style={panelStyle}>
        <table className="nf-pr-table" style={{ width: '100%', borderCollapse: 'collapse' }}>
          <thead><tr>{['Component Name', 'Component Type', 'Accounting Code', ''].map(h => <th key={h} style={thStyle}>{h}</th>)}</tr></thead>
          <tbody>
            {catalog.map(c => {
              const value = valueFor(c);
              const changed = value !== (c.accountingCode ?? '');
              return (
                <tr key={c.id}>
                  <td style={tdStyle}>{c.name}</td>
                  <td style={tdStyle}>{TYPE_LABEL[c.type] ?? c.type}</td>
                  <td style={tdStyle}>
                    <input
                      value={value} onChange={e => setDrafts(d => ({ ...d, [c.id]: e.target.value }))}
                      style={{ ...inputStyle, maxWidth: 160 }} placeholder="e.g. GL-4010"
                    />
                  </td>
                  <td style={tdStyle}>
                    {changed && <BtnGhost onClick={() => save(c)} disabled={savingId === c.id}>{savingId === c.id ? 'Saving…' : 'Save'}</BtnGhost>}
                  </td>
                </tr>
              );
            })}
            {catalog.length === 0 && <EmptyRow cols={4} msg="No salary components yet." />}
          </tbody>
        </table>
      </div>
    </div>
  );
}

type ComponentView = 'components' | 'order' | 'accounting-code';
const COMPONENT_VIEWS: TabDef<ComponentView>[] = [
  { key: 'components', label: 'Components' },
  { key: 'order', label: 'Order of Components' },
  { key: 'accounting-code', label: 'Accounting Code' },
];

export function SalaryComponentsTab({ catalog, onChanged }: { catalog: SalaryComponentCatalogResponse[]; onChanged: () => void }) {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const [view, setView] = useState<ComponentView>('components');
  const [showCreate, setShowCreate] = useState(false);
  const [editing, setEditing] = useState<SalaryComponentCatalogResponse | null>(null);
  const [search, setSearch] = useState('');

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

  const filtered = catalog.filter(c => c.name.toLowerCase().includes(search.trim().toLowerCase()));

  return (
    <WithFaqPanel items={FAQ_ITEMS} title="Salary Components Help">
    <div>
      <TabBar tabs={COMPONENT_VIEWS} active={view} onChange={setView} className="nf-boxedtabs" ariaLabel="Salary component views" style={{ marginBottom: 18 }} />

      {view === 'order' && <OrderOfComponentsPanel catalog={catalog} onChanged={onChanged} />}
      {view === 'accounting-code' && <AccountingCodePanel catalog={catalog} onChanged={onChanged} />}

      {view === 'components' && (
        <>
      <SectionHead
        title="Salary Components"
        action={<BtnPrimary onClick={() => setShowCreate(true)}><Plus size={14} style={{ marginRight: 6, verticalAlign: 'text-bottom' }} />Add Component</BtnPrimary>}
      />
      <FilterBar search={search} onSearchChange={setSearch} placeholder="Search component" />
      <div style={panelStyle}>
        <table className="nf-pr-table" style={{ width: '100%', borderCollapse: 'collapse', tableLayout: 'fixed' }}>
          <thead>
            <tr>
              <th style={{ ...thStyle, width: '21%', whiteSpace: 'normal' }}>Name</th>
              <th style={{ ...thStyle, width: '12%', whiteSpace: 'normal' }}>Type</th>
              <th style={{ ...thStyle, width: '9%', whiteSpace: 'normal' }}>Taxable</th>
              <th style={{ ...thStyle, width: '9%', whiteSpace: 'normal' }}>In CTC</th>
              <th style={{ ...thStyle, width: '10%', whiteSpace: 'normal' }}>On Payslip</th>
              <th style={{ ...thStyle, width: '12%', whiteSpace: 'normal' }}>Prorate on LOP</th>
              <th style={{ ...thStyle, width: '9%', whiteSpace: 'normal' }}>Status</th>
              <th style={thStyle} className="nf-dock-cell" />
            </tr>
          </thead>
          <tbody>
            {filtered.map(c => (
              <tr key={c.id}>
                <td style={tdStyle}>{c.name}</td>
                <td style={tdStyle}>{TYPE_LABEL[c.type] ?? c.type}</td>
                <td style={tdStyle}>{c.taxable ? 'Yes' : 'No'}</td>
                <td style={tdStyle}>{c.includedInCtc ? 'Yes' : 'No'}</td>
                <td style={tdStyle}>{c.shownOnPayslip ? 'Yes' : 'No'}</td>
                <td style={tdStyle}>{c.prorateOnLop ? 'Yes' : 'No'}</td>
                <td style={tdStyle}>{c.active ? 'Active' : 'Inactive'}</td>
                <td className="nf-dock-cell" style={tdStyle}>
                  <ActionDock items={[
                    { label: 'Edit', icon: Pencil, onClick: () => setEditing(c) },
                    { label: c.active ? 'Deactivate' : 'Activate', icon: c.active ? Ban : CheckCircle2, onClick: () => toggleActive(c) },
                  ]} />
                </td>
              </tr>
            ))}
            {filtered.length === 0 && (
              <EmptyRow cols={8} msg={catalog.length === 0 ? 'No salary components yet - add one above.' : 'No matches for your search.'} />
            )}
          </tbody>
        </table>
      </div>

      {showCreate && (
        <ComponentDrawer title="Add Salary Component" initial={emptyForm()} isNew onClose={() => setShowCreate(false)} onSubmit={create} />
      )}
      {editing && (
        <ComponentDrawer title={`Edit Salary Component - ${editing.name}`} initial={fromExisting(editing)} isNew={false} onClose={() => setEditing(null)} onSubmit={form => update(editing.id, form)} />
      )}
        </>
      )}
    </div>
    </WithFaqPanel>
  );
}
