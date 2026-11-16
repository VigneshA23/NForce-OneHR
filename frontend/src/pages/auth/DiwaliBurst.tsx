import { useMemo } from 'react';
import { createPortal } from 'react-dom';
import type { CSSProperties } from 'react';

/**
 * Cracker-lights shown inside the login card when sign-in succeeds. The sparks are confined to
 * the card (clipped to its rounded edge) and never spread over the rest of the page. Pure CSS
 * particles (see login.css). Decorative only.
 */
const COLORS = ['#ffb703', '#fb8500', '#ff4d6d', '#ffd166', '#ff9f1c', '#f72585', '#fff3b0'];
const PARTICLES = 18;
// Extra bursts, as % of the card, fired after the one from the button.
const BURSTS = [
  { x: 50, y: 32, delay: 0.25, size: 95 },
  { x: 22, y: 22, delay: 0.45, size: 75 },
  { x: 80, y: 24, delay: 0.6, size: 80 },
  { x: 28, y: 72, delay: 0.75, size: 70 },
  { x: 74, y: 70, delay: 0.9, size: 75 },
];

export function DiwaliBurst() {
  const card = document.querySelector('.nf-lg-card') as HTMLElement | null;
  // First burst fires from the sign-in button itself, as if it set off the cracker.
  const origin = useMemo(() => {
    const c = card?.getBoundingClientRect();
    const b = document.querySelector('.nf-lg-submit')?.getBoundingClientRect();
    return c && b ? { x: ((b.left + b.width / 2 - c.left) / c.width) * 100, y: ((b.top + b.height / 2 - c.top) / c.height) * 100 } : { x: 50, y: 70 };
  }, [card]);
  if (!card) return null;
  const bursts = [{ x: origin.x, y: origin.y, delay: 0, size: 80 }, ...BURSTS];
  return createPortal(
    <div className="nf-dw" aria-hidden="true">
      <div className="nf-dw-glow" />
      {bursts.map((b, bi) => (
        <div key={bi} className="nf-dw-burst" style={{ left: `${b.x}%`, top: `${b.y}%`, ['--dl' as string]: `${b.delay}s` } as CSSProperties}>
          {Array.from({ length: PARTICLES }, (_, i) => {
            const angle = (360 / PARTICLES) * i + (bi % 2 ? 10 : 0);
            const dist = b.size * (i % 2 ? 0.62 : 1);
            return (
              <i key={i} style={{ ['--a' as string]: `${angle}deg`, ['--d' as string]: `${dist}px`, ['--c' as string]: COLORS[(i + bi) % COLORS.length] } as CSSProperties} />
            );
          })}
        </div>
      ))}
    </div>,
    card,
  );
}
