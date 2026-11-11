import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Wallet, Plus, Lock, CheckCircle2, Clock, CalendarClock } from 'lucide-react';
import PageTitleBlock from '../components/PageTitleBlock';
import { usePaged } from '../components/usePaged';
import { TablePagination } from '../components/TablePagination';
import { useAuthStore } from '../store/authStore';
import { hasPermission } from '../lib/permissions';
import { useToast } from '../context/ToastContext';
import { payrollApi, type PayGroupResponse, type PayrollRunResponse } from '../api/payroll';
import {
  panelStyle, thStyle, tdStyle, fmtCurrency, fmtDate, StatusBadge, EmptyRow, SectionHead,
  Modal, FormRow, labelStyle, inputStyle, BtnPrimary, BtnGhost,
} from './payroll/payrollShared';
import './payroll/payroll.css';

function GenerateRunModal({ payGroups, defaultPayGroupId, defaultReferenceDate, onClose, onCreated }: {
  payGroups: PayGroupResponse[];
  defaultPayGroupId: string;
  defaultReferenceDate?: string;
  onClose: () => void;
  onCreated: (run: PayrollRunResponse) => void;
}) {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();
  const [payGroupId, setPayGroupId] = useState(defaultPayGroupId);
  const [referenceDate, setReferenceDate] = useState(defaultReferenceDate ?? '');
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

type MonthTileStatus = 'LOCKED' | 'COMPLETED' | 'CURRENT' | 'UPCOMING' | 'NOT_GENERATED';

interface MonthTile {
  key: string;
  label: string;
  cycleStart: Date;
  cycleEnd: Date;
  run: PayrollRunResponse | null;
  status: MonthTileStatus;
}

function toDateOnly(iso: string) {
  const [y, m, d] = iso.split('-').map(Number);
  return new Date(y, m - 1, d);
}

function sameDay(a: Date, b: Date) {
  return a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
}

/** Computes the Keka-style month strip for a pay group: the cycle "ending" in each of a window of
 * months, matched against any real run already generated for it. Exported as a pure function (same
 * convention as PayrollRunDetailPage.tsx's availableActions) so its month-boundary math and status
 * classification are unit-testable without rendering anything. `today` is a parameter rather than
 * `new Date()` read inline, for the same reason. */
export function buildMonthStrip(payGroup: PayGroupResponse, runs: PayrollRunResponse[], today: Date): MonthTile[] {
  // This pay group's cycle wraps the month boundary (e.g. 26th–25th), so "today"'s own cycle ends
  // THIS calendar month if we haven't yet passed cycleEndDay, otherwise it ends NEXT month.
  const currentEndMonth = new Date(today.getFullYear(), today.getMonth(), 1);
  if (today.getDate() > payGroup.cycleEndDay) currentEndMonth.setMonth(currentEndMonth.getMonth() + 1);

  const disbursedEnds = runs.filter(r => r.status === 'DISBURSED').map(r => toDateOnly(r.cycleEnd).getTime()).sort((a, b) => b - a);
  const mostRecentDisbursedEnd = disbursedEnds[0];

  const tiles: MonthTile[] = [];
  for (let offset = -5; offset <= 2; offset++) {
    const endMonth = new Date(currentEndMonth.getFullYear(), currentEndMonth.getMonth() + offset, 1);
    const cycleEnd = new Date(endMonth.getFullYear(), endMonth.getMonth(), payGroup.cycleEndDay);
    const cycleStart = new Date(endMonth.getFullYear(), endMonth.getMonth() - 1, payGroup.cycleStartDay);

    const run = runs.find(r => sameDay(toDateOnly(r.cycleStart), cycleStart) && sameDay(toDateOnly(r.cycleEnd), cycleEnd)) ?? null;
    const isCurrentCycle = today >= cycleStart && today <= cycleEnd;

    let status: MonthTileStatus;
    if (run?.status === 'DISBURSED') {
      // Demo/visual-only distinction — the backend has no separate "locked" state once DISBURSED,
      // this just mirrors Keka showing only the most recent disbursed cycle as "Completed" and
      // everything older as "Locked" (read-only in spirit; rollback is still the real guard).
      status = toDateOnly(run.cycleEnd).getTime() === mostRecentDisbursedEnd ? 'COMPLETED' : 'LOCKED';
    } else if (run) {
      status = 'CURRENT'; // DRAFT/SUBMITTED/APPROVED — in progress, regardless of calendar position
    } else if (isCurrentCycle) {
      status = 'CURRENT';
    } else if (cycleStart > today) {
      status = 'UPCOMING';
    } else {
      status = 'NOT_GENERATED';
    }

    tiles.push({
      key: `${endMonth.getFullYear()}-${endMonth.getMonth()}`,
      label: endMonth.toLocaleDateString('en-IN', { month: 'short', year: 'numeric' }),
      cycleStart, cycleEnd, run, status,
    });
  }
  return tiles;
}

const TILE_TONE: Record<MonthTileStatus, string> = {
  LOCKED: 'var(--txt-dim)',
  COMPLETED: 'var(--ok)',
  CURRENT: 'var(--brand)',
  UPCOMING: 'var(--txt-dim)',
  NOT_GENERATED: 'var(--warn)',
};

const TILE_ICON: Record<MonthTileStatus, typeof Lock> = {
  LOCKED: Lock,
  COMPLETED: CheckCircle2,
  CURRENT: Clock,
  UPCOMING: CalendarClock,
  NOT_GENERATED: CalendarClock,
};

const TILE_LABEL: Record<MonthTileStatus, string> = {
  LOCKED: 'Locked',
  COMPLETED: 'Completed',
  CURRENT: 'In Progress',
  UPCOMING: 'Upcoming',
  NOT_GENERATED: 'Not Generated',
};

function MonthStrip({ payGroup, runs, canConfigure, onOpenRun, onGenerateFor }: {
  payGroup: PayGroupResponse;
  runs: PayrollRunResponse[];
  canConfigure: boolean;
  onOpenRun: (runId: string) => void;
  onGenerateFor: (referenceDate: string) => void;
}) {
  const tiles = useMemo(() => buildMonthStrip(payGroup, runs, new Date()), [payGroup, runs]);

  function click(tile: MonthTile) {
    if (tile.run) { onOpenRun(tile.run.id); return; }
    if (tile.status === 'UPCOMING' || !canConfigure) return;
    onGenerateFor(tile.cycleEnd.toISOString().slice(0, 10));
  }

  return (
    <div style={{ display: 'flex', gap: 10, overflowX: 'auto', paddingBottom: 6, marginBottom: 22 }}>
      {tiles.map(tile => {
        const Icon = TILE_ICON[tile.status];
        const clickable = !!tile.run || (tile.status !== 'UPCOMING' && canConfigure);
        const isCurrent = tile.status === 'CURRENT';
        return (
          <button
            key={tile.key}
            onClick={() => click(tile)}
            disabled={!clickable}
            title={tile.run ? `${tile.run.employeeCount} employees` : tile.status === 'UPCOMING' ? 'Not yet due' : 'Click to generate'}
            style={{
              flex: '0 0 148px', textAlign: 'left', cursor: clickable ? 'pointer' : 'default',
              background: 'var(--panel)', border: `1px solid ${isCurrent ? 'var(--brand)' : 'var(--line)'}`,
              borderRadius: 12, padding: '14px 14px', boxShadow: isCurrent ? '0 0 0 1px var(--brand)' : 'none',
              opacity: tile.status === 'UPCOMING' ? 0.6 : 1,
            }}
          >
            <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--txt)', marginBottom: 8 }}>{tile.label}</div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 6, color: TILE_TONE[tile.status], fontSize: 12, fontWeight: 600 }}>
              <Icon size={14} /> {TILE_LABEL[tile.status]}
            </div>
            {tile.run && (
              <div style={{ marginTop: 8, fontSize: 11, color: 'var(--txt-mut)' }}>{fmtCurrency(tile.run.totalNet)} net</div>
            )}
          </button>
        );
      })}
    </div>
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
  const [showGenerate, setShowGenerate] = useState<{ referenceDate?: string } | null>(null);

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
  const selectedPayGroup = payGroups.find(pg => pg.id === payGroupId) ?? null;

  return (
    <div style={{ padding: '24px 28px' }}>
      <div className="nf-page-hdr" style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 20, flexWrap: 'wrap', gap: 12 }}>
        <PageTitleBlock icon={Wallet}>
          <h1 style={{ margin: 0, fontSize: 22, fontWeight: 800, letterSpacing: '-.01em', color: 'var(--txt)' }}>Payroll Runs</h1>
          <p style={{ margin: '2px 0 0', fontSize: 13, color: 'var(--txt-mut)' }}>Generate, review and process each pay cycle's batch.</p>
        </PageTitleBlock>
        {canConfigure && (
          <button onClick={() => setShowGenerate({})} style={{ display: 'flex', alignItems: 'center', gap: 7, background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 8, padding: '9px 16px', fontSize: 13, fontWeight: 600, cursor: 'pointer' }}>
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

      {selectedPayGroup && (
        <MonthStrip
          payGroup={selectedPayGroup}
          runs={runs}
          canConfigure={canConfigure}
          onOpenRun={runId => navigate(`/payroll-runs/${runId}`)}
          onGenerateFor={referenceDate => setShowGenerate({ referenceDate })}
        />
      )}

      <SectionHead title="All Runs" />
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
          defaultReferenceDate={showGenerate.referenceDate}
          onClose={() => setShowGenerate(null)}
          onCreated={run => { setRuns(prev => [run, ...prev.filter(r => r.id !== run.id)]); navigate(`/payroll-runs/${run.id}`); }}
        />
      )}
    </div>
  );
}
