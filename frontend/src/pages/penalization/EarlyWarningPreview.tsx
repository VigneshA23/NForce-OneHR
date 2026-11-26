/**
 * ============================================================================
 *  MOCK / VISUAL PREVIEW - NOT CONNECTED TO ANY BACKEND
 * ============================================================================
 *  PN-03: (1) the REAL Penalization Policy configuration screen (Basic Information tab) with the
 *  early-warning settings, fed a canned policy; (2) what the employee receives - the in-app
 *  notification and the email - for a late-mark warning and a work-hours-shortage warning.
 *    /early-warning-preview
 *  Theme colour and Light/Dark follow the app's real settings (see PreviewChrome).
 */
import { useEffect, useState } from 'react';
import { Bell, BellRing, Mail, Moon, RefreshCw, Timer, Hourglass } from 'lucide-react';
import PenalizationPolicySection from './PenalizationPolicySection';
import { PreviewChrome, PreviewSection } from './PreviewChrome';

const POLICY = {
  id: 'v1', policyId: 'p1', version: 3, effectiveFrom: '2026-11-01T00:00:00', effectiveTo: null, createdBy: 'mock', createdAt: '2026-10-20T00:00:00', updatedBy: null, updatedAt: '2026-10-20T00:00:00',
  basicInfo: {
    deductionMethod: 'PAID_LEAVE', leavePriorityOrder: ['CASUAL'], bufferPeriodDays: 3, noticePeriodForcesLopEnabled: false,
    earlyWarning: { enabled: true, lateArrivalEnabled: true, missingLogsEnabled: true, shortageEnabled: true, exemptRemaining: 1, shortageMarginPercent: 10 },
  },
  noAttendance: { enabled: true, deductionDays: 1, noShowEnabled: false, noShowThresholdHours: null, adjoiningHolidayEnabled: false, adjoiningHolidayCondition: null, adjoiningHolidayCalendarDayThreshold: 1, adjoiningHolidayIgnoreHalfDayLeave: true, adjoiningWeekoffEnabled: false, adjoiningWeekoffCondition: null, adjoiningWeekoffCalendarDayThreshold: 1, adjoiningWeekoffIgnoreHalfDayLeave: true },
  lateArrival: { enabled: true, basis: 'NUMBER_OF_INCIDENTS', gracePeriodMinutes: 10, exemptCount: 3, exemptPeriod: 'MONTH', deductionDays: 0.5, deductionPerShifts: 1, ignoreWhenEffectiveHoursMetEnabled: false, allowedHours: null, lateHoursTiers: [], combinedRuleBehavior: 'TOTAL_HOURS_ONLY', penaliseWhenCausedByMissingLogEnabled: false },
  workHoursShortage: { enabled: true, deductionBasis: 'EFFECTIVE_HOURS', deductionPeriod: 'WEEK', tiers: [{ thresholdPercent: 80, deductionDays: 0.5 }, { thresholdPercent: 50, deductionDays: 1 }], applyPenaltyForShortageEnabled: true, applyPenaltyForLateArrivalEnabled: false, excludeHoursOutsideShiftEnabled: false, penalizeShortageCausedByMissingLogsEnabled: false },
  missingLogs: { enabled: true, exemptDays: 2, exemptPeriod: 'MONTH', deductionMode: 'PER_SHIFT', deductionDays: 0.5, deductionPerShifts: 1, ignoreRuleEnabled: false, ignoreRuleThresholdPercent: null },
};

const STEPS = [
  { icon: Moon, title: 'Nightly check', text: 'After the daily attendance run, each employee on a policy with warnings switched on is checked.' },
  { icon: BellRing, title: 'One nudge per threshold', text: 'The employee gets an in-app notification and an email, once for each threshold that is reached.' },
  { icon: RefreshCw, title: 'Resets each period', text: 'A new month or week starts a fresh count, so the same threshold can warn again next period.' },
];

const card: React.CSSProperties = { background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 16, boxShadow: '0 1px 2px rgba(0,0,0,.05), 0 12px 28px -18px rgba(0,0,0,.28)' };

function Pips({ used, total }: { used: number; total: number }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 10 }}>
      {Array.from({ length: total }).map((_, i) => (
        <span key={i} style={{ width: 26, height: 26, borderRadius: 8, display: 'inline-flex', alignItems: 'center', justifyContent: 'center', fontSize: 11, fontWeight: 800,
          color: i < used ? '#fff' : 'var(--txt-dim)', background: i < used ? 'var(--warn)' : 'var(--raised)', border: '1px solid ' + (i < used ? 'var(--warn)' : 'var(--line2)') }}>{i + 1}</span>
      ))}
      <span style={{ fontSize: 12, color: 'var(--txt-mut)' }}>{used} of {total} exempt late marks used</span>
    </div>
  );
}

function Meter({ percent, tier }: { percent: number; tier: number }) {
  return (
    <div style={{ marginTop: 12 }}>
      <div style={{ position: 'relative', height: 10, borderRadius: 6, background: 'var(--raised)', border: '1px solid var(--line2)' }}>
        <div style={{ width: percent + '%', height: '100%', borderRadius: 6, background: 'linear-gradient(90deg, var(--warn), var(--brand))' }} />
        <div title={`Shortage tier at ${tier}%`} style={{ position: 'absolute', left: tier + '%', top: -4, bottom: -4, width: 2, background: 'var(--risk)' }} />
      </div>
      <div style={{ position: 'relative', height: 16, marginTop: 4, fontSize: 10.5, color: 'var(--txt-dim)' }}>
        <span style={{ position: 'absolute', left: 0 }}>0%</span>
        <span style={{ position: 'absolute', left: tier + '%', transform: 'translateX(-50%)', color: 'var(--risk)', fontWeight: 700 }}>tier {tier}%</span>
        <span style={{ position: 'absolute', right: 0 }}>100%</span>
      </div>
      <div style={{ fontSize: 12, color: 'var(--txt-mut)' }}>You are at <b style={{ color: 'var(--txt)' }}>{percent}%</b> of required hours this week.</div>
    </div>
  );
}

interface Scenario { key: string; rule: string; icon: typeof Timer; message: string; rows: [string, string][]; visual: React.ReactNode; time: string }
const SCENARIOS: Scenario[] = [
  {
    key: 'late', rule: 'Late arrival', icon: Timer, time: 'Just now',
    message: 'You have used 2 of 3 exempt late marks this month. Your next late arrival is still exempt; after that, each late arrival is penalised (0.5 day deduction). Arrive within your grace time to avoid it.',
    rows: [['Late marks used', '2 of 3'], ['Exempt left', '1']],
    visual: <Pips used={2} total={3} />,
  },
  {
    key: 'shortage', rule: 'Work-hours shortage', icon: Hourglass, time: 'Just now',
    message: 'You have worked 24h 00m of the 27h 00m expected so far this week (89%). Finishing the week below 80% of required hours leads to a 0.5-day deduction. You still need at least 12h 00m more before the week ends.',
    rows: [['Hours worked', '24h 00m of 27h 00m'], ['Hours still needed', '12h 00m']],
    visual: <Meter percent={89} tier={80} />,
  },
];

function InAppCard({ s }: { s: Scenario }) {
  return (
    <div style={{ ...card, padding: 16 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 10 }}>
        <Bell size={14} style={{ color: 'var(--brand)' }} />
        <span style={{ fontSize: 10.5, fontWeight: 800, letterSpacing: '.07em', textTransform: 'uppercase', color: 'var(--txt-dim)' }}>In-app notification</span>
        <span style={{ marginLeft: 'auto', fontSize: 11, color: 'var(--txt-dim)' }}>{s.time}</span>
      </div>
      <div style={{ display: 'flex', gap: 12 }}>
        <div style={{ width: 38, height: 38, borderRadius: 11, flexShrink: 0, display: 'flex', alignItems: 'center', justifyContent: 'center', color: 'var(--warn)', background: 'color-mix(in srgb, var(--warn) 14%, var(--panel))' }}><s.icon size={19} /></div>
        <div style={{ minWidth: 0 }}>
          <div style={{ fontSize: 14, fontWeight: 700, display: 'flex', alignItems: 'center', gap: 8 }}>
            Heads-up: attendance penalty ahead <span style={{ width: 7, height: 7, borderRadius: '50%', background: 'var(--brand)' }} />
          </div>
          <div style={{ fontSize: 12.5, color: 'var(--txt-mut)', lineHeight: 1.55, marginTop: 4 }}>{s.message}</div>
          {s.visual}
        </div>
      </div>
    </div>
  );
}

function EmailCard({ s }: { s: Scenario }) {
  return (
    <div style={{ borderRadius: 16, overflow: 'hidden', background: '#fff', color: '#0F172A', border: '1px solid #CBD5E1', boxShadow: '0 12px 28px -18px rgba(0,0,0,.35)' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '10px 16px', background: 'linear-gradient(120deg, var(--brand), var(--brand-bright, var(--brand)))', color: '#fff' }}>
        <Mail size={14} /><span style={{ fontSize: 10.5, fontWeight: 800, letterSpacing: '.08em', textTransform: 'uppercase' }}>Email</span>
        <span style={{ marginLeft: 'auto', fontSize: 12, fontWeight: 700 }}>NForce OneHR</span>
      </div>
      <div style={{ padding: '18px 20px 16px' }}>
        <div style={{ fontFamily: '"Space Grotesk", sans-serif', fontSize: 19, fontWeight: 700, marginBottom: 8 }}>Heads-up: attendance penalty ahead</div>
        <p style={{ fontSize: 13, lineHeight: 1.65, color: '#6B7280', margin: '0 0 14px' }}>Hi Rahul, {s.message.charAt(0).toLowerCase() + s.message.slice(1)}</p>
        <div style={{ background: '#F8FAFC', border: '1px solid #E2E8F0', borderRadius: 12, padding: '12px 14px', marginBottom: 12 }}>
          {s.rows.map(([k, v]) => <p key={k} style={{ margin: '0 0 6px', fontSize: 13, color: '#6B7280' }}>{k}: <b style={{ color: '#111827' }}>{v}</b></p>)}
        </div>
        <p style={{ fontSize: 12, color: '#94A3B8', margin: 0 }}>This is an early warning only. No deduction has been made. You can review your record under Attendance, My Penalties.</p>
      </div>
    </div>
  );
}

export default function EarlyWarningPreview() {
  const [ready, setReady] = useState(false);
  useEffect(() => {
    const prev = window.fetch;
    window.fetch = ((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input).split('?')[0];
      if (url.endsWith('/api/penalization-policy/current')) {
        return Promise.resolve(new Response(JSON.stringify(POLICY), { status: 200, headers: { 'Content-Type': 'application/json' } }));
      }
      // Mock only: Version History and Save must never reach a backend.
      const json = (body: unknown) => Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }));
      if (/\/api\/penalization-policy\/versions$/.test(url)) {
        return json([
          { id: 'v3', version: 3, effectiveFrom: '2026-11-01', effectiveTo: null, createdAt: '2026-10-28T10:00:00' },
          { id: 'v2', version: 2, effectiveFrom: '2026-06-01', effectiveTo: '2026-10-31', createdAt: '2026-06-01T09:00:00' },
          { id: 'v1', version: 1, effectiveFrom: '2026-01-01', effectiveTo: '2026-05-31', createdAt: '2026-01-01T09:00:00' },
        ]);
      }
      if (/\/api\/penalization-policy\/versions\/[^/]+$/.test(url)) return json(POLICY);
      if (/\/api\/penalization-policy(\/[^/]+)?$/.test(url) && (init?.method ?? 'GET').toUpperCase() !== 'GET') return json(POLICY);
      return prev(input, init);
    }) as typeof window.fetch;
    setReady(true);
    return () => { window.fetch = prev; };
  }, []);

  // Mock only: land on the Basic Information tab, where the new settings live.
  useEffect(() => {
    if (!ready) return;
    const t = window.setInterval(() => {
      const tab = [...document.querySelectorAll<HTMLElement>('button, [role=tab]')].find((b) => /^Basic Information/.test(b.innerText.trim()));
      if (tab) { tab.click(); window.clearInterval(t); }
    }, 250);
    const stop = window.setTimeout(() => window.clearInterval(t), 5000);
    return () => { window.clearInterval(t); window.clearTimeout(stop); };
  }, [ready]);

  return (
    <PreviewChrome
      story="PN-03" icon={BellRing} title="Early-warning notifications"
      subtitle="Tell employees they are one step from a penalty, before any deduction happens. HR switches it on per policy and chooses the thresholds."
      stats={[{ label: 'Default', value: 'Off' }, { label: 'Set per', value: 'Penalization policy' }, { label: 'Sent', value: 'In-app and email' }, { label: 'Repeats', value: 'Never within a period' }]}
    >
      <PreviewSection n={1} title="How it works" hint="Runs on its own after the nightly attendance check.">
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(260px, 1fr))', gap: 14 }}>
          {STEPS.map((st, i) => (
            <div key={st.title} style={{ ...card, padding: '16px 18px', position: 'relative' }}>
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

      <PreviewSection n={2} title="Policy settings" hint="Open the Basic Information tab. Switch it on or off, pick which rules warn, and set the thresholds.">
        <div style={{ ...card, padding: 18, borderTop: '3px solid var(--brand)' }}>
          {ready && <PenalizationPolicySection token="mock" />}
        </div>
      </PreviewSection>

      <PreviewSection n={3} title="What the employee receives" hint="One in-app notification and one email, each time a threshold is reached.">
        <div style={{ display: 'flex', flexDirection: 'column', gap: 22 }}>
          {SCENARIOS.map((s) => (
            <div key={s.key} data-testid={'example-' + s.key}>
              <div style={{ display: 'inline-flex', alignItems: 'center', gap: 7, marginBottom: 10, padding: '4px 11px', borderRadius: 999, fontSize: 12, fontWeight: 700, color: 'var(--warn)', background: 'color-mix(in srgb, var(--warn) 12%, var(--panel))', border: '1px solid color-mix(in srgb, var(--warn) 28%, var(--line))' }}>
                <s.icon size={13} /> {s.rule}
              </div>
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(340px, 1fr))', gap: 16, alignItems: 'start' }}>
                <InAppCard s={s} />
                <EmailCard s={s} />
              </div>
            </div>
          ))}
        </div>
      </PreviewSection>
    </PreviewChrome>
  );
}
