import { describe, expect, it } from 'vitest';
import type { MyAllocation } from '../api/projectAllocations';
import {
  MY_ALLOCATIONS_EMPTY_MESSAGE, STATUS_LABEL, clientLabel, formatAllocationDate, formatAllocationRange,
  formatPercent, groupMyAllocations, isProjectClosed, projectHoldLabel, projectLabel,
} from './myAllocations';

const alloc = (over: Partial<MyAllocation> = {}): MyAllocation => ({
  id: 'a1', projectName: 'Apollo', clientName: 'Acme Corp', capacityPercent: 60,
  startDate: '2026-10-01', endDate: '2027-03-31', projectStatus: 'ACTIVE', status: 'CURRENT', ...over,
});

describe('groupMyAllocations (US-B08 current vs upcoming)', () => {
  it('splits by status and keeps the server order within each group', () => {
    const list = [
      alloc({ id: 'c1', status: 'CURRENT' }),
      alloc({ id: 'u1', status: 'UPCOMING' }),
      alloc({ id: 'c2', status: 'CURRENT' }),
      alloc({ id: 'u2', status: 'UPCOMING' }),
    ];

    const { current, upcoming } = groupMyAllocations(list);

    expect(current.map(a => a.id)).toEqual(['c1', 'c2']);
    expect(upcoming.map(a => a.id)).toEqual(['u1', 'u2']);
  });

  it('handles only-current, only-upcoming and none', () => {
    expect(groupMyAllocations([alloc()]).upcoming).toEqual([]);
    expect(groupMyAllocations([alloc({ status: 'UPCOMING' })]).current).toEqual([]);
    expect(groupMyAllocations([])).toEqual({ current: [], upcoming: [], closed: [] });
  });

  it('puts an allocation of a CLOSED project in the closed group, whatever its dates', () => {
    const list = [
      alloc({ id: 'active', projectStatus: 'ACTIVE', status: 'CURRENT' }),
      alloc({ id: 'closed-current', projectStatus: 'CLOSED', status: 'CURRENT' }),
      alloc({ id: 'closed-upcoming', projectStatus: 'CLOSED', status: 'UPCOMING' }),
      alloc({ id: 'on-hold', projectStatus: 'ON_HOLD', status: 'UPCOMING' }),
    ];

    const { current, upcoming, closed } = groupMyAllocations(list);

    expect(current.map(a => a.id)).toEqual(['active']);
    expect(upcoming.map(a => a.id)).toEqual(['on-hold']);
    expect(closed.map(a => a.id)).toEqual(['closed-current', 'closed-upcoming']);
  });

  it('keeps the closed group in the server order and never drops an allocation', () => {
    const list = [alloc({ id: 'a' }), alloc({ id: 'c1', projectStatus: 'CLOSED' }), alloc({ id: 'c2', projectStatus: 'CLOSED' })];

    const g = groupMyAllocations(list);

    expect(g.closed.map(a => a.id)).toEqual(['c1', 'c2']);
    expect(g.current.length + g.upcoming.length + g.closed.length).toBe(list.length);
  });

  it('treats a missing project status as a still-running project, not a closed one', () => {
    expect(groupMyAllocations([alloc({ projectStatus: null })]).current).toHaveLength(1);
    expect(isProjectClosed({ projectStatus: null })).toBe(false);
    expect(isProjectClosed({ projectStatus: 'CLOSED' })).toBe(true);
  });

  it('supports several current allocations at once', () => {
    const { current } = groupMyAllocations([alloc({ id: 'x' }), alloc({ id: 'y' }), alloc({ id: 'z' })]);
    expect(current).toHaveLength(3);
  });
});

describe('date and percent formatting', () => {
  it('formats an ISO date as day month year without shifting the day', () => {
    expect(formatAllocationDate('2026-10-01')).toBe('01 Oct 2026');
    expect(formatAllocationDate('2027-03-31')).toBe('31 Mar 2027');
    expect(formatAllocationDate('2028-02-29')).toBe('29 Feb 2028');
  });

  it('formats the date range with an en dash, start then end', () => {
    expect(formatAllocationRange('2026-10-01', '2027-03-31')).toBe('01 Oct 2026 – 31 Mar 2027');
  });

  it('shows a dash for a missing or invalid date instead of throwing', () => {
    expect(formatAllocationDate(null)).toBe('–');
    expect(formatAllocationDate('')).toBe('–');
    expect(formatAllocationDate('not-a-date')).toBe('–');
  });

  it('shows the percentage exactly as stored', () => {
    expect(formatPercent(60)).toBe('60%');
    expect(formatPercent(100)).toBe('100%');
    expect(formatPercent(1)).toBe('1%');
  });
});

describe('project hold label', () => {
  it('only labels a paused project', () => {
    expect(projectHoldLabel('ON_HOLD')).toBe('Project on hold');
    expect(projectHoldLabel('ACTIVE')).toBeNull();
    expect(projectHoldLabel('CLOSED')).toBeNull();   // a closed project gets the Closed label, not the hold one
    expect(projectHoldLabel(null)).toBeNull();
  });
});

describe('labels for missing relationship data', () => {
  it('falls back to a placeholder when the project or client is missing', () => {
    expect(projectLabel(null)).toBe('Unknown project');
    expect(projectLabel('  ')).toBe('Unknown project');
    expect(clientLabel(null)).toBe('No client');
    expect(clientLabel('')).toBe('No client');
  });

  it('shows the real names when present', () => {
    expect(projectLabel('Apollo')).toBe('Apollo');
    expect(clientLabel('Acme Corp')).toBe('Acme Corp');
  });

  it('has distinct status labels and a clear empty-state message', () => {
    expect(STATUS_LABEL.CURRENT).toBe('Current');
    expect(STATUS_LABEL.UPCOMING).toBe('Upcoming');
    expect(MY_ALLOCATIONS_EMPTY_MESSAGE).toContain('no current or upcoming');
  });
});
