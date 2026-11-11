import { useState } from 'react';
import { Plus, Trash2 } from 'lucide-react';
import { useAuthStore } from '../../store/authStore';
import { useToast } from '../../context/ToastContext';
import { payrollApi, type SalaryStructureResponse, type SalaryComponentRequest, type SalaryComponentCatalogResponse } from '../../api/payroll';
import { panelStyle, thStyle, tdStyle, EmptyRow, FormRow, labelStyle, inputStyle, BtnPrimary, BtnGhost, SectionHead } from './payrollShared';

type DraftComponent = SalaryComponentRequest;

function emptyComponent(defaultName: string): DraftComponent {
  return { componentName: defaultName, calcType: 'FIXED', value: 0, referenceComponentName: null, cap: null, useLowerOfCap: false };
}

function CreateStructureForm({ catalog, onCreated }: { catalog: SalaryComponentCatalogResponse[]; onCreated: (s: SalaryStructureResponse) => void }) {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const [name, setName] = useState('');
  const [grade, setGrade] = useState('');
  const [financialYear, setFinancialYear] = useState('');
  const [components, setComponents] = useState<DraftComponent[]>([emptyComponent(catalog[0]?.name ?? '')]);
  const [submitting, setSubmitting] = useState(false);

  function updateRow(i: number, patch: Partial<DraftComponent>) {
    setComponents(prev => prev.map((c, idx) => (idx === i ? { ...c, ...patch } : c)));
  }

  const valid = !!name.trim() && components.length > 0 && components.every(c =>
    !!c.componentName &&
    (c.calcType === 'BALANCING_REMAINDER' || (c.value != null && c.value >= 0)) &&
    (c.calcType !== 'PERCENT_OF_COMPONENT' || !!c.referenceComponentName));

  async function submit() {
    if (!valid) return;
    setSubmitting(true);
    try {
      const structure = await payrollApi.createSalaryStructure({
        name: name.trim(), grade: grade.trim() || undefined, financialYear: financialYear.trim() || undefined,
        components: components.map(c => ({
          ...c,
          value: c.calcType === 'BALANCING_REMAINDER' ? null : c.value,
          referenceComponentName: c.calcType === 'PERCENT_OF_COMPONENT' ? c.referenceComponentName : null,
        })),
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
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: 10 }}>
        <BtnGhost onClick={() => setComponents(prev => [...prev, emptyComponent(catalog[0]?.name ?? '')])}><Plus size={14} style={{ marginRight: 6, verticalAlign: 'text-bottom' }} />Add Component</BtnGhost>
        <BtnPrimary onClick={submit} disabled={!valid || submitting}>{submitting ? 'Creating…' : 'Create Structure'}</BtnPrimary>
      </div>
    </div>
  );
}

export function SalaryStructuresTab({ structures, catalog, onCreated }: {
  structures: SalaryStructureResponse[]; catalog: SalaryComponentCatalogResponse[]; onCreated: (s: SalaryStructureResponse) => void;
}) {
  return (
    <div>
      <CreateStructureForm catalog={catalog} onCreated={onCreated} />
      <div>
        <SectionHead title="Existing Structures" />
        <div style={{ fontSize: 12, color: 'var(--txt-dim)', marginBottom: 10 }}>
          A structure can't be edited once created — create a new one instead if its components need to change.
        </div>
        <div style={panelStyle}>
          <table className="nf-pr-table" style={{ width: '100%', borderCollapse: 'collapse' }}>
            <thead><tr>{['Name', 'Grade', 'Financial Year', 'Components'].map(h => <th key={h} style={thStyle}>{h}</th>)}</tr></thead>
            <tbody>
              {structures.map(s => (
                <tr key={s.id}>
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
    </div>
  );
}
