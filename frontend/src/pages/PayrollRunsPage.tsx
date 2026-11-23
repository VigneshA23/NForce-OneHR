import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Wallet, Lock, CheckCircle2, Clock, CalendarClock, Loader2, ListChecks, IndianRupee, Hourglass } from 'lucide-react';
import DashTile from '../components/DashTile';
import PageTitleBlock from '../components/PageTitleBlock';
import { usePaged } from '../components/usePaged';
import { TablePagination } from '../components/TablePagination';
import { useAuthStore } from '../store/authStore';
import { hasPermission } from '../lib/permissions';
import { useToast } from '../context/ToastContext';
import { payrollApi, type PayGroupResponse, type PayrollRunResponse } from '../api/payroll';
import {
  panelStyle, thStyle, tdStyle, fmtCurrency, fmtDate, StatusBadge, EmptyRow, SectionHead,
  labelStyle, inputStyle,
} from './payroll/payrollShared';
import './payroll/payroll.css';

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
      // Demo/visual-only distinction - the backend has no separate "locked" state once DISBURSED,
      // this just mirrors Keka showing only the most recent disbursed cycle as "Completed" and
      // everything older as "Locked" (read-only in spirit; rollback is still the real guard).
      status = toDateOnly(run.cycleEnd).getTime() === mostRecentDisbursedEnd ? 'COMPLETED' : 'LOCKED';
    } else if (run) {
      status = 'CURRENT'; // DRAFT/SUBMITTED/APPROVED - in progress, regardless of calendar position
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

function MonthStrip({ payGroup, runs, canConfigure, generatingKey, onOpenRun, onGenerate }: {
  payGroup: PayGroupResponse;
  runs: PayrollRunResponse[];
  canConfigure: boolean;
  generatingKey: string | null;
  onOpenRun: (runId: string) => void;
  onGenerate: (tile: MonthTile) => void;
}) {
  const tiles = useMemo(() => buildMonthStrip(payGroup, runs, new Date()), [payGroup, runs]);

  function click(tile: MonthTile) {
    if (generatingKey) return;
    if (tile.run) { onOpenRun(tile.run.id); return; }
    if (tile.status === 'UPCOMING' || !canConfigure) return;
    onGenerate(tile);
  }

  return (
    <div className="nf-pr-strip-wrap">
    <div className="nf-pr-strip">
      {tiles.map(tile => {
        const Icon = TILE_ICON[tile.status];
        const isGenerating = generatingKey === tile.key;
        const clickable = !generatingKey && (!!tile.run || (tile.status !== 'UPCOMING' && canConfigure));
        const isCurrent = tile.status === 'CURRENT';
        return (
          <button
            key={tile.key}
            onClick={() => click(tile)}
            disabled={!clickable}
            className={`nf-pr-cycle${isCurrent ? ' is-current' : ''}${tile.status === 'UPCOMING' ? ' is-upcoming' : ''}`}
            style={{ ['--mk' as string]: TILE_TONE[tile.status] }}
            title={tile.run ? `Open this run (${tile.run.employeeCount} employees)` : tile.status === 'UPCOMING' ? 'Not yet due' : `Click to generate the ${fmtDate(tile.cycleStart.toISOString().slice(0, 10))} to ${fmtDate(tile.cycleEnd.toISOString().slice(0, 10))} cycle`}
          >
            <span className="nf-pr-cycle-month">{tile.label}</span>
            <span className="nf-pr-cycle-state">
              {isGenerating ? <Loader2 size={14} className="nf-spin" /> : <Icon size={14} />}
              {isGenerating ? 'Generating…' : TILE_LABEL[tile.status]}
            </span>
            {tile.run
              ? <span className="nf-pr-cycle-net">{fmtCurrency(tile.run.totalNet)} net</span>
              : <span className="nf-pr-cycle-net is-empty">{tile.status === 'UPCOMING' ? 'Not yet due' : canConfigure ? 'Click to generate' : 'No run'}</span>}
          </button>
        );
      })}
    </div>
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
  const [generatingKey, setGeneratingKey] = useState<string | null>(null);

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

  // Each tile already knows its own exact cycle (cycleStart/cycleEnd from buildMonthStrip's own
  // boundary math), so there's nothing left for a dialog to ask - generate straight off the click,
  // the same instinct as regenerate/submit/approve/disburse already being one click each.
  async function generateForTile(tile: MonthTile) {
    if (!payGroupId) return;
    setGeneratingKey(tile.key);
    try {
      const run = await payrollApi.generateDraft({ payGroupId, referenceDate: tile.cycleEnd.toISOString().slice(0, 10) }, token);
      setRuns(prev => [run, ...prev.filter(r => r.id !== run.id)]);
      showToast('success', 'Draft run generated');
      navigate(`/payroll-runs/${run.id}`);
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Failed to generate draft');
    } finally {
      setGeneratingKey(null);
    }
  }

  return (
    <div style={{ padding: '24px 28px' }}>
      <div className="nf-pr-hero">
        <PageTitleBlock icon={Wallet}>
          <h1 style={{ margin: 0, fontSize: 22, fontWeight: 800, letterSpacing: '-.01em', color: 'var(--txt)' }}>Payroll Runs</h1>
          <p style={{ margin: '2px 0 0', fontSize: 13, color: 'var(--txt-mut)' }}>Generate, review and process each pay cycle's batch.</p>
        </PageTitleBlock>
        <div className="nf-pr-hero-select">
          <label style={labelStyle}>Pay Group</label>
          <select value={payGroupId} onChange={e => setPayGroupId(e.target.value)} style={inputStyle}>
            {payGroups.map(pg => <option key={pg.id} value={pg.id}>{pg.name}</option>)}
          </select>
        </div>
      </div>

      <div className="nf-pr-tiles" style={{ marginBottom: 18 }}>
        <DashTile icon={<ListChecks size={20} />} label="Total Runs" value={String(runs.length)} accent="var(--brand)" />
        <DashTile icon={<Hourglass size={20} />} label="In Progress" value={String(runs.filter(r => r.status !== 'DISBURSED').length)} sub="Draft, submitted or approved" accent="var(--warn)" />
        <DashTile icon={<CheckCircle2 size={20} />} label="Disbursed" value={String(runs.filter(r => r.status === 'DISBURSED').length)} accent="var(--ok)" />
        <DashTile icon={<IndianRupee size={20} />} label="Latest Net Pay" value={runs.length ? fmtCurrency([...runs].sort((a, b) => b.cycleEnd.localeCompare(a.cycleEnd))[0].totalNet) : fmtCurrency(0)} sub={runs.length ? 'Most recent cycle' : undefined} accent="var(--info)" />
      </div>

      {selectedPayGroup && (
        <MonthStrip
          payGroup={selectedPayGroup}
          runs={runs}
          canConfigure={canConfigure}
          generatingKey={generatingKey}
          onOpenRun={runId => navigate(`/payroll-runs/${runId}`)}
          onGenerate={generateForTile}
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
              <tr key={run.id} tabIndex={0} title="Open this run" onClick={() => navigate(`/payroll-runs/${run.id}`)} onKeyDown={e => { if (e.key === 'Enter') navigate(`/payroll-runs/${run.id}`); }}>
                <td style={{ ...tdStyle, color: 'var(--txt)', fontWeight: 600 }}>{fmtDate(run.cycleStart)} to {fmtDate(run.cycleEnd)}</td>
                <td style={tdStyle}><StatusBadge status={run.status} /></td>
                <td style={tdStyle}>{run.employeeCount}</td>
                <td style={tdStyle}>{fmtCurrency(run.totalGross)}</td>
                <td style={tdStyle}>{fmtCurrency(run.totalDeductions)}</td>
                <td style={{ ...tdStyle, color: 'var(--txt)', fontWeight: 700 }}>{fmtCurrency(run.totalNet)}</td>
                <td style={tdStyle}>{fmtDate(run.createdAt)}</td>
              </tr>
            ))}
            {pageRows.length === 0 && <EmptyRow cols={7} msg={payGroupId ? 'No payroll runs for this pay group yet.' : 'No pay groups configured yet.'} />}
          </tbody>
        </table>
        <TablePagination {...pagerProps} noun="runs" />
      </div>
    </div>
  );
}
