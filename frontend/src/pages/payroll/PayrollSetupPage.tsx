import { useEffect, useState } from 'react';
import { Settings, Plus, Trash2 } from 'lucide-react';
import PageTitleBlock from '../../components/PageTitleBlock';
import { useAuthStore } from '../../store/authStore';
import { hasPermission } from '../../lib/permissions';
import { useToast } from '../../context/ToastContext';
import {
  payrollApi, type SalaryStructureResponse, type SalaryComponentRequest,
  type SalaryComponentCatalogResponse, type EmployeeCompensationResponse,
} from '../../api/payroll';
import {
  panelStyle, thStyle, tdStyle, fmtCurrency, fmtDate, EmptyRow, FormRow, labelStyle, inputStyle,
  BtnPrimary, BtnGhost, SectionHead, useEmployeeDirectory,
} from './payrollShared';
import './payroll.css';

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

function AssignCompensationForm({ structures }: { structures: SalaryStructureResponse[] }) {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const { employees } = useEmployeeDirectory();
  const [employeeId, setEmployeeId] = useState('');
  const [structureId, setStructureId] = useState(structures[0]?.id ?? '');
  const [ctc, setCtc] = useState('');
  const [effectiveFrom, setEffectiveFrom] = useState('2025-01-01');
  const [current, setCurrent] = useState<EmployeeCompensationResponse | null>(null);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (!structureId && structures[0]) setStructureId(structures[0].id);
  }, [structures, structureId]);

  useEffect(() => {
    if (!employeeId) { setCurrent(null); return; }
    payrollApi.getCurrentCompensation(employeeId, token).then(setCurrent).catch(() => setCurrent(null));
  }, [employeeId, token]);

  const valid = !!employeeId && !!structureId && Number(ctc) > 0 && !!effectiveFrom;

  async function submit() {
    if (!valid) return;
    setSubmitting(true);
    try {
      const comp = await payrollApi.assignCompensation({ employeeId, structureId, ctc: Number(ctc), effectiveFrom }, token);
      setCurrent(comp);
      showToast('success', 'Compensation assigned');
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Failed to assign compensation');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 14, padding: '18px 22px' }}>
      <SectionHead title="Assign Compensation" />
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: 12, marginBottom: 14 }}>
        <FormRow>
          <label style={labelStyle}>Employee *</label>
          <select value={employeeId} onChange={e => setEmployeeId(e.target.value)} style={inputStyle}>
            <option value="">Select…</option>
            {employees.map(e => <option key={e.userId} value={e.userId}>{e.fullName} ({e.departmentName ?? 'No dept'})</option>)}
          </select>
        </FormRow>
        <FormRow>
          <label style={labelStyle}>Salary Structure *</label>
          <select value={structureId} onChange={e => setStructureId(e.target.value)} style={inputStyle}>
            {structures.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}
          </select>
        </FormRow>
        <FormRow>
          <label style={labelStyle}>Annual CTC *</label>
          <input type="number" min={0} value={ctc} onChange={e => setCtc(e.target.value)} style={inputStyle} placeholder="e.g. 1200000" />
        </FormRow>
        <FormRow>
          <label style={labelStyle}>Effective From *</label>
          <input type="date" value={effectiveFrom} onChange={e => setEffectiveFrom(e.target.value)} style={inputStyle} />
        </FormRow>
      </div>
      {current && (
        <div style={{ fontSize: 13, color: 'var(--txt-mut)', marginBottom: 14 }}>
          Current: <strong style={{ color: 'var(--txt)' }}>{current.structureName}</strong>, {fmtCurrency(current.ctc)} CTC, effective {fmtDate(current.effectiveFrom)}
        </div>
      )}
      <BtnPrimary onClick={submit} disabled={!valid || submitting}>{submitting ? 'Assigning…' : 'Assign Compensation'}</BtnPrimary>
    </div>
  );
}

export default function PayrollSetupPage() {
  const token = useAuthStore(s => s.token)!;
  const permissions = useAuthStore(s => s.permissions);
  const canConfigure = hasPermission(permissions, 'payroll', 'CONFIGURE', 'ALL');
  const [structures, setStructures] = useState<SalaryStructureResponse[]>([]);
  const [catalog, setCatalog] = useState<SalaryComponentCatalogResponse[]>([]);

  function reloadStructures() {
    payrollApi.listSalaryStructures(token).then(setStructures).catch(() => {});
  }

  useEffect(reloadStructures, [token]);
  useEffect(() => {
    payrollApi.listSalaryComponents(token)
      .then(all => setCatalog(all.filter(c => ['EARNING', 'REIMBURSEMENT', 'DEDUCTION'].includes(c.type) && c.active)))
      .catch(() => {});
  }, [token]);

  if (!canConfigure) {
    return <div style={{ padding: '24px 28px', color: 'var(--txt-mut)' }}>You don't have permission to configure payroll setup.</div>;
  }

  return (
    <div style={{ padding: '24px 28px' }}>
      <PageTitleBlock icon={Settings} style={{ marginBottom: 20 }}>
        <h1 style={{ margin: 0, fontSize: 22, fontWeight: 800, letterSpacing: '-.01em', color: 'var(--txt)' }}>Payroll Setup</h1>
        <p style={{ margin: '2px 0 0', fontSize: 13, color: 'var(--txt-mut)' }}>Salary structures and employee compensation — the data a payroll run needs before it can generate.</p>
      </PageTitleBlock>

      <CreateStructureForm catalog={catalog} onCreated={s => setStructures(prev => [s, ...prev])} />

      <div style={{ marginBottom: 20 }}>
        <SectionHead title="Existing Structures" />
        <div style={panelStyle}>
          <table style={{ width: '100%', borderCollapse: 'collapse' }}>
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

      {structures.length > 0 && <AssignCompensationForm structures={structures} />}
    </div>
  );
}
