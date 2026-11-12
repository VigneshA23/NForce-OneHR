import { useEffect, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { X, type LucideIcon } from 'lucide-react';
import './documentPopups.css';

/**
 * Shared popup shell for the My Documents & Policies page (policy, announcement, upload and
 * withdraw popups): themed header with icon tile, scrolling body and a pinned footer. Rendered in
 * a portal so it can't be clipped by the page's sticky top bar. Presentation only.
 */
export function DocPopup({ icon: Icon, title, sub, onClose, footer, children, size = 'md', tone = 'brand', closeOnBackdrop = false }: {
  icon: LucideIcon;
  title: string;
  sub?: ReactNode;
  onClose: () => void;
  footer?: ReactNode;
  children: ReactNode;
  size?: 'sm' | 'md' | 'lg';
  tone?: 'brand' | 'risk';
  closeOnBackdrop?: boolean;
}) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [onClose]);

  return createPortal(
    <div className="nf-dp-overlay" role="presentation" onMouseDown={closeOnBackdrop ? (e) => { if (e.target === e.currentTarget) onClose(); } : undefined}>
      <div className={`nf-dp-modal nf-dp-${size} nf-dp-tone-${tone}`} role="dialog" aria-modal="true" aria-label={title}>
        <div className="nf-dp-head">
          <span className="nf-dp-ico"><Icon size={20} /></span>
          <div className="nf-dp-titles">
            <div className="nf-dp-title">{title}</div>
            {sub && <div className="nf-dp-sub">{sub}</div>}
          </div>
          <button type="button" className="nf-dp-x" onClick={onClose} aria-label="Close"><X size={16} /></button>
        </div>
        <div className="nf-dp-body">{children}</div>
        {footer && <div className="nf-dp-foot">{footer}</div>}
      </div>
    </div>,
    document.body,
  );
}
