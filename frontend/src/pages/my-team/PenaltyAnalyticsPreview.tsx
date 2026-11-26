/**
 * ============================================================================
 *  MOCK / VISUAL PREVIEW - NOT CONNECTED TO ANY BACKEND
 * ============================================================================
 *  PN-06: the REAL Penalty Analytics view, fed by a small in-browser stand-in for the endpoint that
 *  filters a sample dataset exactly the way the server does (date range, rule, department, status;
 *  in-force-only deduction days; every month in the range). Switch between HR (whole organisation)
 *  and Manager (own team only) to see the scope difference. Export to Excel is the real export.
 *    /penalty-analytics-preview
 *  Theme colour and Light/Dark follow the app's real settings (see PreviewChrome).
 */
import { useEffect, useMemo, useState } from 'react';
import { BarChart3 } from 'lucide-react';
import PenaltyAnalyticsTab from './PenaltyAnalyticsTab';
import { PreviewChrome, PreviewSection } from '../penalization/PreviewChrome';
import type { PenaltyAnalytics, PenaltyAnalyticsBucket, PenaltyAnalyticsEmployee } from '../../api/attendance';

type Status = 'PENDING_REVIEW' | 'CANCELLED' | 'REVERSED';
interface P { emp: string; type: string; status: Status; days: number; date: string }

const PEOPLE: Record<string, { name: string; code: string; dept: string; team: boolean }> = {
  e1: { name: 'Asha Rao', code: 'NF-101', dept: 'Engineering', team: true },
  e2: { name: 'Ravi Kumar', code: 'NF-102', dept: 'Engineering', team: true },
  e3: { name: 'Meera Nair', code: 'NF-103', dept: 'Customer Support', team: false },
  e4: { name: 'Vikram Singh', code: 'NF-104', dept: 'Customer Support', team: false },
  e5: { name: 'Priya Shah', code: 'NF-105', dept: 'Finance', team: false },
  e6: { name: 'Karan Mehta', code: 'NF-106', dept: 'Sales', team: false },
  e7: { name: 'Divya Iyer', code: 'NF-107', dept: 'Engineering', team: true },
};

const RULE_LABEL: Record<string, string> = {
  NO_ATTENDANCE: 'No Attendance', LATE_ARRIVAL: 'Late Arrival', EARLY_DEPARTURE: 'Early Going',
  WORK_HOURS_SHORTAGE: 'Shortage', MISSING_PUNCH: 'Missing Logs', EXCESS_BREAK: 'Excess Break',
};

// A readable spread over Aug to Nov 2026: a bad September for Support, Early Going growing later.
const DATA: P[] = [
  ...['2026-08-04', '2026-08-12', '2026-08-25'].map((d, i) => ({ emp: ['e1', 'e3', 'e5'][i], type: 'LATE_ARRIVAL', status: 'PENDING_REVIEW' as Status, days: 0.5, date: d })),
  ...['2026-09-02', '2026-09-03', '2026-09-08', '2026-09-10', '2026-09-15', '2026-09-22', '2026-09-24'].map((d, i) => ({ emp: ['e3', 'e4', 'e3', 'e4', 'e3', 'e6', 'e1'][i], type: ['LATE_ARRIVAL', 'LATE_ARRIVAL', 'MISSING_PUNCH', 'LATE_ARRIVAL', 'WORK_HOURS_SHORTAGE', 'NO_ATTENDANCE', 'LATE_ARRIVAL'][i], status: (i === 4 ? 'CANCELLED' : 'PENDING_REVIEW') as Status, days: [0.5, 0.5, 0.5, 0.5, 1, 1, 0.5][i], date: d })),
  ...['2026-10-01', '2026-10-06', '2026-10-09', '2026-10-13', '2026-10-15', '2026-10-20', '2026-10-27', '2026-10-29'].map((d, i) => ({ emp: ['e2', 'e1', 'e7', 'e3', 'e2', 'e1', 'e4', 'e5'][i], type: ['EARLY_DEPARTURE', 'EARLY_DEPARTURE', 'LATE_ARRIVAL', 'MISSING_PUNCH', 'EARLY_DEPARTURE', 'EXCESS_BREAK', 'LATE_ARRIVAL', 'WORK_HOURS_SHORTAGE'][i], status: (i === 3 ? 'REVERSED' : 'PENDING_REVIEW') as Status, days: [0.5, 0.5, 0.5, 0.5, 0.5, 0.25, 0.5, 0.5][i], date: d })),
  ...['2026-11-03', '2026-11-05', '2026-11-10', '2026-11-12', '2026-11-17', '2026-11-19', '2026-11-24'].map((d, i) => ({ emp: ['e1', 'e2', 'e7', 'e1', 'e6', 'e2', 'e3'][i], type: ['EARLY_DEPARTURE', 'EXCESS_BREAK', 'EARLY_DEPARTURE', 'EXCESS_BREAK', 'LATE_ARRIVAL', 'EARLY_DEPARTURE', 'LATE_ARRIVAL'][i], status: 'PENDING_REVIEW' as Status, days: [0.5, 0.25, 0.5, 0.25, 0.5, 0.5, 0.5][i], date: d })),
];

type Tally = { total: number; standing: number; cancelled: number; reversed: number; days: number };
const blank = (): Tally => ({ total: 0, standing: 0, cancelled: 0, reversed: 0, days: 0 });
function add(t: Tally, p: P) {
  t.total++;
  if (p.status === 'PENDING_REVIEW') { t.standing++; t.days += p.days; } else if (p.status === 'CANCELLED') t.cancelled++; else t.reversed++;
}
const bucket = (key: string, label: string, t: Tally): PenaltyAnalyticsBucket => ({ key, label, total: t.total, standing: t.standing, cancelled: t.cancelled, reversed: t.reversed, deductionDays: t.days });

/** The same maths as PenaltyAnalyticsService, over the sample data. */
function analyse(q: URLSearchParams, hr: boolean): PenaltyAnalytics {
  const from = q.get('from')!, to = q.get('to')!, rule = q.get('rule'), dept = q.get('department'), status = q.get('status');
  const inScope = Object.entries(PEOPLE).filter(([, p]) => hr || p.team).map(([id]) => id);
  const ids = inScope.filter((id) => !dept || PEOPLE[id].dept.toLowerCase() === dept.toLowerCase());
  const rows = DATA.filter((p) => ids.includes(p.emp) && p.date >= from && p.date <= to && (!rule || p.type === rule) && (!status || p.status === status));

  const all = blank();
  rows.forEach((p) => add(all, p));
  const group = (keyOf: (p: P) => string, labelOf: (k: string) => string) => {
    const m = new Map<string, Tally>();
    rows.forEach((p) => { const k = keyOf(p); if (!m.has(k)) m.set(k, blank()); add(m.get(k)!, p); });
    return [...m.entries()].map(([k, t]) => bucket(k, labelOf(k), t)).sort((a, b) => b.total - a.total || a.label.localeCompare(b.label));
  };

  const months: PenaltyAnalyticsBucket[] = [];
  const end = new Date(to + 'T00:00:00');
  for (let d = new Date(from.slice(0, 7) + '-01T00:00:00'); d <= end; d = new Date(d.getFullYear(), d.getMonth() + 1, 1)) {
    const key = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
    const t = blank();
    rows.filter((p) => p.date.startsWith(key)).forEach((p) => add(t, p));
    months.push(bucket(key, key, t));
  }

  const byEmployee: PenaltyAnalyticsEmployee[] = ids.map((id): PenaltyAnalyticsEmployee | null => {
    const mine = rows.filter((p) => p.emp === id);
    if (mine.length === 0) return null;
    const t = blank();
    mine.forEach((p) => add(t, p));
    const basis = mine.some((p) => p.status === 'PENDING_REVIEW') ? mine.filter((p) => p.status === 'PENDING_REVIEW') : mine;
    const counts = new Map<string, number>();
    basis.forEach((p) => counts.set(p.type, (counts.get(p.type) ?? 0) + 1));
    const topType = [...counts.entries()].sort((a, b) => b[1] - a[1] || b[0].localeCompare(a[0]))[0][0];
    return { employeeUserId: id, fullName: PEOPLE[id].name, employeeCode: PEOPLE[id].code, department: PEOPLE[id].dept, total: t.total, standing: t.standing, cancelled: t.cancelled, reversed: t.reversed, deductionDays: t.days, topRule: RULE_LABEL[topType] };
  }).filter((e): e is PenaltyAnalyticsEmployee => e !== null)
    .sort((a, b) => b.deductionDays - a.deductionDays || b.standing - a.standing || b.total - a.total || a.fullName.localeCompare(b.fullName));

  return {
    from, to, scope: hr ? 'ORGANISATION' : 'TEAM',
    summary: { total: all.total, standing: all.standing, cancelled: all.cancelled, reversed: all.reversed, deductionDays: all.days, employeesAffected: new Set(rows.map((p) => p.emp)).size },
    byRule: group((p) => p.type, (k) => RULE_LABEL[k] ?? k),
    byDepartment: group((p) => PEOPLE[p.emp].dept, (k) => k),
    byMonth: months, byEmployee,
    departments: [...new Set(inScope.map((id) => PEOPLE[id].dept))].sort(),
  };
}

export default function PenaltyAnalyticsPreview() {
  const [ready, setReady] = useState(false);
  const [hr, setHr] = useState(true);
  // The stand-in reads the current role through a ref-like closure; remount the view on change.
  useEffect(() => {
    const prev = window.fetch;
    window.fetch = ((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.split('?')[0].endsWith('/api/attendance/penalty-analytics')) {
        const q = new URL(url, window.location.origin).searchParams;
        return Promise.resolve(new Response(JSON.stringify(analyse(q, (window as unknown as { __pnHr?: boolean }).__pnHr !== false)), { status: 200, headers: { 'Content-Type': 'application/json' } }));
      }
      return prev(input, init);
    }) as typeof window.fetch;
    setReady(true);
    return () => { window.fetch = prev; };
  }, []);
  useEffect(() => { (window as unknown as { __pnHr?: boolean }).__pnHr = hr; }, [hr]);

  const seg = (on: boolean): React.CSSProperties => ({
    border: 'none', cursor: 'pointer', padding: '6px 14px', borderRadius: 7, fontSize: 12.5, fontWeight: 600,
    background: on ? 'var(--brand)' : 'transparent', color: on ? '#fff' : 'var(--txt-mut)',
  });
  const view = useMemo(() => ready && <PenaltyAnalyticsTab key={hr ? 'hr' : 'mgr'} token="mock" />, [ready, hr]);

  return (
    <PreviewChrome
      story="PN-06" icon={BarChart3} title="Penalty analytics"
      subtitle="See which rules, departments and people the penalties come from, month by month, to spot trends and fix root causes."
      stats={[{ label: 'Breakdowns', value: 'Rule, department, month, employee' }, { label: 'HR sees', value: 'Whole organisation' }, { label: 'Manager sees', value: 'Own team only' }, { label: 'Export', value: 'Excel' }]}
    >
      <PreviewSection n={1} title="The report" hint="Change the dates, rule, department or status. Cancelled and reversed penalties can be shown, but never add to days deducted.">
        <div style={{ display: 'inline-flex', alignItems: 'center', gap: 10, marginBottom: 12 }}>
          <span style={{ fontSize: 11.5, fontWeight: 700, letterSpacing: '.06em', textTransform: 'uppercase', color: 'var(--txt-dim)' }}>View as</span>
          <div style={{ display: 'inline-flex', gap: 2, padding: 3, borderRadius: 10, background: 'var(--panel)', border: '1px solid var(--line)' }}>
            <button style={seg(hr)} onClick={() => setHr(true)}>HR</button>
            <button style={seg(!hr)} onClick={() => setHr(false)}>Manager</button>
          </div>
        </div>
        {view}
      </PreviewSection>
    </PreviewChrome>
  );
}
