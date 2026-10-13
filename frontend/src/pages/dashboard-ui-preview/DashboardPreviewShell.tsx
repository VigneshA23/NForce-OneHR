/**
 * ============================================================================
 *  MOCK / VISUAL PREVIEW — NOT CONNECTED TO ANY BACKEND
 * ============================================================================
 *  Employee: the WHOLE real Home dashboard (EmployeeDashboardView) with canned data, via
 *  PreviewModeContext — no login, session or API. Manager / HR Admin / Super Admin: their tile rows
 *  (Quick Actions + stat tiles) only, built from the real DashTile/QuickActions with invented numbers;
 *  their charts and tables need more live data than is mocked.
 *
 *    /dashboard-ui-preview                        → Employee
 *    /dashboard-ui-preview?role=manager|hr|superadmin
 *  The "Theme" swatches use the app's real accent-color setting, same as the Attendance preview.
 * ============================================================================
 */
import { useSearchParams } from 'react-router-dom';
import { Users, UserCheck, Clock, Calendar, Package, ShieldCheck } from 'lucide-react';
import DashTile from '../../components/DashTile';
import { useAccentColor, ACCENT_SWATCHES, type AccentColor } from '../../lib/accentColor';
import { PreviewModeContext } from '../../lib/previewMode';
import {
  QuickActions, EmployeeDashboardView,
  MANAGER_QUICK_ACTIONS, HR_ADMIN_QUICK_ACTIONS, SUPER_ADMIN_QUICK_ACTIONS,
} from '../DashboardPage';

type RoleKey = 'employee' | 'manager' | 'hr' | 'superadmin';
const ROLES: { key: RoleKey; label: string; subtitle: string }[] = [
  { key: 'employee', label: 'Employee', subtitle: 'Employee Dashboard' },
  { key: 'manager', label: 'Manager', subtitle: 'Manager Dashboard' },
  { key: 'hr', label: 'HR Admin', subtitle: 'HR Dashboard' },
  { key: 'superadmin', label: 'Super Admin', subtitle: 'Super Admin Dashboard' },
];

export default function DashboardPreviewShell() {
  const [params, setParams] = useSearchParams();
  const { accent, setAccent } = useAccentColor();
  const active = ROLES.find((r) => r.key === params.get('role')) ?? ROLES[0];

  function pick(key: RoleKey) {
    const next = new URLSearchParams(params);
    next.set('role', key);
    setParams(next, { replace: true });
  }

  const noop = () => {};
  return (
    <div style={{ minHeight: '100vh', background: 'var(--shell)', color: 'var(--txt)', fontFamily: 'Inter, sans-serif' }}>
      <main style={{ maxWidth: 1480, margin: '0 auto', padding: '16px 28px 48px', display: 'flex', flexDirection: 'column', gap: 20 }}>
        <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 12 }}>
          <div role="tablist" aria-label="Preview role" style={{ display: 'inline-flex', gap: 4, padding: 4, borderRadius: 10, background: 'var(--panel)', border: '1px solid var(--line)' }}>
            <span style={{ alignSelf: 'center', padding: '0 10px', fontSize: 10.5, fontWeight: 700, letterSpacing: '.06em', textTransform: 'uppercase', color: 'var(--txt-dim)' }}>View as</span>
            {ROLES.map((r) => {
              const on = r.key === active.key;
              return (
                <button key={r.key} role="tab" aria-selected={on} onClick={() => pick(r.key)}
                  style={{ border: 'none', cursor: 'pointer', padding: '7px 14px', borderRadius: 7, fontSize: 12.5, fontWeight: 600, background: on ? 'var(--brand)' : 'transparent', color: on ? '#fff' : 'var(--txt-mut)' }}>
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
                <button key={key} role="radio" aria-checked={on} aria-label={sw.label} title={sw.label} onClick={() => setAccent(key)}
                  style={{ width: 22, height: 22, borderRadius: '50%', cursor: 'pointer', padding: 0, background: `linear-gradient(135deg, ${sw.brandBright}, ${sw.brand})`, border: on ? '2px solid var(--panel)' : '2px solid transparent', boxShadow: on ? `0 0 0 2px ${sw.brandBright}` : 'none' }} />
              );
            })}
          </div>
        </div>

        {active.key === 'employee' ? (
          // The WHOLE real Employee dashboard (hero, quick actions, stat tiles, calendar, leave
          // balance, notifications, holidays, birthdays, requests) — every widget reads canned data
          // because PreviewModeContext is on.
          <PreviewModeContext.Provider value>
            <div style={{ display: 'inline-flex', alignSelf: 'flex-start', padding: '5px 10px', borderRadius: 6, background: 'rgba(99,102,241,.10)', border: '1px solid rgba(99,102,241,.25)', fontSize: 11.5, fontWeight: 600, color: '#8183f4' }}>
              🔍 UI PREVIEW · Mock Data (Not Real)
            </div>
            <EmployeeDashboardView />
          </PreviewModeContext.Provider>
        ) : (
          <div>
            <h1 style={{ margin: 0, marginBottom: 3, fontSize: 22, fontWeight: 700, color: 'var(--txt)' }}>Good morning, Preview User.</h1>
            <p style={{ margin: 0, fontSize: 13, color: 'var(--txt-dim)' }}>{active.subtitle}</p>
            <div style={{ display: 'inline-flex', marginTop: 8, padding: '5px 10px', borderRadius: 6, background: 'rgba(99,102,241,.10)', border: '1px solid rgba(99,102,241,.25)', fontSize: 11.5, fontWeight: 600, color: '#8183f4' }}>
              🔍 UI PREVIEW · Mock Data (tile rows only for this role)
            </div>
          </div>
        )}

        {(active.key === 'manager' || active.key === 'hr') && (() => {
          const isHr = active.key === 'hr';
          return (
            <>
              <QuickActions actions={isHr ? HR_ADMIN_QUICK_ACTIONS : MANAGER_QUICK_ACTIONS} />
              <div className="nf-dash-row" style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(170px, 1fr))', gap: 12 }}>
                <DashTile icon={<Users size={16} />} accent="#4E9EE8" label={isHr ? 'Total Employees' : 'Direct Reports'} value={isHr ? '248' : '6'} sub={isHr ? 'across the organization' : 'current reports from HR record'} />
                <DashTile icon={<UserCheck size={16} />} accent="#2FB67C" label="Active" value={isHr ? '241' : '6'} sub={isHr ? 'active org-wide' : 'active in team'} />
                <DashTile icon={<Clock size={16} />} accent="var(--brand)" label="Present Today" value={isHr ? '203/248' : '5/6'} sub="checked in so far today · view list →" onClick={noop} />
                <DashTile icon={<Calendar size={16} />} accent="#8B5CF6" label="On Leave" value={isHr ? '17' : '1'} sub="on leave today · view list →" onClick={noop} />
              </div>
            </>
          );
        })()}

        {active.key === 'superadmin' && (
          <>
            <QuickActions actions={SUPER_ADMIN_QUICK_ACTIONS} />
            <div className="nf-dash-row" style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(170px, 1fr))', gap: 12 }}>
              <DashTile icon={<Users size={16} />} accent="#4E9EE8" label="Total Users" value="262" sub="249 active · 13 inactive · view directory →" onClick={noop} />
              <DashTile icon={<UserCheck size={16} />} accent="#2FB67C" label="Present Today" value="203/248" sub="org-wide check-ins · view list →" onClick={noop} />
              <DashTile icon={<Package size={16} />} accent="#F97316" label="Total Assets" value="531" sub="company-wide inventory · view inventory →" onClick={noop} />
              <DashTile icon={<ShieldCheck size={16} />} accent="var(--brand)" label="Audit Events Today" value="84" sub="security events logged · view log →" onClick={noop} />
            </div>
          </>
        )}
      </main>
    </div>
  );
}
