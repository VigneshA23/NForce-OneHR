/**
 * ============================================================================
 *  MOCK / VISUAL PREVIEW — NOT CONNECTED TO ANY BACKEND
 * ============================================================================
 *  Renders the REAL AttendancePage with `forcePreview` (the page's own uiPreview mock-data mode)
 *  inside a bare frame, so it needs no login, session or API. The authenticated Shell is
 *  intentionally not used. A role switcher on the same screen shows the page as each role sees it
 *  (title/subtitle, approver lists in the request tabs, and the team / organization roster).
 *
 *    /attendance-ui-preview                                  → Employee, Attendance Log tab
 *    /attendance-ui-preview?tab=calendar                     → Calendar tab
 *    /attendance-ui-preview?role=manager|hr|superadmin       → that role's view
 *  The "Theme" swatches use the app's real accent-color setting (lib/accentColor), so everything
 *  that follows --brand recolors exactly as it does in the real app; the choice is remembered.
 *
 *  Mock dates are relative to today; use the calendar's "<" arrow for the previous month.
 *  Approve / Reject / submit actions are not wired to anything in this preview.
 * ============================================================================
 */
import { useEffect } from 'react';
import { useSearchParams } from 'react-router-dom';
import AttendancePage from '../AttendancePage';
import { PreviewModeContext } from '../../lib/previewMode';
import type { Role } from '../../lib/nav.config';
import { useAccentColor, ACCENT_SWATCHES, type AccentColor } from '../../lib/accentColor';

const ROLES: { param: string; role: Role; label: string }[] = [
  { param: 'employee', role: 'Employee', label: 'Employee' },
  { param: 'manager', role: 'Manager', label: 'Manager' },
  { param: 'hr', role: 'HR Admin', label: 'HR Admin' },
  { param: 'superadmin', role: 'Super Admin', label: 'Super Admin' },
];

/** Mock only: the Attendance Policy popup reads the employee's real resolved policy, so give it a sample one here. */
const SAMPLE_POLICY = {
  id: 'p1', policyId: 'p1', version: 1, effectiveFrom: '2026-01-01', effectiveTo: null, createdBy: 'mock', createdAt: '2026-01-01T00:00:00',
  basicInfo: { deductionMethod: 'PAID_LEAVE', leavePriorityOrder: ['Casual Leave', 'Earned Leave'], bufferPeriodDays: 3, noticePeriodForcesLopEnabled: false },
  noAttendance: { enabled: true, deductionDays: 1, noShowEnabled: false, noShowThresholdHours: null, adjoiningHolidayEnabled: false, adjoiningHolidayCondition: null, adjoiningHolidayCalendarDayThreshold: null, adjoiningHolidayIgnoreHalfDayLeave: false, adjoiningWeekoffEnabled: false, adjoiningWeekoffCondition: null, adjoiningWeekoffCalendarDayThreshold: null, adjoiningWeekoffIgnoreHalfDayLeave: false },
  lateArrival: { enabled: true, basis: 'NUMBER_OF_INCIDENTS', gracePeriodMinutes: 10, exemptCount: 3, exemptPeriod: 'MONTH', deductionDays: 0.5, deductionPerShifts: 3, ignoreWhenEffectiveHoursMetEnabled: true, allowedHours: null, lateHoursTiers: [], combinedRuleBehavior: 'BOTH', penaliseWhenCausedByMissingLogEnabled: false },
  workHoursShortage: { enabled: true, deductionBasis: 'EFFECTIVE_HOURS', deductionPeriod: 'DAY', tiers: [{ thresholdPercent: 50, deductionDays: 1 }, { thresholdPercent: 75, deductionDays: 0.5 }], applyPenaltyForShortageEnabled: true, applyPenaltyForLateArrivalEnabled: false, excludeHoursOutsideShiftEnabled: false, penalizeShortageCausedByMissingLogsEnabled: false },
  missingLogs: { enabled: true, exemptDays: 2, exemptPeriod: 'MONTH', deductionMode: 'PER_SHIFT', deductionDays: 0.5, deductionPerShifts: 1, ignoreRuleEnabled: false, ignoreRuleThresholdPercent: null },
};

export default function AttendancePreviewShell() {
  const [params, setParams] = useSearchParams();
  useEffect(() => {
    const prevFetch = window.fetch;
    window.fetch = ((input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input).split('?')[0].endsWith('/api/penalization-policy/my-current')) {
        return Promise.resolve(new Response(JSON.stringify(SAMPLE_POLICY), { status: 200, headers: { 'Content-Type': 'application/json' } }));
      }
      return prevFetch(input, init);
    }) as typeof window.fetch;
    return () => { window.fetch = prevFetch; };
  }, []);
  const { accent, setAccent } = useAccentColor();
  const active = ROLES.find((r) => r.param === params.get('role')) ?? ROLES[0];

  function pick(param: string) {
    const next = new URLSearchParams(params);
    next.set('role', param);
    setParams(next, { replace: true });
  }

  return (
    <div style={{ minHeight: '100vh', background: 'var(--shell)', color: 'var(--txt)', fontFamily: 'Inter, sans-serif' }}>
      <main style={{ maxWidth: 1480, margin: '0 auto', padding: '16px 28px 48px' }}>
        <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 12, marginBottom: 18 }}>
        <div role="tablist" aria-label="Preview role" style={{ display: 'inline-flex', gap: 4, padding: 4, borderRadius: 10, background: 'var(--panel)', border: '1px solid var(--line)' }}>
          <span style={{ alignSelf: 'center', padding: '0 10px', fontSize: 10.5, fontWeight: 700, letterSpacing: '.06em', textTransform: 'uppercase', color: 'var(--txt-dim)' }}>View as</span>
          {ROLES.map((r) => {
            const on = r.param === active.param;
            return (
              <button
                key={r.param}
                role="tab"
                aria-selected={on}
                onClick={() => pick(r.param)}
                style={{ border: 'none', cursor: 'pointer', padding: '7px 14px', borderRadius: 7, fontSize: 12.5, fontWeight: 600, background: on ? 'var(--brand)' : 'transparent', color: on ? '#fff' : 'var(--txt-mut)' }}
              >
                {r.label}
              </button>
            );
          })}
        </div>
        <div role="radiogroup" aria-label="Theme color" style={{ display: 'inline-flex', alignItems: 'center', gap: 8, padding: '4px 12px', borderRadius: 10, background: 'var(--panel)', border: '1px solid var(--line)', height: 40, boxSizing: 'border-box' }}>
          <span style={{ fontSize: 10.5, fontWeight: 700, letterSpacing: '.06em', textTransform: 'uppercase', color: 'var(--txt-dim)', marginRight: 2 }}>Theme</span>
          {(Object.keys(ACCENT_SWATCHES) as AccentColor[]).map((key) => {
            const sw = ACCENT_SWATCHES[key];
            const on = key === accent;
            return (
              <button
                key={key}
                role="radio"
                aria-checked={on}
                aria-label={sw.label}
                title={sw.label}
                onClick={() => setAccent(key)}
                style={{
                  width: 22, height: 22, borderRadius: '50%', cursor: 'pointer', padding: 0,
                  background: `linear-gradient(135deg, ${sw.brandBright}, ${sw.brand})`,
                  border: on ? '2px solid var(--panel)' : '2px solid transparent',
                  boxShadow: on ? `0 0 0 2px ${sw.brandBright}` : 'none',
                }}
              />
            );
          })}
        </div>
        </div>
        {/* key: remount on role change so every section reloads its role-specific data */}
        <PreviewModeContext.Provider value>
          <AttendancePage key={active.param} forcePreview previewRole={active.role} />
        </PreviewModeContext.Provider>
      </main>
    </div>
  );
}
