/**
 * ============================================================================
 *  MOCK / VISUAL PREVIEW — NOT CONNECTED TO ANY BACKEND
 * ============================================================================
 *  The four summary tiles of the Organization Hierarchy page, rendered by the real
 *  HierarchyStatTiles component with invented numbers; no login, session or API. The tiles read the
 *  same figures for every role (the page's own data scope is unchanged), so there is no role switcher.
 *
 *    /hierarchy-ui-preview            → loaded
 *    /hierarchy-ui-preview?state=loading
 *  The "Theme" swatches use the app's real accent-color setting.
 * ============================================================================
 */
import { useSearchParams } from 'react-router-dom';
import { useAccentColor, ACCENT_SWATCHES, type AccentColor } from '../../lib/accentColor';
import { HierarchyStatTiles } from '../HierarchyPage';

export default function HierarchyPreviewShell() {
  const [params] = useSearchParams();
  const { accent, setAccent } = useAccentColor();
  const loading = params.get('state') === 'loading';
  return (
    <div style={{ minHeight: '100vh', background: 'var(--shell)', padding: '24px clamp(16px, 4vw, 40px)', display: 'flex', flexDirection: 'column', gap: 18 }}>
      <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', alignItems: 'center' }}>
        <div role="radiogroup" aria-label="Theme color" style={{ display: 'inline-flex', alignItems: 'center', gap: 8, padding: '4px 12px', borderRadius: 10, background: 'var(--panel)', border: '1px solid var(--line)', height: 40, boxSizing: 'border-box' }}>
          <span style={{ fontSize: 10.5, fontWeight: 700, letterSpacing: '.08em', color: 'var(--txt-dim)' }}>THEME</span>
          {(Object.keys(ACCENT_SWATCHES) as AccentColor[]).map((key) => {
            const sw = ACCENT_SWATCHES[key];
            const on = accent === key;
            return (
              <button key={key} role="radio" aria-checked={on} aria-label={sw.label} title={sw.label} onClick={() => setAccent(key)}
                style={{ width: 16, height: 16, borderRadius: '50%', background: sw.brandBright, border: on ? '2px solid var(--txt)' : '2px solid transparent', cursor: 'pointer', padding: 0 }} />
            );
          })}
        </div>
        <div style={{ display: 'inline-flex', padding: '5px 10px', borderRadius: 6, background: 'rgba(99,102,241,.10)', border: '1px solid rgba(99,102,241,.25)', fontSize: 11.5, fontWeight: 600, color: '#8183f4' }}>
          🔍 UI PREVIEW · Mock Data (Not Real)
        </div>
      </div>
      <div>
        <h1 style={{ margin: 0, fontSize: 22, fontWeight: 700, color: 'var(--txt)' }}>Organization Hierarchy</h1>
        <p style={{ margin: '4px 0 0', fontSize: 13, color: 'var(--txt-dim)' }}>Click any card to re-center the view. Read-only - manage reporting lines in User Management.</p>
      </div>
      <HierarchyStatTiles
        summary={loading ? null : { total: 262, active: 249, inactive: 13, namedDeptCount: 9, unassigned: 4, levels: 6 }}
        topCount={loading ? null : 2}
      />
    </div>
  );
}
