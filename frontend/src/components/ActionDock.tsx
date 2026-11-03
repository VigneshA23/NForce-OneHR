import { useRef, useState } from 'react';
import { ChevronsLeft } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';

export interface DockItem {
  label: string;
  /** Without an icon the action renders as a small text button, so no action is ever unlabelled. */
  icon?: LucideIcon;
  onClick: () => void;
  danger?: boolean;
  disabled?: boolean;
}

/**
 * The one row-actions control used across the app: a small "Manage" pill that, on hover, keyboard
 * focus or tap, slides open a row of round icon buttons (each labelled by tooltip / aria-label) to its
 * left. It floats over the neighbouring content instead of pushing the layout, closes when the pointer
 * or focus leaves, and positions itself relative to its own pill, so it works in any cell or card.
 * Display only: it renders the items it is given and calls their handlers; it never decides what a
 * user may do.
 */
export function ActionDock({ items, label = 'Manage' }: { items: DockItem[]; label?: string }) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  if (items.length === 0) return null;

  return (
    <div
      ref={ref}
      className={`nf-dock${open ? ' is-open' : ''}`}
      onClick={e => e.stopPropagation()}
      onMouseLeave={() => setOpen(false)}
      onBlur={e => { if (!ref.current?.contains(e.relatedTarget as Node | null)) setOpen(false); }}
    >
      <div className="nf-dock-items" role="group" aria-label={`${label} actions`}>
        {items.map(it => (
          <button
            key={it.label}
            type="button"
            className={`nf-dock-btn${it.danger ? ' is-danger' : ''}${it.icon ? '' : ' is-text'}`}
            title={it.label}
            aria-label={it.label}
            disabled={it.disabled}
            onClick={e => { e.stopPropagation(); setOpen(false); it.onClick(); }}
          >
            {it.icon ? <it.icon size={15} /> : it.label}
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
