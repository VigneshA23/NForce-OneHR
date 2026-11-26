/**
 * Shared frame for the design-review mock pages (PN-03 / PN-04): a sticky top bar with the story tag,
 * the app's real accent-colour swatches and a Light / Dark switch, then a hero card and the content.
 * Everything is drawn from the app's theme tokens (--brand, --panel, ...), so picking a swatch recolours
 * the whole page exactly as it does in the real app. Mock only: nothing here talks to a backend.
 */
import type { ReactNode } from 'react';
import type { LucideIcon } from 'lucide-react';
import { useAccentColor, ACCENT_SWATCHES, type AccentColor } from '../../lib/accentColor';
import { useTheme } from '../../lib/theme';

const seg = (on: boolean): React.CSSProperties => ({
  border: 'none', cursor: 'pointer', padding: '6px 12px', borderRadius: 7, fontSize: 12, fontWeight: 600,
  background: on ? 'var(--brand)' : 'transparent', color: on ? '#fff' : 'var(--txt-mut)',
});

export function PreviewChrome({ story, title, subtitle, icon: Icon, stats, children }: {
  story: string; title: string; subtitle: string; icon: LucideIcon;
  stats: { label: string; value: string }[]; children: ReactNode;
}) {
  const { accent, setAccent } = useAccentColor();
  const { theme, setMode } = useTheme();
  return (
    <div style={{ minHeight: '100vh', background: 'var(--shell)', color: 'var(--txt)', fontFamily: 'Inter, sans-serif' }}>
      <div style={{ position: 'sticky', top: 0, zIndex: 20, backdropFilter: 'blur(8px)', background: 'color-mix(in srgb, var(--shell) 86%, transparent)', borderBottom: '1px solid var(--line)' }}>
        <div style={{ maxWidth: 1180, margin: '0 auto', padding: '10px 28px', display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
          <span style={{ fontSize: 10.5, fontWeight: 800, letterSpacing: '.08em', textTransform: 'uppercase', padding: '5px 10px', borderRadius: 6, color: 'var(--brand)', background: 'color-mix(in srgb, var(--brand) 12%, var(--panel))', border: '1px solid color-mix(in srgb, var(--brand) 28%, var(--line))' }}>
            Mock preview · {story}
          </span>
          <span style={{ fontSize: 12, color: 'var(--txt-dim)' }}>Sample data only. Nothing is saved or sent.</span>
          <div style={{ marginLeft: 'auto', display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
            <div role="radiogroup" aria-label="Theme colour" style={{ display: 'inline-flex', alignItems: 'center', gap: 8, padding: '4px 12px', borderRadius: 10, background: 'var(--panel)', border: '1px solid var(--line)', height: 34, boxSizing: 'border-box' }}>
              <span style={{ fontSize: 10.5, fontWeight: 700, letterSpacing: '.06em', textTransform: 'uppercase', color: 'var(--txt-dim)' }}>Theme</span>
              {(Object.keys(ACCENT_SWATCHES) as AccentColor[]).map((key) => {
                const sw = ACCENT_SWATCHES[key]; const on = key === accent;
                return (
                  <button key={key} role="radio" aria-checked={on} aria-label={sw.label} title={sw.label} onClick={() => setAccent(key)}
                    style={{ width: 20, height: 20, borderRadius: '50%', cursor: 'pointer', padding: 0, background: `linear-gradient(135deg, ${sw.brandBright}, ${sw.brand})`,
                      border: on ? '2px solid var(--panel)' : '2px solid transparent', boxShadow: on ? `0 0 0 2px ${sw.brandBright}` : 'none' }} />
                );
              })}
            </div>
            <div role="group" aria-label="Light or dark" style={{ display: 'inline-flex', gap: 2, padding: 3, borderRadius: 10, background: 'var(--panel)', border: '1px solid var(--line)' }}>
              <button style={seg(theme === 'light')} onClick={() => setMode('light')}>Light</button>
              <button style={seg(theme === 'dark')} onClick={() => setMode('dark')}>Dark</button>
            </div>
          </div>
        </div>
      </div>

      <main style={{ maxWidth: 1180, margin: '0 auto', padding: '26px 28px 64px', display: 'flex', flexDirection: 'column', gap: 34 }}>
        <header style={{
          position: 'relative', overflow: 'hidden', borderRadius: 18, padding: '26px 28px', border: '1px solid color-mix(in srgb, var(--brand) 26%, var(--line))',
          background: 'linear-gradient(120deg, color-mix(in srgb, var(--brand) 16%, var(--panel)) 0%, var(--panel) 62%)',
        }}>
          <div aria-hidden style={{ position: 'absolute', right: -50, top: -60, width: 240, height: 240, borderRadius: '50%', background: 'color-mix(in srgb, var(--brand) 14%, transparent)' }} />
          <div style={{ position: 'relative', display: 'flex', gap: 18, alignItems: 'center', flexWrap: 'wrap' }}>
            <div style={{ width: 54, height: 54, borderRadius: 16, display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#fff', background: 'linear-gradient(135deg, var(--brand-bright, var(--brand)), var(--brand))', boxShadow: '0 10px 22px -10px var(--brand)' }}>
              <Icon size={26} />
            </div>
            <div style={{ flex: '1 1 320px', minWidth: 0 }}>
              <h1 style={{ margin: 0, fontFamily: '"Space Grotesk", sans-serif', fontSize: 23, fontWeight: 700, letterSpacing: '-.01em' }}>{title}</h1>
              <p style={{ margin: '5px 0 0', fontSize: 13, lineHeight: 1.55, color: 'var(--txt-mut)', maxWidth: 640 }}>{subtitle}</p>
            </div>
          </div>
          <div style={{ position: 'relative', display: 'flex', gap: 10, flexWrap: 'wrap', marginTop: 18 }}>
            {stats.map((s) => (
              <div key={s.label} style={{ padding: '8px 14px', borderRadius: 12, background: 'color-mix(in srgb, var(--panel) 80%, transparent)', border: '1px solid var(--line)' }}>
                <div style={{ fontSize: 10, fontWeight: 700, letterSpacing: '.07em', textTransform: 'uppercase', color: 'var(--txt-dim)' }}>{s.label}</div>
                <div style={{ fontSize: 14, fontWeight: 700, marginTop: 2 }}>{s.value}</div>
              </div>
            ))}
          </div>
        </header>
        {children}
      </main>
    </div>
  );
}

/** A numbered section: step badge, title, one-line hint, then the content. */
export function PreviewSection({ n, title, hint, children }: { n: number; title: string; hint: string; children: ReactNode }) {
  return (
    <section>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 12 }}>
        <span style={{ width: 28, height: 28, borderRadius: 9, display: 'inline-flex', alignItems: 'center', justifyContent: 'center', fontSize: 13, fontWeight: 800, color: '#fff', background: 'var(--brand)', flexShrink: 0 }}>{n}</span>
        <div>
          <h2 style={{ margin: 0, fontSize: 16, fontWeight: 700 }}>{title}</h2>
          <p style={{ margin: '2px 0 0', fontSize: 12.5, color: 'var(--txt-mut)' }}>{hint}</p>
        </div>
      </div>
      {children}
    </section>
  );
}
