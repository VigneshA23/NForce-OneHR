/**
 * ============================================================================
 *  MOCK / VISUAL PREVIEW - NOT CONNECTED TO ANY BACKEND
 * ============================================================================
 *  PN-05: (1) how the two new rules decide; (2) the REAL Penalization Policy configuration screen
 *  with the Early Going and Excess Break tabs, fed a canned policy; (3) a worked month for each
 *  rule showing which days are free, exempt or deducted.
 *    /early-going-break-preview
 *  Theme colour and Light/Dark follow the app's real settings (see PreviewChrome).
 */
import { useEffect, useState } from 'react';
import { CalendarCheck, Coffee, DoorOpen, Gauge, ListChecks, ShieldCheck } from 'lucide-react';
import PenalizationPolicySection from './PenalizationPolicySection';
import { PreviewChrome, PreviewSection } from './PreviewChrome';
import '../attendance/myPenalties.css';

const POLICY = {
  id: 'v1', policyId: 'p1', version: 4, effectiveFrom: '2026-12-01T00:00:00', effectiveTo: null, createdBy: 'mock', createdAt: '2026-11-20T00:00:00', updatedBy: null, updatedAt: '2026-11-20T00:00:00',
  basicInfo: { deductionMethod: 'PAID_LEAVE', leavePriorityOrder: ['CASUAL'], bufferPeriodDays: 3, noticePeriodForcesLopEnabled: false, earlyWarning: { enabled: false, lateArrivalEnabled: true, missingLogsEnabled: true, shortageEnabled: true, exemptRemaining: 1, shortageMarginPercent: 10 } },
  noAttendance: { enabled: true, deductionDays: 1, noShowEnabled: false, noShowThresholdHours: null, adjoiningHolidayEnabled: false, adjoiningHolidayCondition: null, adjoiningHolidayCalendarDayThreshold: 1, adjoiningHolidayIgnoreHalfDayLeave: true, adjoiningWeekoffEnabled: false, adjoiningWeekoffCondition: null, adjoiningWeekoffCalendarDayThreshold: 1, adjoiningWeekoffIgnoreHalfDayLeave: true },
  lateArrival: { enabled: true, basis: 'NUMBER_OF_INCIDENTS', gracePeriodMinutes: 10, exemptCount: 3, exemptPeriod: 'MONTH', deductionDays: 0.5, deductionPerShifts: 1, ignoreWhenEffectiveHoursMetEnabled: false, allowedHours: null, lateHoursTiers: [], combinedRuleBehavior: 'TOTAL_HOURS_ONLY', penaliseWhenCausedByMissingLogEnabled: false },
  workHoursShortage: { enabled: true, deductionBasis: 'EFFECTIVE_HOURS', deductionPeriod: 'WEEK', tiers: [{ thresholdPercent: 80, deductionDays: 0.5 }], applyPenaltyForShortageEnabled: true, applyPenaltyForLateArrivalEnabled: false, excludeHoursOutsideShiftEnabled: false, penalizeShortageCausedByMissingLogsEnabled: false },
  missingLogs: { enabled: true, exemptDays: 2, exemptPeriod: 'MONTH', deductionMode: 'PER_SHIFT', deductionDays: 0.5, deductionPerShifts: 1, ignoreRuleEnabled: false, ignoreRuleThresholdPercent: null },
  earlyGoing: { enabled: true, gracePeriodMinutes: 15, exemptCount: 2, exemptPeriod: 'MONTH', deductionDays: 0.5, deductionPerShifts: 1 },
  excessBreak: { enabled: true, maxBreakMinutes: 60, exemptCount: 1, exemptPeriod: 'MONTH', deductionDays: 0.25 },
};

const card: React.CSSProperties = { background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 16, boxShadow: '0 1px 2px rgba(0,0,0,.05), 0 12px 28px -18px rgba(0,0,0,.28)' };

const STEPS = [
  { icon: Gauge, title: 'Measured nightly', text: 'The night job reads each finished day: minutes left before the shift end, and total break minutes.' },
  { icon: ListChecks, title: 'Grace, then exempt count', text: 'Within the grace or the limit is free. The first few occurrences per week or month are exempt. After that the deduction applies.' },
  { icon: ShieldCheck, title: 'Approved requests exempt', text: 'An approved Partial Day (Leaving early) request exempts that day from Early Going, even if the penalty was already raised.' },
];

type Outcome = 'FREE' | 'EXEMPT' | 'DEDUCT' | 'PARTIAL';
const OUTCOME: Record<Outcome, { label: string; color: string }> = {
  FREE: { label: 'No penalty', color: 'var(--ok)' },
  EXEMPT: { label: 'Exempt', color: 'var(--info)' },
  DEDUCT: { label: 'Deducted', color: 'var(--risk)' },
  PARTIAL: { label: 'Exempt: approved request', color: 'var(--warn)' },
};

function Chip({ outcome }: { outcome: Outcome }) {
  const o = OUTCOME[outcome];
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', padding: '2px 9px', borderRadius: 999, fontSize: 11.5, fontWeight: 700, whiteSpace: 'nowrap', color: o.color, background: `color-mix(in srgb, ${o.color} 12%, var(--panel))`, border: `1px solid color-mix(in srgb, ${o.color} 30%, var(--line))` }}>{o.label}</span>
  );
}

const EARLY_ROWS: { day: string; left: string; early: string; count: string; outcome: Outcome; note: string }[] = [
  { day: 'Mon 2 Dec', left: '17:50', early: '10 min', count: '-', outcome: 'FREE', note: 'Within the 15 min grace' },
  { day: 'Tue 3 Dec', left: '17:15', early: '45 min', count: '1 of 2 exempt', outcome: 'EXEMPT', note: 'First occurrence, exempt' },
  { day: 'Wed 4 Dec', left: '16:30', early: '90 min', count: '2 of 2 exempt', outcome: 'EXEMPT', note: 'Second occurrence, exempt' },
  { day: 'Thu 5 Dec', left: '16:00', early: '120 min', count: '-', outcome: 'PARTIAL', note: 'Approved Partial Day (Leaving early)' },
  { day: 'Fri 6 Dec', left: '17:00', early: '60 min', count: '3rd, past exempt', outcome: 'DEDUCT', note: 'Deduct 0.5 day' },
];

const BREAK_ROWS: { day: string; total: string; over: string; count: string; outcome: Outcome; note: string }[] = [
  { day: 'Mon 2 Dec', total: '55 min', over: '-', count: '-', outcome: 'FREE', note: 'Within the 60 min limit' },
  { day: 'Tue 3 Dec', total: '75 min', over: '15 min over', count: '1 of 1 exempt', outcome: 'EXEMPT', note: 'First day over, exempt' },
  { day: 'Wed 4 Dec', total: '40 min', over: '-', count: '-', outcome: 'FREE', note: 'Within the limit' },
  { day: 'Thu 5 Dec', total: '95 min', over: '35 min over', count: '2nd, past exempt', outcome: 'DEDUCT', note: 'Deduct 0.25 day' },
];

function ExampleTable({ title, icon: Icon, rule, heads, rows, testId }: {
  title: string; icon: typeof DoorOpen; rule: string; heads: string[]; rows: string[][]; testId: string;
}) {
  return (
    <div data-testid={testId} className="nf-mp" style={{ ...card, overflow: 'hidden' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '14px 18px', borderBottom: '1px solid var(--line)' }}>
        <span style={{ width: 32, height: 32, borderRadius: 10, display: 'inline-flex', alignItems: 'center', justifyContent: 'center', color: 'var(--brand)', background: 'color-mix(in srgb, var(--brand) 12%, var(--panel))' }}><Icon size={16} /></span>
        <div>
          <div style={{ fontSize: 14, fontWeight: 700 }}>{title}</div>
          <div style={{ fontSize: 12, color: 'var(--txt-mut)' }}>{rule}</div>
        </div>
      </div>
      <div className="nf-req-wrap" style={{ padding: '4px 10px 10px' }}>
        <table className="nf-req-table" style={{ width: '100%', borderCollapse: 'collapse' }}>
          <thead>
            <tr>{heads.map((h) => (
              <th key={h} style={{ padding: '9px 12px', textAlign: 'left', fontSize: 10.5, fontWeight: 700, letterSpacing: '.06em', textTransform: 'uppercase', color: 'var(--txt-dim)', background: 'transparent', borderBottom: '1px solid var(--line)', whiteSpace: 'nowrap' }}>{h}</th>
            ))}</tr>
          </thead>
          <tbody>
            {rows.map((r, i) => {
              const outcome = r[r.length - 2] as Outcome;
              return (
                <tr key={i} data-testid="example-row" style={{ ['--rc' as string]: OUTCOME[outcome].color }}>
                  {r.slice(0, -2).map((cell, c) => (
                    <td key={c} data-label={heads[c]} style={{ padding: '11px 12px', fontSize: 12.5, color: c === 0 ? 'var(--txt)' : 'var(--txt-mut)', fontWeight: c === 0 ? 600 : 400, borderBottom: '1px solid var(--line)', verticalAlign: 'middle' }}>{cell}</td>
                  ))}
                  <td data-label={heads[heads.length - 2]} style={{ padding: '11px 12px', borderBottom: '1px solid var(--line)' }}><Chip outcome={outcome} /></td>
                  <td data-label={heads[heads.length - 1]} style={{ padding: '11px 12px', fontSize: 12.5, color: 'var(--txt-mut)', borderBottom: '1px solid var(--line)' }}>{r[r.length - 1]}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </div>
  );
}

export default function EarlyGoingBreakPreview() {
  const [ready, setReady] = useState(false);
  const [tab, setTab] = useState<'Early Going' | 'Excess Break'>('Early Going');

  useEffect(() => {
    const prev = window.fetch;
    window.fetch = ((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input).split('?')[0];
      const json = (body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }));
      if (url.endsWith('/api/penalization-policy/current')) return json(POLICY);
      if (/\/api\/penalization-policy\/versions$/.test(url)) {
        return json([
          { id: 'v4', version: 4, effectiveFrom: '2026-12-01', effectiveTo: null, createdAt: '2026-11-20T10:00:00' },
          { id: 'v3', version: 3, effectiveFrom: '2026-06-01', effectiveTo: '2026-11-30', createdAt: '2026-06-01T09:00:00' },
        ]);
      }
      if (/\/api\/penalization-policy\/versions\/[^/]+$/.test(url)) return json(POLICY);
      // Mock only: Save must never reach a backend.
      if (/\/api\/penalization-policy(\/[^/]+)?$/.test(url) && (init?.method ?? 'GET').toUpperCase() !== 'GET') return json(POLICY);
      return prev(input, init);
    }) as typeof window.fetch;
    setReady(true);
    return () => { window.fetch = prev; };
  }, []);

  // Mock only: open the chosen rule's tab inside the real configuration screen.
  useEffect(() => {
    if (!ready) return;
    const t = window.setInterval(() => {
      // The form's own side tab carries a status badge on a second line; my toggle above does not.
      const btn = [...document.querySelectorAll<HTMLElement>('button')].find((b) => new RegExp('^' + tab + '\\s*\\n').test(b.innerText.trim()));
      if (btn) { btn.click(); window.clearInterval(t); }
    }, 250);
    const stop = window.setTimeout(() => window.clearInterval(t), 5000);
    return () => { window.clearInterval(t); window.clearTimeout(stop); };
  }, [ready, tab]);

  const seg = (on: boolean): React.CSSProperties => ({
    border: 'none', cursor: 'pointer', padding: '6px 14px', borderRadius: 7, fontSize: 12.5, fontWeight: 600,
    background: on ? 'var(--brand)' : 'transparent', color: on ? '#fff' : 'var(--txt-mut)',
  });

  return (
    <PreviewChrome
      story="PN-05" icon={DoorOpen} title="Early going and excess break rules"
      subtitle="Two explicit rules instead of relying on work-hours shortage: one for leaving before the shift ends, one for taking too much break time in a day."
      stats={[{ label: 'Early going', value: 'Grace, exempt count, deduction' }, { label: 'Excess break', value: 'Daily limit, exempt count, deduction' }, { label: 'Versioned', value: 'With the policy' }, { label: 'Evaluated', value: 'By the nightly job' }]}
    >
      <PreviewSection n={1} title="How it works" hint="Both rules follow the same pattern as Late Arrival.">
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(260px, 1fr))', gap: 14 }}>
          {STEPS.map((st, i) => (
            <div key={st.title} style={{ ...card, padding: '16px 18px' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 8 }}>
                <span style={{ width: 34, height: 34, borderRadius: 10, display: 'inline-flex', alignItems: 'center', justifyContent: 'center', color: 'var(--brand)', background: 'color-mix(in srgb, var(--brand) 12%, var(--panel))' }}><st.icon size={17} /></span>
                <b style={{ fontSize: 14 }}>{st.title}</b>
                <span style={{ marginLeft: 'auto', fontSize: 11, fontWeight: 800, color: 'var(--txt-dim)' }}>0{i + 1}</span>
              </div>
              <p style={{ margin: 0, fontSize: 12.5, lineHeight: 1.55, color: 'var(--txt-mut)' }}>{st.text}</p>
            </div>
          ))}
        </div>
      </PreviewSection>

      <PreviewSection n={2} title="Policy settings" hint="The real configuration screen. Switch between the two new rules, edit the values, and use Version History.">
        <div style={{ display: 'inline-flex', gap: 2, padding: 3, borderRadius: 10, background: 'var(--panel)', border: '1px solid var(--line)', marginBottom: 12 }}>
          <button style={seg(tab === 'Early Going')} onClick={() => setTab('Early Going')}>Early Going</button>
          <button style={seg(tab === 'Excess Break')} onClick={() => setTab('Excess Break')}>Excess Break</button>
        </div>
        <div style={{ ...card, padding: 18, borderTop: '3px solid var(--brand)' }}>
          {ready && <PenalizationPolicySection token="mock" />}
        </div>
      </PreviewSection>

      <PreviewSection n={3} title="A worked week" hint="Using the sample settings above, this is how each day is decided.">
        <div style={{ display: 'flex', flexDirection: 'column', gap: 18 }}>
          <ExampleTable
            testId="example-early-going" title="Early going" icon={CalendarCheck}
            rule="Grace 15 min, 2 exempt per month, then 0.5 day for each occurrence. Shift ends 18:00."
            heads={['Day', 'Left at', 'Early by', 'Count', 'Outcome', 'Why']}
            rows={EARLY_ROWS.map((r) => [r.day, r.left, r.early, r.count, r.outcome, r.note])}
          />
          <ExampleTable
            testId="example-excess-break" title="Excess break" icon={Coffee}
            rule="Limit 60 min a day, 1 exempt day per month, then 0.25 day for each day over."
            heads={['Day', 'Break total', 'Over by', 'Count', 'Outcome', 'Why']}
            rows={BREAK_ROWS.map((r) => [r.day, r.total, r.over, r.count, r.outcome, r.note])}
          />
        </div>
      </PreviewSection>
    </PreviewChrome>
  );
}
