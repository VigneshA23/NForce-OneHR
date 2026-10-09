import { useEffect, useMemo, useState } from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import { PartyPopper, X } from 'lucide-react';
import { useAccessibilityPrefs } from '../lib/accessibilityPrefs';

export interface KudosCelebrationItem {
  id: number;
  title: string;
  message: string;
}

const AUTO_DISMISS_MS = 6000;
const EMOJIS = ['🎉', '🎊', '👏', '✨', '🥳', '🙌'];
const BURST_DURATION_MS = 3500;

/**
 * A small, on-page celebration for a KUDOS notification — distinct from the full-screen
 * WorkAnniversaryOverlay, since being appreciated by a teammate is a lighter, more frequent
 * moment than an anniversary. Mounted once in Shell.tsx (the root layout) and fed by Shell's
 * existing notification poll via notificationEvents.ts, so it appears on whichever page the
 * recipient happens to be on — no per-page wiring needed, matching how the poll already feeds
 * AttendancePage/LeavePage/MyRequestsPage today.
 *
 * Reuses the same sparkle keyframe added for the anniversary overlay (nf-anniv-sparkle-twinkle,
 * index.css) rather than inventing new CSS, and framer-motion (already a dependency) for the
 * enter/exit — no new libraries.
 */
export function KudosCelebrationToast({ items, onDismiss }: {
  items: KudosCelebrationItem[];
  onDismiss: (id: number) => void;
}) {
  // Re-fires the emoji burst for each new kudos as it arrives, rather than only once ever —
  // keyed on the most recent item's id, so it restarts if another kudos comes in while one is
  // still showing, but doesn't replay on every unrelated re-render of this component.
  const latestId = items.length > 0 ? items[items.length - 1].id : null;

  return (
    <>
      {latestId !== null && <EmojiBurst triggerKey={latestId} />}
      <div
        aria-live="polite"
        style={{
          position: 'fixed', top: 72, right: '1rem', zIndex: 900,
          display: 'flex', flexDirection: 'column', gap: 10,
          maxWidth: 360, width: 'calc(100% - 2rem)', pointerEvents: 'none',
        }}
      >
        <AnimatePresence>
          {items.map((item) => (
            <KudosCard key={item.id} item={item} onDismiss={() => onDismiss(item.id)} />
          ))}
        </AnimatePresence>
      </div>
    </>
  );
}

interface EmojiPiece { left: number; delay: number; duration: number; size: number; drift: number; emoji: string; }

/** A brief (3.5s) burst of emoji floating up from the bottom of the screen, alongside the
 * KudosCard. Plain CSS keyframe animation (nf-kudos-emoji-rise, index.css) — automatically
 * respects the "Reduce animations" preference the same way the anniversary overlay's ambient
 * effects do, plus is explicitly skipped here too so no burst timer runs needlessly. */
function EmojiBurst({ triggerKey }: { triggerKey: number }) {
  const { reduceAnimations } = useAccessibilityPrefs();
  const [visible, setVisible] = useState(false);

  const pieces = useMemo<EmojiPiece[]>(() => Array.from({ length: 14 }, (_, i) => ({
    left: Math.random() * 100,
    delay: Math.random() * 0.6,
    duration: 2.6 + Math.random() * 1.4,
    size: 22 + Math.random() * 14,
    drift: (Math.random() - 0.5) * 60,
    emoji: EMOJIS[i % EMOJIS.length],
  // eslint-disable-next-line react-hooks/exhaustive-deps
  })), [triggerKey]);

  useEffect(() => {
    if (reduceAnimations) return;
    setVisible(true);
    const t = setTimeout(() => setVisible(false), BURST_DURATION_MS);
    return () => clearTimeout(t);
  }, [triggerKey, reduceAnimations]);

  if (!visible) return null;

  return (
    <div aria-hidden="true" style={{ position: 'fixed', inset: 0, zIndex: 890, overflow: 'hidden', pointerEvents: 'none' }}>
      {pieces.map((p, i) => (
        <span
          key={i}
          style={{
            position: 'absolute', bottom: -40, left: `${p.left}%`, fontSize: p.size,
            animation: `nf-kudos-emoji-rise ${p.duration}s ease-out ${p.delay}s forwards`,
            ['--nf-emoji-drift' as string]: `${p.drift}px`,
          }}
        >
          {p.emoji}
        </span>
      ))}
    </div>
  );
}

function KudosCard({ item, onDismiss }: { item: KudosCelebrationItem; onDismiss: () => void }) {
  const { reduceAnimations } = useAccessibilityPrefs();

  useEffect(() => {
    const t = setTimeout(onDismiss, AUTO_DISMISS_MS);
    return () => clearTimeout(t);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [item.id]);

  return (
    <motion.div
      role="status"
      layout
      initial={{ opacity: 0, x: 40, scale: .95 }}
      animate={{ opacity: 1, x: 0, scale: 1 }}
      exit={{ opacity: 0, x: 40, scale: .95 }}
      transition={{ duration: reduceAnimations ? 0 : 0.3, ease: [0.16, 1, 0.3, 1] }}
      style={{
        position: 'relative', overflow: 'hidden', pointerEvents: 'auto',
        display: 'flex', alignItems: 'flex-start', gap: 10, padding: '12px 14px',
        borderRadius: 10, background: 'var(--panel)',
        border: '1px solid color-mix(in srgb, var(--brand) 35%, var(--line))',
        boxShadow: '0 8px 28px rgba(0,0,0,.3), 0 0 0 1px var(--line)',
      }}
    >
      {!reduceAnimations && (
        <span aria-hidden="true" style={{
          position: 'absolute', top: 6, right: 34, width: 4, height: 4, borderRadius: '50%',
          background: '#fff', boxShadow: '0 0 5px 1px rgba(255,255,255,.8)',
          animation: 'nf-anniv-sparkle-twinkle 1.8s ease-in-out infinite',
        }} />
      )}
      {!reduceAnimations && (
        <span aria-hidden="true" style={{
          position: 'absolute', top: 18, right: 14, width: 3, height: 3, borderRadius: '50%',
          background: '#fff', boxShadow: '0 0 4px 1px rgba(255,255,255,.7)',
          animation: 'nf-anniv-sparkle-twinkle 2.2s ease-in-out .4s infinite',
        }} />
      )}

      <span style={{
        flexShrink: 0, width: 30, height: 30, borderRadius: '50%', marginTop: 1,
        background: 'color-mix(in srgb, var(--brand) 16%, var(--raised2))',
        color: 'var(--brand-bright)', display: 'grid', placeItems: 'center',
      }}>
        <PartyPopper size={15} aria-hidden="true" />
      </span>

      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--txt)', marginBottom: 2 }}>{item.title}</div>
        <div style={{ fontSize: 12.5, color: 'var(--txt-mut)', lineHeight: 1.4 }}>{item.message}</div>
      </div>

      <button
        onClick={onDismiss}
        aria-label="Dismiss"
        style={{ flexShrink: 0, background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt-dim)', padding: 2, marginTop: 1 }}
      >
        <X size={13} aria-hidden="true" />
      </button>
    </motion.div>
  );
}
