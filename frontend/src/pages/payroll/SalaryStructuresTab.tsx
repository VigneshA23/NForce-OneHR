import { useState } from 'react';
import { Plus, Trash2 } from 'lucide-react';
import { useAuthStore } from '../../store/authStore';
import { useToast } from '../../context/ToastContext';
import { payrollApi, type SalaryStructureResponse, type SalaryComponentResponse, type SalaryComponentRequest, type SalaryComponentCatalogResponse } from '../../api/payroll';
import {
  panelStyle, thStyle, tdStyle, fmtCurrency, EmptyRow, FormRow, labelStyle, inputStyle, BtnPrimary,
  BtnGhost, SectionHead, Modal, FullScreenTask,
} from './payrollShared';

// Human-readable formula for one structure component, e.g. "50% of Gross", "₹19,200 /yr", "Remainder
// (Gross minus every other component)". Values on SalaryStructureResponse are always ANNUAL (see
// the "values are ANNUAL" note on the editor below) — this never re-derives a monthly figure.
function describeCalc(c: SalaryComponentResponse): string {
  let base: string;
  switch (c.calcType) {
    case 'FIXED': base = `${fmtCurrency(c.value)} /yr`; break;
    case 'PERCENT_OF_GROSS': base = `${c.value}% of Gross`; break;
    case 'PERCENT_OF_COMPONENT': base = `${c.value}% of ${c.referenceComponentName ?? '—'}`; break;
    case 'BALANCING_REMAINDER': base = 'Remainder (Gross minus every other component)'; break;
    default: base = c.calcType;
  }
  if (c.cap != null) base += ` (${c.useLowerOfCap ? 'lower of this or' : 'capped at'} ${fmtCurrency(c.cap)} /yr)`;
  return base;
}

type DraftComponent = SalaryComponentRequest;

// Same formula description as describeCalc, for a not-yet-saved draft row in the editor (column 2
// of the structure editor) — a separate function rather than widening describeCalc's parameter
// type, since DraftComponent's fields are optional (not yet filled in) where the saved response's
// are not.
function describeDraftCalc(c: DraftComponent): string {
  let base: string;
  switch (c.calcType) {
    case 'FIXED': base = `${fmtCurrency(c.value ?? 0)} /yr`; break;
    case 'PERCENT_OF_GROSS': base = `${c.value ?? 0}% of Gross`; break;
    case 'PERCENT_OF_COMPONENT': base = `${c.value ?? 0}% of ${c.referenceComponentName || '—'}`; break;
    case 'BALANCING_REMAINDER': base = 'Remainder (Gross minus every other component)'; break;
    default: base = c.calcType;
  }
  if (c.cap != null) base += ` (${c.useLowerOfCap ? 'lower of this or' : 'capped at'} ${fmtCurrency(c.cap)} /yr)`;
  return base;
}

const GROUP_LABEL: Record<string, string> = {
  EARNING: 'Earnings', DEDUCTION: 'Deductions', REIMBURSEMENT: 'Reimbursements',
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

/** Column 3 of the structure editor — the selected component's own calculation fields and its
 * catalog-defined characteristics (read-only; a structure can't override what the component
 * itself is, only how much of it to pay). */
function ComponentDetailPane({ component, components, catalog, onChange }: {
  component: DraftComponent; components: DraftComponent[]; catalog: SalaryComponentCatalogResponse[];
  onChange: (patch: Partial<DraftComponent>) => void;
}) {
  const catalogEntry = catalog.find(cc => cc.name === component.componentName);
  return (
    <div style={{ ...panelStyle, padding: '18px 20px' }}>
      <div style={{ fontSize: 15, fontWeight: 700, color: 'var(--txt)', marginBottom: 14 }}>{component.componentName || 'Unnamed component'}</div>

      <FormRow>
        <label style={labelStyle}>Component</label>
        <select value={component.componentName} onChange={e => onChange({ componentName: e.target.value })} style={inputStyle}>
          <option value="">Select…</option>
          {catalog.map(cc => <option key={cc.id} value={cc.name}>{cc.name}</option>)}
        </select>
      </FormRow>
      <FormRow>
        <label style={labelStyle}>Calc Type</label>
        <select value={component.calcType} onChange={e => onChange({ calcType: e.target.value as DraftComponent['calcType'] })} style={inputStyle}>
          <option value="FIXED">Fixed</option>
          <option value="PERCENT_OF_GROSS">% of Gross</option>
          <option value="PERCENT_OF_COMPONENT">% of Another Component</option>
          <option value="BALANCING_REMAINDER">Balancing Remainder</option>
        </select>
      </FormRow>
      {component.calcType !== 'BALANCING_REMAINDER' && (
        <FormRow>
          <label style={labelStyle}>Value</label>
          <input type="number" min={0} value={component.value ?? ''} onChange={e => onChange({ value: Number(e.target.value) })} style={inputStyle} />
        </FormRow>
      )}
      {component.calcType === 'PERCENT_OF_COMPONENT' && (
        <FormRow>
          <label style={labelStyle}>Of Component</label>
          <select value={component.referenceComponentName ?? ''} onChange={e => onChange({ referenceComponentName: e.target.value })} style={inputStyle}>
            <option value="">Select…</option>
            {components.filter(other => other !== component && !!other.componentName).map(other => (
              <option key={other.componentName} value={other.componentName}>{other.componentName}</option>
            ))}
          </select>
        </FormRow>
      )}

      {catalogEntry && (
        <div style={{ marginTop: 18, paddingTop: 16, borderTop: '1px solid var(--line)' }}>
          <div style={{ ...labelStyle, marginBottom: 10 }}>Characteristics</div>
          {[
            ['Taxable', catalogEntry.taxable],
            ['Included in CTC', catalogEntry.includedInCtc],
            ['Shown on Payslip', catalogEntry.shownOnPayslip],
            ['Prorated on LOP', catalogEntry.prorateOnLop],
          ].map(([label, on]) => (
            <div key={label as string} style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8, fontSize: 13 }}>
              <span style={{ color: on ? 'var(--ok)' : 'var(--txt-dim)' }}>{on ? '✓' : '✕'}</span>
              <span style={{ color: on ? 'var(--txt)' : 'var(--txt-dim)' }}>{label}</span>
            </div>
          ))}
        </div>
      )}
    </div>
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
      showToast('success', structure ? 'Salary structure updated — takes effect on employees\' next payroll regenerate' : 'Salary structure created');
      onClose();
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Failed to save structure');
    } finally {
      setSubmitting(false);
    }
  }

  // Grouped by the catalog's own type (Earnings/Deductions/Reimbursements), same categories
  // Salary Setup's own component picker already filters to — not a Keka-specific invention.
  const grouped = new Map<string, number[]>();
  components.forEach((c, i) => {
    const type = catalog.find(cc => cc.name === c.componentName)?.type ?? 'Other';
    grouped.set(type, [...(grouped.get(type) ?? []), i]);
  });

  const selected = components[selectedIndex] as DraftComponent | undefined;

  return (
    <FullScreenTask
      title={structure ? 'Update Salary Structure' : 'Create Salary Structure'}
      onClose={onClose}
      actions={(
        <>
          <BtnGhost onClick={onClose}>Cancel</BtnGhost>
          <BtnPrimary onClick={save} disabled={!valid || submitting}>{submitting ? 'Saving…' : structure ? 'Update' : 'Create'}</BtnPrimary>
        </>
      )}
    >
      {structure && (
        <div style={{ fontSize: 12, color: 'var(--txt-dim)', marginBottom: 18 }}>
          Changes apply going forward only — already-generated payslips keep their original amounts.
          Every employee currently on this structure picks up the new components on their next payroll regenerate.
        </div>
      )}
      <div style={{ display: 'grid', gridTemplateColumns: 'minmax(220px, 26%) minmax(260px, 32%) minmax(260px, 1fr)', gap: 20, alignItems: 'start' }}>
        {/* Column 1 — structure metadata */}
        <div>
          <FormRow><label style={labelStyle}>Name of the Structure *</label><input value={name} onChange={e => setName(e.target.value)} style={inputStyle} placeholder="e.g. Standard Structure" /></FormRow>
          <FormRow><label style={labelStyle}>Grade</label><input value={grade} onChange={e => setGrade(e.target.value)} style={inputStyle} placeholder="e.g. L2" /></FormRow>
          <FormRow><label style={labelStyle}>Financial Year</label><input value={financialYear} onChange={e => setFinancialYear(e.target.value)} style={inputStyle} placeholder="e.g. 2026-27" /></FormRow>
        </div>

        {/* Column 2 — component list, grouped */}
        <div>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 10 }}>
            <span style={labelStyle}>Components (values are ANNUAL)</span>
            <BtnGhost onClick={addComponent}><Plus size={13} style={{ marginRight: 5, verticalAlign: 'text-bottom' }} />Add</BtnGhost>
          </div>
          <div style={panelStyle}>
            {[...grouped.entries()].map(([type, indices]) => (
              <div key={type}>
                <div style={{ padding: '8px 14px', fontSize: 11, fontWeight: 700, letterSpacing: '.06em', textTransform: 'uppercase', color: 'var(--txt-dim)', background: 'var(--raised)', borderBottom: '1px solid var(--line)' }}>
                  {GROUP_LABEL[type] ?? type}
                </div>
                {indices.map(i => {
                  const c = components[i];
                  const isSelected = i === selectedIndex;
                  return (
                    <div
                      key={i} onClick={() => setSelectedIndex(i)}
                      style={{
                        display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8, padding: '10px 14px',
                        cursor: 'pointer', borderBottom: '1px solid var(--line)',
                        background: isSelected ? 'color-mix(in srgb, var(--brand) 10%, var(--panel))' : 'var(--panel)',
                      }}
                    >
                      <div style={{ minWidth: 0 }}>
                        <div style={{ fontSize: 13.5, fontWeight: 600, color: 'var(--txt)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                          {c.componentName || 'Unnamed component'}
                        </div>
                        <div style={{ fontSize: 12, color: 'var(--txt-dim)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                          {describeDraftCalc(c)}
                        </div>
                      </div>
                      <button type="button" onClick={e => { e.stopPropagation(); removeComponent(i); }} style={{ background: 'none', border: 'none', color: 'var(--txt-dim)', cursor: 'pointer', padding: 4, flexShrink: 0 }} aria-label="Remove component">
                        <Trash2 size={14} />
                      </button>
                    </div>
                  );
                })}
              </div>
            ))}
            {components.length === 0 && <EmptyRow cols={1} msg="No components yet — add one." />}
          </div>
        </div>

        {/* Column 3 — selected component's detail */}
        <div>
          {selected ? (
            <ComponentDetailPane component={selected} components={components} catalog={catalog} onChange={patch => updateComponent(selectedIndex, patch)} />
          ) : (
            <div style={{ ...panelStyle, padding: '24px 20px', textAlign: 'center', color: 'var(--txt-dim)', fontSize: 13 }}>
              Select a component on the left to edit its calculation.
            </div>
          )}
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
      <table className="nf-pr-table" style={{ width: '100%', borderCollapse: 'collapse', marginBottom: 16 }}>
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
  // Holds just the id, not the structure object — the object itself always comes fresh from the
  // `structures` prop (the parent's state, updated via onCreated/onUpdated), so an edit elsewhere
  // (or this one's own save) is reflected here without a second copy of the list to keep in sync.
  const [viewingId, setViewingId] = useState<string | null>(null);
  const [editing, setEditing] = useState<'new' | string | null>(null);
  const viewing = structures.find(s => s.id === viewingId) ?? null;
  const editingStructure = editing && editing !== 'new' ? structures.find(s => s.id === editing) ?? null : null;

  return (
    <div>
      <SectionHead title="Salary Structures" action={<BtnPrimary onClick={() => setEditing('new')}><Plus size={14} style={{ marginRight: 6, verticalAlign: 'text-bottom' }} />Create Structure</BtnPrimary>} />
      <div style={{ fontSize: 12, color: 'var(--txt-dim)', marginBottom: 10 }}>
        Click a row to see the full calculation for each component, or edit it.
      </div>
      <div style={panelStyle}>
        <table className="nf-pr-table" style={{ width: '100%', borderCollapse: 'collapse' }}>
          <thead><tr>{['Name', 'Grade', 'Financial Year', 'Components'].map(h => <th key={h} style={thStyle}>{h}</th>)}</tr></thead>
          <tbody>
            {structures.map(s => (
              <tr key={s.id} onClick={() => setViewingId(s.id)} style={{ cursor: 'pointer' }}>
                <td style={tdStyle}>{s.name}</td>
                <td style={tdStyle}>{s.grade ?? '–'}</td>
                <td style={tdStyle}>{s.financialYear ?? '–'}</td>
                <td style={tdStyle}>{s.components.map(c => c.componentName).join(', ')}</td>
              </tr>
            ))}
            {structures.length === 0 && <EmptyRow cols={4} msg="No salary structures yet — create one above." />}
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
  );
}
