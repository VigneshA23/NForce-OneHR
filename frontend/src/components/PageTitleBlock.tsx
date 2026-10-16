import type { CSSProperties, ReactNode } from 'react';
import type { LucideIcon } from 'lucide-react';

/**
 * Page title with a small icon box on its left (same look as the User Management header): the icon sits in
 * a rounded tile tinted with the chosen theme colour, and the page's own heading + subtitle sit to its right.
 * The icon is top-aligned with the heading (not centred on the heading + subtitle). Display only; wrap a page's existing <h1> / <p> in it and pass the icon that fits the module.
 * `style` is applied to the outer row, so a page's existing margin/flex sizing on its old title wrapper carries over.
 */
export default function PageTitleBlock({ icon: Icon, children, style, className, compact }: {
  icon: LucideIcon;
  children: ReactNode;
  style?: CSSProperties;
  className?: string;
  /** Smaller icon box for a section heading inside a page (instead of the page title itself). */
  compact?: boolean;
}) {
  return (
    <div className={className ? `nf-page-title ${className}` : 'nf-page-title'} style={{ display: 'flex', alignItems: 'flex-start', gap: compact ? 10 : 12, minWidth: 0, ...style }}>
      <div
        aria-hidden="true"
        style={{
          width: compact ? 34 : 40, height: compact ? 34 : 40, borderRadius: compact ? 10 : 11, flexShrink: 0, display: 'grid', placeItems: 'center',
          color: 'var(--brand-bright, var(--brand))',
          background: 'color-mix(in srgb, var(--brand) 14%, var(--raised))',
          border: '1px solid color-mix(in srgb, var(--brand) 26%, var(--line))',
        }}
      >
        <Icon size={compact ? 17 : 19} />
      </div>
      <div style={{ minWidth: 0 }}>{children}</div>
    </div>
  );
}
