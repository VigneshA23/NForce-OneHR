import type { MyAllocation } from '../api/projectAllocations';

/** US-B08 display helpers for the profile's "My Project Allocations" section (pure, so they are unit-tested). */

export const MY_ALLOCATIONS_EMPTY_MESSAGE = 'You have no current or upcoming project allocations.';
export const NO_CURRENT_MESSAGE = 'No current allocations.';
export const NO_UPCOMING_MESSAGE = 'No upcoming allocations.';
export const CLOSED_PROJECT_LABEL = 'Project closed';

export function isProjectClosed(a: Pick<MyAllocation, 'projectStatus'>): boolean {
  return a.projectStatus === 'CLOSED';
}

/**
 * Splits the server list into Current, Upcoming and Closed, keeping the server's order (active projects
 * first, then by start date). An allocation on a CLOSED project goes in `closed` whatever its dates, so the
 * Current and Upcoming groups only ever hold allocations of projects that are still running.
 */
export function groupMyAllocations(list: MyAllocation[]): {
  current: MyAllocation[]; upcoming: MyAllocation[]; closed: MyAllocation[];
} {
  const open = list.filter(a => !isProjectClosed(a));
  return {
    current: open.filter(a => a.status === 'CURRENT'),
    upcoming: open.filter(a => a.status === 'UPCOMING'),
    closed: list.filter(isProjectClosed),
  };
}

/** "2026-10-01" → "01 Oct 2026". Read in UTC so the day never shifts with the viewer's timezone. */
export function formatAllocationDate(iso: string | null | undefined): string {
  if (!iso) return '–';
  const d = new Date(`${iso}T00:00:00Z`);
  if (Number.isNaN(d.getTime())) return '–';
  return d.toLocaleDateString('en-GB', { day: '2-digit', month: 'short', year: 'numeric', timeZone: 'UTC' });
}

export function formatAllocationRange(start: string, end: string): string {
  return `${formatAllocationDate(start)} – ${formatAllocationDate(end)}`;
}

export function projectLabel(name: string | null): string {
  return name && name.trim() ? name : 'Unknown project';
}

export function clientLabel(name: string | null): string {
  return name && name.trim() ? name : 'No client';
}

/** "60%" — whole percent, as stored (1–100). */
export function formatPercent(value: number): string {
  return `${value}%`;
}

/** A small extra label only when the project is paused; null for a normal (active or unknown) project. */
export function projectHoldLabel(projectStatus: MyAllocation['projectStatus']): string | null {
  return projectStatus === 'ON_HOLD' ? 'Project on hold' : null;
}

export const STATUS_LABEL: Record<MyAllocation['status'], string> = {
  CURRENT: 'Current',
  UPCOMING: 'Upcoming',
};
