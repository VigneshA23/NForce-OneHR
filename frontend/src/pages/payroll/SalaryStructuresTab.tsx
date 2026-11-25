import { useState } from 'react';
import { Plus, Trash2, Check, X, Layers, Calculator, FileText, ListChecks, FileStack } from 'lucide-react';
import { useAuthStore } from '../../store/authStore';
import { useToast } from '../../context/ToastContext';
import { payrollApi, type SalaryStructureResponse, type SalaryComponentResponse, type SalaryComponentRequest, type SalaryComponentCatalogResponse } from '../../api/payroll';
import {
  panelStyle, thStyle, tdStyle, fmtCurrency, EmptyRow, FormRow, labelStyle, inputStyle, BtnPrimary,
  BtnGhost, SectionHead, Modal, FullScreenTask, WithFaqPanel, type FaqItem,
} from './payrollShared';

const FAQ_ITEMS: FaqItem[] = [
  { q: 'What is a salary structure?', a: 'A named template of components (Basic, HRA, LTA, etc.) with how each one is calculated - fixed amount, a percentage of another component, or whatever remains of gross. Employees are assigned one structure at a time via Employee Compensation.' },
  { q: 'Why are the values shown here annual, not monthly?', a: 'Every figure on a structure (and in its editor) is the yearly amount, matching how CTC is normally quoted. Payroll divides by 12 - and applies LOP proration - when it actually builds a monthly payslip.' },
  { q: 'How do I see exactly how a component is calculated?', a: 'Click any row to open its preview, which shows the plain-language formula for every component (e.g. "50% of Gross", "₹1,600/yr fixed"). Click Edit from there to change it.' },
  { q: 'Can I change which components are in a structure after employees are assigned to it?', a: 'Yes - editing a structure changes it for every employee currently assigned to it, going forward. It never rewrites an already-generated payslip.' },
];

// Human-readable formula for one structure component, e.g. "50% of Gross", "₹19,200 /yr", "Remainder
// (Gross minus every other component)". Values on SalaryStructureResponse are always ANNUAL (see
// the "values are ANNUAL" note on the editor below) - this never re-derives a monthly figure.
function describeCalc(c: SalaryComponentResponse): string {
  let base: string;
  switch (c.calcType) {
    case 'FIXED': base = `${fmtCurrency(c.value)} /yr`; break;
    case 'PERCENT_OF_GROSS': base = `${c.value}% of Gross`; break;
    case 'PERCENT_OF_COMPONENT': base = `${c.value}% of ${c.referenceComponentName ?? '-'}`; break;
    case 'BALANCING_REMAINDER': base = 'Remainder (Gross minus every other component)'; break;
    default: base = c.calcType;
  }
  if (c.cap != null) base += ` (${c.useLowerOfCap ? 'lower of this or' : 'capped at'} ${fmtCurrency(c.cap)} /yr)`;
  return base;
}

type DraftComponent = SalaryComponentRequest;

// Same formula description as describeCalc, for a not-yet-saved draft row in the editor (column 2
// of the structure editor) - a separate function rather than widening describeCalc's parameter
// type, since DraftComponent's fields are optional (not yet filled in) where the saved response's
// are not.
function describeDraftCalc(c: DraftComponent): string {
  let base: string;
  switch (c.calcType) {
    case 'FIXED': base = `${fmtCurrency(c.value ?? 0)} /yr`; break;
    case 'PERCENT_OF_GROSS': base = `${c.value ?? 0}% of Gross`; break;
    case 'PERCENT_OF_COMPONENT': base = `${c.value ?? 0}% of ${c.referenceComponentName || '-'}`; break;
    case 'BALANCING_REMAINDER': base = 'Remainder (Gross minus every other component)'; break;
    default: base = c.calcType;
  }
  if (c.cap != null) base += ` (${c.useLowerOfCap ? 'lower of this or' : 'capped at'} ${fmtCurrency(c.cap)} /yr)`;
  return base;
}

const GROUP_TONE: Record<string, string> = {
  EARNING: 'var(--ok)', DEDUCTION: 'var(--risk)', REIMBURSEMENT: 'var(--info)', CONTRIBUTION: 'var(--warn)',
};

const GROUP_LABEL: Record<string, string> = {
  EARNING: 'Earnings', DEDUCTION: 'Deductions', REIMBURSEMENT: 'Reimbursements', CONTRIBUTION: 'Contributions',
};

function emptyComponent(defaultName: string): DraftComponent {
  return { componentName: defaultName, calcType: 'FIXED', value: 0, referenceComponentName: null, cap: null, useLowerOfCap: false };
}

function fromResponse(c: SalaryComponentResponse): DraftComponent {
  return {
    componentName: c.componentName, calcType: c.calcType as DraftComponent['calcType'], value: c.value,
    referenceComponentName: c.referenceComponentName, cap: c.cap, useLowerOfCap: c.useLowerOfCap,
  };
}

function componentsAreValid(components: DraftComponent[]): boolean {
  return components.length > 0 && components.every(c =>
    !!c.componentName &&
    (c.calcType === 'BALANCING_REMAINDER' || (c.value != null && c.value >= 0)) &&
    (c.calcType !== 'PERCENT_OF_COMPONENT' || !!c.referenceComponentName));
}

function toComponentsPayload(components: DraftComponent[]): SalaryComponentRequest[] {
  return components.map(c => ({
    ...c,
    value: c.calcType === 'BALANCING_REMAINDER' ? null : c.value,
    referenceComponentName: c.calcType === 'PERCENT_OF_COMPONENT' ? c.referenceComponentName : null,
  }));
}

/** Column 3 of the structure editor: the selected component's own calculation fields and its
 * catalog-defined characteristics (read-only; a structure can't override what the component
 * itself is, only how much of it to pay). */
const CALC_OPTIONS: { key: DraftComponent['calcType']; label: string }[] = [
  { key: 'FIXED', label: 'Fixed amount' },
  { key: 'PERCENT_OF_GROSS', label: '% of Gross' },
  { key: 'PERCENT_OF_COMPONENT', label: '% of a component' },
  { key: 'BALANCING_REMAINDER', label: 'Balancing remainder' },
];

function ComponentDetailPane({ component, components, catalog, onChange }: {
  component: DraftComponent; components: DraftComponent[]; catalog: SalaryComponentCatalogResponse[];
  onChange: (patch: Partial<DraftComponent>) => void;
}) {
  const catalogEntry = catalog.find(cc => cc.name === component.componentName);
  return (
    <section className="nf-pr-edcard">
      <header className="nf-pr-edcard-head">
        <span className="nf-pr-edcard-ico"><Calculator size={16} /></span>
        <div>
          <h3 className="nf-pr-edcard-title">{component.componentName || 'Unnamed component'}</h3>
          <p className="nf-pr-edcard-sub">How this component is calculated</p>
        </div>
      </header>
      <div className="nf-pr-edcard-body">
        <FormRow>
          <label style={labelStyle}>Component</label>
          <select value={component.componentName} onChange={e => onChange({ componentName: e.target.value })} style={inputStyle}>
            <option value="">Select…</option>
            {catalog.map(cc => <option key={cc.id} value={cc.name}>{cc.name}</option>)}
          </select>
        </FormRow>

        <FormRow>
          <label style={labelStyle}>Calculation</label>
          <div className="nf-pr-chips" role="radiogroup" aria-label="Calculation type">
            {CALC_OPTIONS.map(o => (
              <button
                key={o.key} type="button" role="radio" aria-checked={component.calcType === o.key}
                className={`nf-pr-chip${component.calcType === o.key ? ' is-on' : ''}`}
                onClick={() => onChange({ calcType: o.key })}
              >
                {o.label}
              </button>
            ))}
          </div>
        </FormRow>

        {component.calcType !== 'BALANCING_REMAINDER' && (
          <FormRow>
            <label style={labelStyle}>{component.calcType === 'FIXED' ? 'Amount per year' : 'Percentage'}</label>
            <input type="number" min={0} value={component.value ?? ''} onChange={e => onChange({ value: Number(e.target.value) })} style={inputStyle} />
          </FormRow>
        )}
        {component.calcType === 'PERCENT_OF_COMPONENT' && (
          <FormRow>
            <label style={labelStyle}>Of component</label>
            <select value={component.referenceComponentName ?? ''} onChange={e => onChange({ referenceComponentName: e.target.value })} style={inputStyle}>
              <option value="">Select…</option>
              {components.filter(other => other !== component && !!other.componentName).map(other => (
                <option key={other.componentName} value={other.componentName}>{other.componentName}</option>
              ))}
            </select>
          </FormRow>
        )}

        <div className="nf-pr-formula">
          <span className="nf-pr-formula-k">Formula</span>
          <span className="nf-pr-formula-v">{describeDraftCalc(component)}</span>
        </div>

        {catalogEntry && (
          <div className="nf-pr-chars">
            <div style={{ ...labelStyle, marginBottom: 10 }}>Characteristics</div>
            <div className="nf-pr-chars-grid">
              {([
                ['Taxable', catalogEntry.taxable],
                ['Included in CTC', catalogEntry.includedInCtc],
                ['Shown on payslip', catalogEntry.shownOnPayslip],
                ['Prorated on LOP', catalogEntry.prorateOnLop],
              ] as [string, boolean][]).map(([label, on]) => (
                <span key={label} className={`nf-pr-char${on ? ' is-on' : ''}`}>
                  {on ? <Check size={13} /> : <X size={13} />}{label}
                </span>
              ))}
            </div>
          </div>
        )}
      </div>
    </section>
  );
}

/** The structure editor, full-screen with three columns (Keka's "Update Annual salary structure"
 * pattern, §3.8): structure metadata on the left, the component list (grouped by catalog type) in
 * the middle, and the selected component's own calculation + characteristics on the right.
 * Handles both create (structure === null) and edit in the same layout. */
function StructureEditorScreen({ structure, catalog, onClose, onSaved }: {
  structure: SalaryStructureResponse | null; catalog: SalaryComponentCatalogResponse[];
  onClose: () => void; onSaved: (s: SalaryStructureResponse) => void;
}) {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const [name, setName] = useState(structure?.name ?? '');
  const [grade, setGrade] = useState(structure?.grade ?? '');
  const [financialYear, setFinancialYear] = useState(structure?.financialYear ?? '');
  const [components, setComponents] = useState<DraftComponent[]>(
    () => structure ? structure.components.map(fromResponse) : [emptyComponent(catalog[0]?.name ?? '')],
  );
  const [selectedIndex, setSelectedIndex] = useState(0);
  const [submitting, setSubmitting] = useState(false);

  const valid = !!name.trim() && componentsAreValid(components);

  function updateComponent(i: number, patch: Partial<DraftComponent>) {
    setComponents(prev => prev.map((c, idx) => (idx === i ? { ...c, ...patch } : c)));
  }

  function addComponent() {
    setComponents(prev => {
      const next = [...prev, emptyComponent(catalog[0]?.name ?? '')];
      setSelectedIndex(next.length - 1);
      return next;
    });
  }

  function removeComponent(i: number) {
    setComponents(prev => {
      const next = prev.filter((_, idx) => idx !== i);
      setSelectedIndex(s => Math.min(s, Math.max(0, next.length - 1)));
      return next;
    });
  }

  async function save() {
    if (!valid) return;
    setSubmitting(true);
    try {
      const payload = {
        name: name.trim(), grade: grade.trim() || undefined, financialYear: financialYear.trim() || undefined,
        components: toComponentsPayload(components),
      };
      const saved = structure
        ? await payrollApi.updateSalaryStructure(structure.id, payload, token)
        : await payrollApi.createSalaryStructure(payload, token);
      onSaved(saved);
      showToast('success', structure ? 'Salary structure updated - takes effect on employees\' next payroll regenerate' : 'Salary structure created');
      onClose();
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Failed to save structure');
    } finally {
      setSubmitting(false);
    }
  }

  // Grouped by the catalog's own type (Earnings/Deductions/Reimbursements), same categories
  // Salary Setup's own component picker already filters to - not a Keka-specific invention.
  const grouped = new Map<string, number[]>();
  components.forEach((c, i) => {
    const type = catalog.find(cc => cc.name === c.componentName)?.type ?? 'Other';
    grouped.set(type, [...(grouped.get(type) ?? []), i]);
  });

  const selected = components[selectedIndex] as DraftComponent | undefined;

  return (
    <FullScreenTask
      title={structure ? 'Update Salary Structure' : 'Create Salary Structure'}
      subtitle={structure ? 'Edit the components and how each one is calculated.' : 'Name the structure, then add the components and how each one is calculated.'}
      onClose={onClose}
      actions={(
        <>
          <BtnGhost onClick={onClose}>Cancel</BtnGhost>
          <BtnPrimary onClick={save} disabled={!valid || submitting}>{submitting ? 'Saving…' : structure ? 'Update' : 'Create'}</BtnPrimary>
        </>
      )}
    >
      {structure && (
        <div className="nf-pr-ednote">
          Changes apply going forward only: already-generated payslips keep their original amounts.
          Every employee currently on this structure picks up the new components on their next payroll regenerate.
        </div>
      )}
      <div className="nf-pr-edwrap">
        <div className="nf-pr-ed">
          {/* Column 1: structure details and a live summary */}
          <div className="nf-pr-edcol">
            <section className="nf-pr-edcard">
              <header className="nf-pr-edcard-head">
                <span className="nf-pr-edcard-ico"><FileText size={16} /></span>
                <div>
                  <h3 className="nf-pr-edcard-title">Structure details</h3>
                  <p className="nf-pr-edcard-sub">Name it so it is easy to find when assigning pay</p>
                </div>
              </header>
              <div className="nf-pr-edcard-body">
                <FormRow><label style={labelStyle}>Name of the structure</label><input value={name} onChange={e => setName(e.target.value)} style={inputStyle} placeholder="e.g. Standard Structure" /></FormRow>
                <FormRow><label style={labelStyle}>Grade</label><input value={grade} onChange={e => setGrade(e.target.value)} style={inputStyle} placeholder="e.g. L2" /></FormRow>
                <FormRow><label style={labelStyle}>Financial year</label><input value={financialYear} onChange={e => setFinancialYear(e.target.value)} style={inputStyle} placeholder="e.g. 2026-27" /></FormRow>
              </div>
            </section>

            <section className="nf-pr-edcard">
              <header className="nf-pr-edcard-head">
                <span className="nf-pr-edcard-ico"><ListChecks size={16} /></span>
                <div>
                  <h3 className="nf-pr-edcard-title">Ready to save?</h3>
                  <p className="nf-pr-edcard-sub">{components.length} component{components.length === 1 ? '' : 's'} in this structure</p>
                </div>
              </header>
              <div className="nf-pr-edcard-body">
                <ul className="nf-pr-check">
                  <li className={name.trim() ? 'is-ok' : ''}>{name.trim() ? <Check size={14} /> : <X size={14} />}Structure has a name</li>
                  <li className={components.length > 0 ? 'is-ok' : ''}>{components.length > 0 ? <Check size={14} /> : <X size={14} />}At least one component</li>
                  <li className={componentsAreValid(components) ? 'is-ok' : ''}>{componentsAreValid(components) ? <Check size={14} /> : <X size={14} />}Every component is filled in</li>
                </ul>
              </div>
            </section>
          </div>

          {/* Column 2: component list, grouped by type */}
          <section className="nf-pr-edcard">
            <header className="nf-pr-edcard-head">
              <span className="nf-pr-edcard-ico"><Layers size={16} /></span>
              <div style={{ flex: 1, minWidth: 0 }}>
                <h3 className="nf-pr-edcard-title">Components</h3>
                <p className="nf-pr-edcard-sub">All values are yearly amounts</p>
              </div>
              <BtnPrimary onClick={addComponent}><Plus size={13} style={{ marginRight: 5, verticalAlign: 'text-bottom' }} />Add</BtnPrimary>
            </header>
            <div className="nf-pr-edcard-body nf-pr-edlist">
              {[...grouped.entries()].map(([type, indices]) => (
                <div key={type} className="nf-pr-grp" style={{ ['--gk' as string]: GROUP_TONE[type] ?? 'var(--txt-dim)' }}>
                  <div className="nf-pr-grp-head">
                    <span>{GROUP_LABEL[type] ?? type}</span>
                    <span className="nf-pr-grp-count">{indices.length}</span>
                  </div>
                  {indices.map(i => {
                    const c = components[i];
                    return (
                      <div
                        key={i} role="button" tabIndex={0}
                        className={`nf-pr-comp${i === selectedIndex ? ' is-sel' : ''}`}
                        onClick={() => setSelectedIndex(i)}
                        onKeyDown={e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); setSelectedIndex(i); } }}
                      >
                        <div style={{ minWidth: 0 }}>
                          <div className="nf-pr-comp-name">{c.componentName || 'Unnamed component'}</div>
                          <div className="nf-pr-comp-calc">{describeDraftCalc(c)}</div>
                        </div>
                        <button type="button" className="nf-pr-comp-del" onClick={e => { e.stopPropagation(); removeComponent(i); }} aria-label="Remove component">
                          <Trash2 size={14} />
                        </button>
                      </div>
                    );
                  })}
                </div>
              ))}
              {components.length === 0 && <div className="nf-pr-edempty">No components yet. Use Add to create one.</div>}
            </div>
          </section>

          {/* Column 3: selected component's detail */}
          <div className="nf-pr-edcol">
            {selected ? (
              <ComponentDetailPane component={selected} components={components} catalog={catalog} onChange={patch => updateComponent(selectedIndex, patch)} />
            ) : (
              <section className="nf-pr-edcard">
                <div className="nf-pr-edcard-body nf-pr-edempty">Select a component to edit its calculation.</div>
              </section>
            )}
          </div>
        </div>
      </div>
    </FullScreenTask>
  );
}

function StructurePreviewModal({ structure, onClose, onEdit }: {
  structure: SalaryStructureResponse; onClose: () => void; onEdit: () => void;
}) {
  return (
    <Modal title={structure.name} subtitle={[structure.grade, structure.financialYear].filter(Boolean).join(' · ') || null} onClose={onClose} wide>
      <table className="nf-pr-center nf-pr-table" style={{ width: '100%', borderCollapse: 'collapse', marginBottom: 16 }}>
        <thead>
          <tr>{['Component', 'Type', 'Calculation', 'Taxable', 'In CTC', 'On Payslip'].map(h => <th key={h} style={thStyle}>{h}</th>)}</tr>
        </thead>
        <tbody>
          {structure.components.map(c => (
            <tr key={c.id}>
              <td style={tdStyle}>{c.componentName}</td>
              <td style={tdStyle}>{c.type}</td>
              <td style={tdStyle}>{describeCalc(c)}</td>
              <td style={tdStyle}>{c.taxable ? 'Yes' : 'No'}</td>
              <td style={tdStyle}>{c.includedInCtc ? 'Yes' : 'No'}</td>
              <td style={tdStyle}>{c.shownOnPayslip ? 'Yes' : 'No'}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
        <BtnPrimary onClick={onEdit}>Edit Structure</BtnPrimary>
      </div>
    </Modal>
  );
}

export function SalaryStructuresTab({ structures, catalog, onCreated, onUpdated }: {
  structures: SalaryStructureResponse[]; catalog: SalaryComponentCatalogResponse[];
  onCreated: (s: SalaryStructureResponse) => void; onUpdated: (s: SalaryStructureResponse) => void;
}) {
  // Holds just the id, not the structure object - the object itself always comes fresh from the
  // `structures` prop (the parent's state, updated via onCreated/onUpdated), so an edit elsewhere
  // (or this one's own save) is reflected here without a second copy of the list to keep in sync.
  const [viewingId, setViewingId] = useState<string | null>(null);
  const [editing, setEditing] = useState<'new' | string | null>(null);
  const viewing = structures.find(s => s.id === viewingId) ?? null;
  const editingStructure = editing && editing !== 'new' ? structures.find(s => s.id === editing) ?? null : null;

  return (
    <WithFaqPanel items={FAQ_ITEMS} title="Salary Structures Help">
    <div>
      <SectionHead icon={FileStack} title="Salary Structures" subtitle="Reusable pay templates. Assign one to an employee together with their CTC." action={<BtnPrimary onClick={() => setEditing('new')}><Plus size={14} style={{ marginRight: 6, verticalAlign: 'text-bottom' }} />Create Structure</BtnPrimary>} />
      <div style={{ fontSize: 12, color: 'var(--txt-dim)', marginBottom: 10 }}>
        Click a row to see the full calculation for each component, or edit it.
      </div>
      <div style={panelStyle}>
        <table className="nf-pr-table nf-pr-center-mid nf-pr-compact-th nf-pr-structs" style={{ width: '100%', borderCollapse: 'collapse' }}>
          <thead><tr>{['Name', 'Grade', 'Financial Year', 'Components'].map(h => <th key={h} style={thStyle}>{h}</th>)}</tr></thead>
          <tbody>
            {structures.map(s => (
              <tr key={s.id} onClick={() => setViewingId(s.id)} style={{ cursor: 'pointer' }}>
                <td style={tdStyle}>{s.name}</td>
                <td style={tdStyle}>{s.grade ?? '-'}</td>
                <td style={tdStyle}>{s.financialYear ?? '-'}</td>
                <td style={tdStyle}>{s.components.map(c => c.componentName).join(', ')}</td>
              </tr>
            ))}
            {structures.length === 0 && <EmptyRow cols={4} msg="No salary structures yet - create one above." />}
          </tbody>
        </table>
      </div>

      {viewing && (
        <StructurePreviewModal
          structure={viewing}
          onClose={() => setViewingId(null)}
          onEdit={() => { setEditing(viewing.id); setViewingId(null); }}
        />
      )}

      {editing && (
        <StructureEditorScreen
          structure={editingStructure}
          catalog={catalog}
          onClose={() => setEditing(null)}
          onSaved={s => (editingStructure ? onUpdated(s) : onCreated(s))}
        />
      )}
    </div>
    </WithFaqPanel>
  );
}
