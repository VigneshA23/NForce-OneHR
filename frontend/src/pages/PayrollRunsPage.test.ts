import { describe, expect, it } from 'vitest';
import { buildMonthStrip } from './PayrollRunsPage';
import type { PayGroupResponse, PayrollRunResponse, PayrollRunStatus } from '../api/payroll';

const payGroup: PayGroupResponse = {
  id: 'pg-1', name: 'Default', legalEntity: 'NForce', frequency: 'MONTHLY', currency: 'INR',
  cycleStartDay: 26, cycleEndDay: 25, processingDay: 28, active: true,
};

function run(cycleStart: string, cycleEnd: string, status: PayrollRunStatus): PayrollRunResponse {
  return {
    id: `${cycleEnd}-${status}`, payGroupId: 'pg-1', cycleStart, cycleEnd, status,
    employeeCount: 2, totalGross: 100000, totalDeductions: 10000, totalNet: 90000,
    submittedAt: null, approvedAt: null, disbursedAt: null, lastRolledBackAt: null,
    lastRollbackReason: null, approvalStages: null, pendingApprovalStage: null, createdAt: '2026-01-01T00:00:00',
  };
}

// Today's date (Nov 10, 2026) falls inside the Oct 26 – Nov 25 cycle (ends in November), so that's
// this pay group's "current" cycle for every test below.
const today = new Date(2026, 10, 10);

describe('buildMonthStrip', () => {
  it('marks the cycle containing today as CURRENT when no run exists for it yet', () => {
    const tiles = buildMonthStrip(payGroup, [], today);
    const novTile = tiles.find(t => t.label === 'Nov 2026')!;
    expect(novTile.status).toBe('CURRENT');
    expect(novTile.run).toBeNull();
  });

  it('marks cycles that start after today as UPCOMING', () => {
    const tiles = buildMonthStrip(payGroup, [], today);
    const decTile = tiles.find(t => t.label === 'Dec 2026')!;
    expect(decTile.status).toBe('UPCOMING');
  });

  it('marks a past cycle with no run as NOT_GENERATED', () => {
    const tiles = buildMonthStrip(payGroup, [], today);
    const augTile = tiles.find(t => t.label === 'Aug 2026')!;
    expect(augTile.status).toBe('NOT_GENERATED');
  });

  it('marks any non-DISBURSED run as CURRENT regardless of calendar position', () => {
    const draft = run('2026-07-26', '2026-08-25', 'DRAFT');
    const tiles = buildMonthStrip(payGroup, [draft], today);
    const augTile = tiles.find(t => t.label === 'Aug 2026')!;
    expect(augTile.status).toBe('CURRENT');
    expect(augTile.run).toBe(draft);
  });

  it('marks the most recently DISBURSED cycle as COMPLETED and older DISBURSED cycles as LOCKED', () => {
    const older = run('2026-08-26', '2026-09-25', 'DISBURSED');
    const mostRecent = run('2026-09-26', '2026-10-25', 'DISBURSED');
    const tiles = buildMonthStrip(payGroup, [older, mostRecent], today);
    expect(tiles.find(t => t.label === 'Sept 2026')!.status).toBe('LOCKED');
    expect(tiles.find(t => t.label === 'Oct 2026')!.status).toBe('COMPLETED');
  });

  it('spans exactly 8 months: 5 before the current cycle through 2 after it', () => {
    const tiles = buildMonthStrip(payGroup, [], today);
    expect(tiles).toHaveLength(8);
    expect(tiles[0].label).toBe('Jun 2026');
    expect(tiles[tiles.length - 1].label).toBe('Jan 2027');
  });
});
