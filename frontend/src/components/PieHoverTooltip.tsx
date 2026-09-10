import type { ReactNode, RefObject } from 'react';
import type { TooltipContentProps } from 'recharts';

// Recharts anchors a Pie tooltip on the hovered slice, but its wrapper always offsets it the
// same direction (right of the anchor) — and with allowEscapeViewBox on (needed so it can render
// past these charts' own small bounds instead of being clamped back over the donut hole), it
// never flips to the left even when the hovered slice sits on the left side of the ring. This
// custom content renders flipped to the *left* of the anchor whenever that anchor is left of the
// chart's own center, so the tooltip always appears beside — not across — the hovered section.
//
// `containerRef` must point at the chart's own square/circular wrapper div (the one Pie's
// cx="50%" cy="50%" is relative to) so its mid-width gives the pie's true center; `formatter`
// mirrors Tooltip's own formatter prop (value, name) -> [displayValue, displayName].
export function PieHoverTooltip({
  active,
  payload,
  coordinate,
  containerRef,
  formatter,
}: Pick<TooltipContentProps<number, string>, 'active' | 'payload' | 'coordinate'> & {
  containerRef: RefObject<HTMLDivElement | null>;
  formatter: (value: number, name: string) => [ReactNode, ReactNode];
}) {
  if (!active || !payload || payload.length === 0 || !coordinate) return null;

  const entry = payload[0];
  const [displayValue, displayName] = formatter(Number(entry.value ?? 0), String(entry.name ?? ''));
  const centerX = (containerRef.current?.clientWidth ?? 0) / 2;
  const isLeftHalf = coordinate.x < centerX;

  return (
    <div
      style={{
        display: 'flex', alignItems: 'center', gap: 6,
        transform: isLeftHalf ? 'translateX(calc(-100% - 20px))' : 'translateX(10px)',
        background: 'var(--raised)', border: '1px solid var(--line)', borderRadius: 7,
        padding: '6px 10px', fontSize: 12, color: 'var(--txt)',
        whiteSpace: 'nowrap', pointerEvents: 'none',
      }}
    >
      <span style={{ width: 8, height: 8, borderRadius: 2, background: entry.color ?? entry.fill, flexShrink: 0 }} />
      <span>{displayName}: <b style={{ fontWeight: 700 }}>{displayValue}</b></span>
    </div>
  );
}
