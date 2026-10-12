import type { CSSProperties, ReactNode } from 'react';

/**
 * Dashboard tile — rounded card, thick accent bar down the left edge, a soft fade from the panel
 * color into a tint of the accent toward the bottom-right corner, label + big value on the left and
 * a tinted icon box on the right. Used by every role's Home dashboard (stat tiles and quick-action
 * tiles) so they share one look. Presentation only: it renders whatever label/value/sub it is given.
 *
 * Sizing is container-query based (.nf-dash-* in index.css), so the number and icon shrink with the
 * tile instead of crossing its outline at narrow widths or higher browser zoom.
 *
 * `value` omitted → "action" tile (title + description, used for Quick Actions).
 */
export interface DashTileProps {
  icon: ReactNode;
  label: string;
  value?: string;
  sub?: string;
  /** Any CSS color — pass `var(--brand)` for tiles that should follow the chosen theme color. */
  accent: string;
  onClick?: () => void;
}

export default function DashTile({ icon, label, value, sub, accent, onClick }: DashTileProps) {
  const Tag = onClick ? 'button' : 'div';
  const isAction = value === undefined;
  const style: CSSProperties = {
    background: `linear-gradient(135deg, var(--panel) 0%, var(--panel) 35%, color-mix(in srgb, ${accent} 16%, var(--panel)) 100%)`,
    border: `1px solid color-mix(in srgb, ${accent} 24%, var(--line))`,
    borderLeft: `6px solid ${accent}`,
    borderRadius: 18,
    boxShadow: `0 6px 18px -10px color-mix(in srgb, ${accent} 55%, transparent)`,
    padding: '14px 16px 12px 14px',
    display: 'flex', flexDirection: 'column', gap: 6, textAlign: 'left', width: '100%', minWidth: 0,
    font: 'inherit', cursor: onClick ? 'pointer' : 'default',
  };
  return (
    <Tag onClick={onClick} className="nf-dash-tile" style={style}>
      <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 8 }}>
        <div style={{
          minWidth: 0, overflowWrap: 'anywhere', lineHeight: 1.3, paddingTop: 2,
          ...(isAction
            ? { fontSize: 13, fontWeight: 700, color: 'var(--txt)' }
            : { fontSize: 11, fontWeight: 800, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.07em' }),
        }}>
          {label}
        </div>
        <div className="nf-dash-icon" style={{
          borderRadius: 13, display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0, color: accent,
          background: `color-mix(in srgb, ${accent} 14%, var(--panel))`, border: `1px solid color-mix(in srgb, ${accent} 22%, transparent)`,
          boxShadow: `0 4px 10px -6px color-mix(in srgb, ${accent} 60%, transparent)`,
        }}>
          {icon}
        </div>
      </div>
      {!isAction && (
        <div className="nf-dash-value" style={{ fontWeight: 800, color: 'var(--txt)', lineHeight: 1, whiteSpace: 'nowrap', fontVariantNumeric: 'tabular-nums', fontFamily: '"Space Grotesk", sans-serif' }}>
          {value}
        </div>
      )}
      {sub && <div style={{ fontSize: 11, color: 'var(--txt-dim)', lineHeight: 1.35 }}>{sub}</div>}
    </Tag>
  );
}
