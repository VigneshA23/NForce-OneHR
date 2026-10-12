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
import { useSearchParams } from 'react-router-dom';
import AttendancePage from '../AttendancePage';
import type { Role } from '../../lib/nav.config';
import { useAccentColor, ACCENT_SWATCHES, type AccentColor } from '../../lib/accentColor';

const ROLES: { param: string; role: Role; label: string }[] = [
  { param: 'employee', role: 'Employee', label: 'Employee' },
  { param: 'manager', role: 'Manager', label: 'Manager' },
  { param: 'hr', role: 'HR Admin', label: 'HR Admin' },
  { param: 'superadmin', role: 'Super Admin', label: 'Super Admin' },
];

export default function AttendancePreviewShell() {
  const [params, setParams] = useSearchParams();
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
        <AttendancePage key={active.param} forcePreview previewRole={active.role} />
      </main>
    </div>
  );
}
