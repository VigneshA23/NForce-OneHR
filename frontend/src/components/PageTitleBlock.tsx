import type { CSSProperties, ReactNode } from 'react';
import type { LucideIcon } from 'lucide-react';

/**
 * Page title with a small icon box on its left (same look as the User Management header): the icon sits in
 * a rounded tile tinted with the chosen theme colour, and the page's own heading + subtitle sit to its right.
 * The icon is top-aligned with the heading (not centred on the heading + subtitle). Display only; wrap a page's existing <h1> / <p> in it and pass the icon that fits the module.
 * `style` is applied to the outer row, so a page's existing margin/flex sizing on its old title wrapper carries over.
 */
export default function PageTitleBlock({ icon: Icon, children, style, className }: {
  icon: LucideIcon;
  children: ReactNode;
  style?: CSSProperties;
  className?: string;
}) {
  return (
    <div className={className ? `nf-page-title ${className}` : 'nf-page-title'} style={{ display: 'flex', alignItems: 'flex-start', gap: 12, minWidth: 0, ...style }}>
      <div
        aria-hidden="true"
        style={{
          width: 40, height: 40, borderRadius: 11, flexShrink: 0, display: 'grid', placeItems: 'center',
          color: 'var(--brand-bright, var(--brand))',
          background: 'color-mix(in srgb, var(--brand) 14%, var(--raised))',
          border: '1px solid color-mix(in srgb, var(--brand) 26%, var(--line))',
        }}
      >
        <Icon size={19} />
      </div>
      <div style={{ minWidth: 0 }}>{children}</div>
    </div>
  );
}
