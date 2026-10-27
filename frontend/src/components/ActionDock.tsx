import { useRef, useState } from 'react';
import { ChevronsLeft } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';

export interface DockItem {
  label: string;
  icon: LucideIcon;
  onClick: () => void;
  danger?: boolean;
}

/**
 * Row actions as a slide-out "dock": a small "Manage" pill that, on hover, keyboard focus or tap,
 * slides open a row of round icon buttons (each labelled by tooltip / aria-label). It floats over the
 * neighbouring cells instead of pushing the layout, and closes when the pointer or focus leaves.
 * Display only: it renders the items it is given and calls their handlers; it never decides what a
 * user may do. Place it in a `position: relative` table cell.
 */
export function ActionDock({ items, label = 'Manage' }: { items: DockItem[]; label?: string }) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  if (items.length === 0) return null;

  return (
    <div
      ref={ref}
      className={`nf-dock${open ? ' is-open' : ''}`}
      onMouseLeave={() => setOpen(false)}
      onBlur={e => { if (!ref.current?.contains(e.relatedTarget as Node | null)) setOpen(false); }}
    >
      <div className="nf-dock-items" role="group" aria-label={`${label} actions`}>
        {items.map(it => (
          <button
            key={it.label}
            type="button"
            className={`nf-dock-btn${it.danger ? ' is-danger' : ''}`}
            title={it.label}
            aria-label={it.label}
            onClick={e => { e.stopPropagation(); setOpen(false); it.onClick(); }}
          >
            <it.icon size={15} />
          </button>
        ))}
      </div>
      <button type="button" className="nf-dock-trigger" aria-expanded={open} onClick={e => { e.stopPropagation(); setOpen(o => !o); }}>
        <ChevronsLeft size={14} />
        {label}
      </button>
    </div>
  );
}
