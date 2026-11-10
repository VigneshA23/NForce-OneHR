import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Wallet, Plus } from 'lucide-react';
import PageTitleBlock from '../components/PageTitleBlock';
import { usePaged } from '../components/usePaged';
import { TablePagination } from '../components/TablePagination';
import { useAuthStore } from '../store/authStore';
import { hasPermission } from '../lib/permissions';
import { useToast } from '../context/ToastContext';
import { payrollApi, type PayGroupResponse, type PayrollRunResponse } from '../api/payroll';
import {
  panelStyle, thStyle, tdStyle, fmtCurrency, fmtDate, StatusBadge, EmptyRow,
  Modal, FormRow, labelStyle, inputStyle, BtnPrimary, BtnGhost,
} from './payroll/payrollShared';
import './payroll/payroll.css';

function GenerateRunModal({ payGroups, defaultPayGroupId, onClose, onCreated }: {
  payGroups: PayGroupResponse[];
  defaultPayGroupId: string;
  onClose: () => void;
  onCreated: (run: PayrollRunResponse) => void;
}) {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const [payGroupId, setPayGroupId] = useState(defaultPayGroupId);
  const [referenceDate, setReferenceDate] = useState('');
  const [submitting, setSubmitting] = useState(false);

  // Self-heals a real race: this modal's initial value is whatever defaultPayGroupId was AT THE
  // MOMENT it mounted — if the page's own pay-group fetch hadn't resolved yet (opened the modal
  // immediately after navigating in), that was "" and stays "" forever, since useState's initial
  // value only applies once. The <select> below then masks this entirely: with no empty-value
  // placeholder option, a browser shows the first real option as selected regardless of the
  // actual bound value, so it LOOKS chosen while Generate silently stays disabled. This re-syncs
  // once payGroups actually arrives, same fix as PayrollSetupPage's structureId/payGroupId sync.
  useEffect(() => {
    if (!payGroupId && payGroups[0]) setPayGroupId(payGroups[0].id);
  }, [payGroups, payGroupId]);

  async function submit() {
    if (!payGroupId) return;
    setSubmitting(true);
    try {
      const run = await payrollApi.generateDraft({ payGroupId, referenceDate: referenceDate || undefined }, token);
      onCreated(run);
      onClose();
      showToast('success', 'Draft run generated');
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Failed to generate draft');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Modal title="Generate Payroll Run" subtitle="Creates (or regenerates, while still DRAFT) the payslips for the cycle containing this date." onClose={onClose}>
      <FormRow>
        <label style={labelStyle}>Pay Group *</label>
        <select value={payGroupId} onChange={e => setPayGroupId(e.target.value)} style={inputStyle}>
          {payGroups.map(pg => <option key={pg.id} value={pg.id}>{pg.name}</option>)}
        </select>
      </FormRow>
      <FormRow>
        <label style={labelStyle}>Reference Date</label>
        <input type="date" value={referenceDate} onChange={e => setReferenceDate(e.target.value)} style={inputStyle} />
      </FormRow>
      <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
        <BtnGhost onClick={onClose}>Cancel</BtnGhost>
        <BtnPrimary onClick={submit} disabled={!payGroupId || submitting}>{submitting ? 'Generating…' : 'Generate'}</BtnPrimary>
      </div>
    </Modal>
  );
}

export default function PayrollRunsPage() {
  const token = useAuthStore(s => s.token)!;
  const permissions = useAuthStore(s => s.permissions);
  const canConfigure = hasPermission(permissions, 'payroll', 'CONFIGURE', 'ALL');
  const navigate = useNavigate();
  const { showToast } = useToast();

  const [payGroups, setPayGroups] = useState<PayGroupResponse[]>([]);
  const [payGroupId, setPayGroupId] = useState('');
  const [runs, setRuns] = useState<PayrollRunResponse[]>([]);
  const [showGenerate, setShowGenerate] = useState(false);

  useEffect(() => {
    payrollApi.listPayGroups(token).then(groups => {
      setPayGroups(groups);
      if (groups.length > 0) setPayGroupId(groups[0].id);
    }).catch(() => {});
  }, [token]);

  useEffect(() => {
    if (!payGroupId) return;
    payrollApi.listRuns(payGroupId, token).then(setRuns).catch(() => showToast('error', 'Failed to load payroll runs'));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [payGroupId, token]);

  const { pageRows, pagerProps } = usePaged(runs, [payGroupId]);

  return (
    <div style={{ padding: '24px 28px' }}>
      <div className="nf-page-hdr" style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 20, flexWrap: 'wrap', gap: 12 }}>
        <PageTitleBlock icon={Wallet}>
          <h1 style={{ margin: 0, fontSize: 22, fontWeight: 800, letterSpacing: '-.01em', color: 'var(--txt)' }}>Payroll Runs</h1>
          <p style={{ margin: '2px 0 0', fontSize: 13, color: 'var(--txt-mut)' }}>Generate, review and process each pay cycle's batch.</p>
        </PageTitleBlock>
        {canConfigure && (
          <button onClick={() => setShowGenerate(true)} style={{ display: 'flex', alignItems: 'center', gap: 7, background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 8, padding: '9px 16px', fontSize: 13, fontWeight: 600, cursor: 'pointer' }}>
            <Plus size={16} /> Generate New Run
          </button>
        )}
      </div>

      <div style={{ marginBottom: 16, maxWidth: 320 }}>
        <label style={labelStyle}>Pay Group</label>
        <select value={payGroupId} onChange={e => setPayGroupId(e.target.value)} style={inputStyle}>
          {payGroups.map(pg => <option key={pg.id} value={pg.id}>{pg.name}</option>)}
        </select>
      </div>

      <div style={panelStyle}>
        <table className="nf-pr-table nf-pr-table-clickable" style={{ width: '100%', borderCollapse: 'collapse' }}>
          <thead>
            <tr>
              {['Cycle', 'Status', 'Employees', 'Gross', 'Deductions', 'Net', 'Created'].map(h => <th key={h} style={thStyle}>{h}</th>)}
            </tr>
          </thead>
          <tbody>
            {pageRows.map(run => (
              <tr key={run.id} onClick={() => navigate(`/payroll-runs/${run.id}`)}>
                <td style={tdStyle}>{fmtDate(run.cycleStart)} – {fmtDate(run.cycleEnd)}</td>
                <td style={tdStyle}><StatusBadge status={run.status} /></td>
                <td style={tdStyle}>{run.employeeCount}</td>
                <td style={tdStyle}>{fmtCurrency(run.totalGross)}</td>
                <td style={tdStyle}>{fmtCurrency(run.totalDeductions)}</td>
                <td style={tdStyle}>{fmtCurrency(run.totalNet)}</td>
                <td style={tdStyle}>{fmtDate(run.createdAt)}</td>
              </tr>
            ))}
            {pageRows.length === 0 && <EmptyRow cols={7} msg={payGroupId ? 'No payroll runs for this pay group yet.' : 'No pay groups configured yet.'} />}
          </tbody>
        </table>
        <TablePagination {...pagerProps} noun="runs" />
      </div>

      {showGenerate && (
        <GenerateRunModal
          payGroups={payGroups}
          defaultPayGroupId={payGroupId}
          onClose={() => setShowGenerate(false)}
          onCreated={run => { setRuns(prev => [run, ...prev.filter(r => r.id !== run.id)]); navigate(`/payroll-runs/${run.id}`); }}
        />
      )}
    </div>
  );
}
