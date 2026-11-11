import { useState } from 'react';
import { Plus, Trash2 } from 'lucide-react';
import { useAuthStore } from '../../store/authStore';
import { useToast } from '../../context/ToastContext';
import { payrollApi, type SalaryStructureResponse, type SalaryComponentResponse, type SalaryComponentRequest, type SalaryComponentCatalogResponse } from '../../api/payroll';
import { panelStyle, thStyle, tdStyle, fmtCurrency, EmptyRow, FormRow, labelStyle, inputStyle, BtnPrimary, BtnGhost, SectionHead, Modal } from './payrollShared';

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

/** The editable component-row list shared by the create form and the edit-in-place modal below —
 *  same fields either way, just a different initial list and a different submit target. */
function ComponentRowsEditor({ components, setComponents, catalog }: {
  components: DraftComponent[]; setComponents: (updater: (prev: DraftComponent[]) => DraftComponent[]) => void; catalog: SalaryComponentCatalogResponse[];
}) {
  function updateRow(i: number, patch: Partial<DraftComponent>) {
    setComponents(prev => prev.map((c, idx) => (idx === i ? { ...c, ...patch } : c)));
  }

  return (
    <div>
      <div style={labelStyle}>Components (values are ANNUAL)</div>
      {components.map((c, i) => (
        <div key={i} style={{ display: 'flex', gap: 8, alignItems: 'flex-end', marginBottom: 10, flexWrap: 'wrap' }}>
          <div style={{ minWidth: 160 }}>
            <label style={labelStyle}>Component</label>
            <select value={c.componentName} onChange={e => updateRow(i, { componentName: e.target.value })} style={inputStyle}>
              <option value="">Select…</option>
              {catalog.map(cc => <option key={cc.id} value={cc.name}>{cc.name}</option>)}
            </select>
          </div>
          <div style={{ minWidth: 160 }}>
            <label style={labelStyle}>Calc Type</label>
            <select value={c.calcType} onChange={e => updateRow(i, { calcType: e.target.value as DraftComponent['calcType'] })} style={inputStyle}>
              <option value="FIXED">Fixed</option>
              <option value="PERCENT_OF_GROSS">% of Gross</option>
              <option value="PERCENT_OF_COMPONENT">% of Another Component</option>
              <option value="BALANCING_REMAINDER">Balancing Remainder</option>
            </select>
          </div>
          {c.calcType !== 'BALANCING_REMAINDER' && (
            <div style={{ width: 120 }}>
              <label style={labelStyle}>Value</label>
              <input type="number" min={0} value={c.value ?? ''} onChange={e => updateRow(i, { value: Number(e.target.value) })} style={inputStyle} />
            </div>
          )}
          {c.calcType === 'PERCENT_OF_COMPONENT' && (
            <div style={{ minWidth: 160 }}>
              <label style={labelStyle}>Of Component</label>
              <select value={c.referenceComponentName ?? ''} onChange={e => updateRow(i, { referenceComponentName: e.target.value })} style={inputStyle}>
                <option value="">Select…</option>
                {components.filter((other, idx) => idx !== i && !!other.componentName).map((other, idx) => <option key={idx} value={other.componentName}>{other.componentName}</option>)}
              </select>
            </div>
          )}
          <BtnGhost onClick={() => setComponents(prev => prev.filter((_, idx) => idx !== i))}><Trash2 size={14} /></BtnGhost>
        </div>
      ))}
      <BtnGhost onClick={() => setComponents(prev => [...prev, emptyComponent(catalog[0]?.name ?? '')])}>
        <Plus size={14} style={{ marginRight: 6, verticalAlign: 'text-bottom' }} />Add Component
      </BtnGhost>
    </div>
  );
}

function CreateStructureForm({ catalog, onCreated }: { catalog: SalaryComponentCatalogResponse[]; onCreated: (s: SalaryStructureResponse) => void }) {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const [name, setName] = useState('');
  const [grade, setGrade] = useState('');
  const [financialYear, setFinancialYear] = useState('');
  const [components, setComponents] = useState<DraftComponent[]>([emptyComponent(catalog[0]?.name ?? '')]);
  const [submitting, setSubmitting] = useState(false);

  const valid = !!name.trim() && componentsAreValid(components);

  async function submit() {
    if (!valid) return;
    setSubmitting(true);
    try {
      const structure = await payrollApi.createSalaryStructure({
        name: name.trim(), grade: grade.trim() || undefined, financialYear: financialYear.trim() || undefined,
        components: toComponentsPayload(components),
      }, token);
      onCreated(structure);
      setName(''); setGrade(''); setFinancialYear(''); setComponents([emptyComponent(catalog[0]?.name ?? '')]);
      showToast('success', 'Salary structure created');
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Failed to create structure');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 14, padding: '18px 22px', marginBottom: 20 }}>
      <SectionHead title="Create Salary Structure" />
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: 12, marginBottom: 14 }}>
        <FormRow><label style={labelStyle}>Name *</label><input value={name} onChange={e => setName(e.target.value)} style={inputStyle} placeholder="e.g. Standard Structure" /></FormRow>
        <FormRow><label style={labelStyle}>Grade</label><input value={grade} onChange={e => setGrade(e.target.value)} style={inputStyle} placeholder="e.g. L2" /></FormRow>
        <FormRow><label style={labelStyle}>Financial Year</label><input value={financialYear} onChange={e => setFinancialYear(e.target.value)} style={inputStyle} placeholder="e.g. 2026-27" /></FormRow>
      </div>
      <ComponentRowsEditor components={components} setComponents={setComponents} catalog={catalog} />
      <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: 10 }}>
        <BtnPrimary onClick={submit} disabled={!valid || submitting}>{submitting ? 'Creating…' : 'Create Structure'}</BtnPrimary>
      </div>
    </div>
  );
}

function StructureDetailModal({ structure, catalog, onClose, onUpdated }: {
  structure: SalaryStructureResponse; catalog: SalaryComponentCatalogResponse[]; onClose: () => void; onUpdated: (s: SalaryStructureResponse) => void;
}) {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const [editing, setEditing] = useState(false);
  const [name, setName] = useState(structure.name);
  const [grade, setGrade] = useState(structure.grade ?? '');
  const [financialYear, setFinancialYear] = useState(structure.financialYear ?? '');
  const [components, setComponents] = useState<DraftComponent[]>(() => structure.components.map(fromResponse));
  const [submitting, setSubmitting] = useState(false);

  const valid = !!name.trim() && componentsAreValid(components);

  function startEditing() {
    // Reset from the current (possibly just-updated) structure, not whatever was left over from
    // a previous edit session on this same modal instance.
    setName(structure.name); setGrade(structure.grade ?? ''); setFinancialYear(structure.financialYear ?? '');
    setComponents(structure.components.map(fromResponse));
    setEditing(true);
  }

  async function save() {
    if (!valid) return;
    setSubmitting(true);
    try {
      const updated = await payrollApi.updateSalaryStructure(structure.id, {
        name: name.trim(), grade: grade.trim() || undefined, financialYear: financialYear.trim() || undefined,
        components: toComponentsPayload(components),
      }, token);
      onUpdated(updated);
      setEditing(false);
      showToast('success', 'Salary structure updated — takes effect on employees\' next payroll regenerate');
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Failed to update structure');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Modal title={structure.name} subtitle={[structure.grade, structure.financialYear].filter(Boolean).join(' · ') || null} onClose={onClose} wide>
      {!editing ? (
        <>
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
            <BtnPrimary onClick={startEditing}>Edit Structure</BtnPrimary>
          </div>
        </>
      ) : (
        <>
          <div style={{ fontSize: 12, color: 'var(--txt-dim)', marginBottom: 14 }}>
            Changes apply going forward only — already-generated payslips keep their original amounts.
            Every employee currently on this structure picks up the new components on their next payroll regenerate.
          </div>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: 12, marginBottom: 14 }}>
            <FormRow><label style={labelStyle}>Name *</label><input value={name} onChange={e => setName(e.target.value)} style={inputStyle} /></FormRow>
            <FormRow><label style={labelStyle}>Grade</label><input value={grade} onChange={e => setGrade(e.target.value)} style={inputStyle} /></FormRow>
            <FormRow><label style={labelStyle}>Financial Year</label><input value={financialYear} onChange={e => setFinancialYear(e.target.value)} style={inputStyle} /></FormRow>
          </div>
          <ComponentRowsEditor components={components} setComponents={setComponents} catalog={catalog} />
          <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10, marginTop: 14 }}>
            <BtnGhost onClick={() => { setEditing(false); setName(structure.name); setGrade(structure.grade ?? ''); setFinancialYear(structure.financialYear ?? ''); setComponents(structure.components.map(fromResponse)); }}>
              Cancel
            </BtnGhost>
            <BtnPrimary onClick={save} disabled={!valid || submitting}>{submitting ? 'Saving…' : 'Save Changes'}</BtnPrimary>
          </div>
        </>
      )}
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
  const viewing = structures.find(s => s.id === viewingId) ?? null;

  return (
    <div>
      <CreateStructureForm catalog={catalog} onCreated={onCreated} />
      <div>
        <SectionHead title="Existing Structures" />
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
      </div>
      {viewing && <StructureDetailModal structure={viewing} catalog={catalog} onClose={() => setViewingId(null)} onUpdated={onUpdated} />}
    </div>
  );
}
