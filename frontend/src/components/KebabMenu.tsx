import { useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { MoreVertical } from 'lucide-react';

export interface KebabItem {
  label: string;
  onClick: () => void;
  danger?: boolean;
  dividerBefore?: boolean;
}

const ITEM_HEIGHT = 36; // approx px per item (9px top + 9px bottom padding + ~16px text)

export function KebabMenu({
  items,
  minWidth = 160,
}: {
  items: KebabItem[];
  minWidth?: number;
}) {
  const [open, setOpen] = useState(false);
  const [hovered, setHovered] = useState(false);
  const [pos, setPos] = useState<{ top?: number; bottom?: number; right: number }>({
    top: 0,
    right: 0,
  });
  const btnRef = useRef<HTMLButtonElement>(null);

  if (items.length === 0) return null;

  function handleOpen(e: React.MouseEvent) {
    e.stopPropagation();
    if (!open && btnRef.current) {
      const rect = btnRef.current.getBoundingClientRect();
      const menuHeight = items.length * ITEM_HEIGHT + 8;
      const spaceBelow = window.innerHeight - rect.bottom;
      const right = window.innerWidth - rect.right;
      if (spaceBelow < menuHeight) {
        setPos({ bottom: window.innerHeight - rect.top + 4, right });
      } else {
        setPos({ top: rect.bottom + 4, right });
      }
    }
    setOpen(o => !o);
  }

  const lit = open || hovered;

  return (
    <>
      <button
        ref={btnRef}
        onClick={handleOpen}
        aria-label="Actions"
        onMouseEnter={() => setHovered(true)}
        onMouseLeave={() => setHovered(false)}
        style={{
          background: lit ? 'var(--raised2)' : 'transparent',
          border: `1px solid ${lit ? 'var(--line2)' : 'transparent'}`,
          borderRadius: 6,
          width: 30,
          height: 30,
          cursor: 'pointer',
          color: lit ? 'var(--txt)' : 'var(--txt-mut)',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          transition: 'background .15s, border-color .15s, color .15s',
        }}
      >
        <MoreVertical size={14} />
      </button>

      {open &&
        createPortal(
          <>
            <div onClick={() => setOpen(false)} style={{ position: 'fixed', inset: 0, zIndex: 999 }} />
            <div
              style={{
                position: 'fixed',
                top: pos.top,
                bottom: pos.bottom,
                right: pos.right,
                background: 'var(--panel)',
                border: '1px solid var(--line)',
                borderRadius: 8,
                boxShadow: '0 12px 32px rgba(0,0,0,.55)',
                zIndex: 1000,
                minWidth,
                overflow: 'hidden',
              }}
            >
              {items.map((item, i) => (
                <button
                  key={i}
                  onClick={e => {
                    e.stopPropagation();
                    item.onClick();
                    setOpen(false);
                  }}
                  style={{
                    display: 'block',
                    width: '100%',
                    textAlign: 'left',
                    padding: '9px 14px',
                    fontSize: 12.5,
                    fontWeight: 500,
                    background: 'none',
                    border: 'none',
                    cursor: 'pointer',
                    color: item.danger ? '#E4373D' : 'var(--txt)',
                    borderTop: item.dividerBefore ? '1px solid var(--line)' : 'none',
                    transition: 'background .1s',
                  }}
                  onMouseEnter={e => {
                    (e.currentTarget as HTMLButtonElement).style.background = item.danger
                      ? 'rgba(228,55,61,.08)'
                      : 'var(--raised)';
                  }}
                  onMouseLeave={e => {
                    (e.currentTarget as HTMLButtonElement).style.background = 'none';
                  }}
                >
                  {item.label}
                </button>
              ))}
            </div>
          </>,
          document.body
        )}
    </>
  );
}
