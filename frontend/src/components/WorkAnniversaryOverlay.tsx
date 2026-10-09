import { useMemo } from 'react';
import { motion, AnimatePresence, type Variants } from 'framer-motion';
import { X } from 'lucide-react';
import { EmployeeAvatar } from './EmployeeAvatar';
import { useAccessibilityPrefs } from '../lib/accessibilityPrefs';

interface WorkAnniversaryOverlayProps {
  open: boolean;
  employeeName: string;
  photoDataUrl?: string | null;
  years: number;
  onClose: () => void;
}

// Accent-derived, not hardcoded hex — picks up whichever Theme color the viewer has chosen (see
// lib/accentColor.tsx), same as the rest of the app, rather than a fixed "party" palette.
const CONFETTI_COLORS = ['var(--brand)', 'var(--brand-bright)', 'var(--ok)', 'var(--info)', 'var(--warn)'];
const BALLOON_COLORS = ['var(--brand)', 'var(--info)', 'var(--ok)'];

interface ConfettiPiece { left: number; delay: number; duration: number; size: number; color: string; round: boolean; }
interface SparklePoint { left: number; top: number; delay: number; duration: number; size: number; }

function ConfettiField({ pieces }: { pieces: ConfettiPiece[] }) {
  return (
    <div style={{ position: 'absolute', inset: 0, overflow: 'hidden', pointerEvents: 'none' }} aria-hidden="true">
      {pieces.map((p, i) => (
        <span
          key={i}
          style={{
            position: 'absolute', top: 0, left: `${p.left}%`,
            width: p.size, height: p.size * 1.6,
            background: p.color, opacity: .85,
            borderRadius: p.round ? '50%' : 2,
            animation: `nf-anniv-confetti-fall ${p.duration}s linear ${p.delay}s infinite`,
            willChange: 'transform',
          }}
        />
      ))}
    </div>
  );
}

function SparkleField({ points }: { points: SparklePoint[] }) {
  return (
    <div style={{ position: 'absolute', inset: 0, overflow: 'hidden', pointerEvents: 'none' }} aria-hidden="true">
      {points.map((s, i) => (
        <span
          key={i}
          style={{
            position: 'absolute', left: `${s.left}%`, top: `${s.top}%`,
            width: s.size, height: s.size, borderRadius: '50%',
            background: '#fff', boxShadow: '0 0 6px 1px rgba(255,255,255,.8)',
            animation: `nf-anniv-sparkle-twinkle ${s.duration}s ease-in-out ${s.delay}s infinite`,
            willChange: 'transform, opacity',
          }}
        />
      ))}
    </div>
  );
}

function Balloon({ left, color, delay, size }: { left: string; color: string; delay: number; size: number }) {
  return (
    <div
      aria-hidden="true"
      style={{
        position: 'absolute', bottom: '8%', left, width: size, height: size * 1.2,
        animation: `nf-anniv-balloon-float ${5 + delay}s ease-in-out ${delay}s infinite`,
        pointerEvents: 'none', opacity: .9,
      }}
    >
      <div style={{
        width: '100%', height: '100%', borderRadius: '50% 50% 50% 50% / 58% 58% 42% 42%',
        background: `radial-gradient(circle at 32% 28%, color-mix(in srgb, ${color} 55%, #fff) 0%, ${color} 60%, color-mix(in srgb, ${color} 70%, #000) 100%)`,
        boxShadow: '0 8px 20px rgba(0,0,0,.25)',
      }} />
      <div style={{ width: 1, height: size * 0.9, background: 'rgba(255,255,255,.4)', margin: '0 auto' }} />
    </div>
  );
}

const containerVariants: Variants = {
  hidden: {},
  show: (fast: boolean) => ({ transition: { staggerChildren: fast ? 0 : 0.16, delayChildren: fast ? 0 : 0.25 } }),
};
const itemVariants: Variants = {
  hidden: { opacity: 0, y: 18, scale: .96 },
  show: { opacity: 1, y: 0, scale: 1, transition: { duration: .55, ease: [0.16, 1, 0.3, 1] } },
};

/**
 * Full-screen work-anniversary celebration — mounted globally in Shell.tsx and shown/hidden via
 * the `open` prop (Shell owns the "should this appear today" decision; this component only
 * renders the experience itself). Purely decorative/ambient effects (confetti, sparkles, balloon
 * float, the photo's glow ring) are plain CSS keyframe animations, so the app's existing
 * "Reduce animations" accessibility preference ([data-motion="reduce"] in index.css) already
 * neutralizes them for free. The one-time entrance sequencing of the text/photo/button is
 * framer-motion (already a project dependency — see AttendanceHeroBanner/other motion usage),
 * and separately respects that same preference by collapsing its stagger/duration to near-zero.
 */
export function WorkAnniversaryOverlay({ open, employeeName, photoDataUrl, years, onClose }: WorkAnniversaryOverlayProps) {
  const { reduceAnimations } = useAccessibilityPrefs();
  const firstName = employeeName.trim().split(/\s+/)[0] || employeeName;

  // Randomized once per mount (not per render) so particles don't jump around mid-animation.
  const confetti = useMemo<ConfettiPiece[]>(() => Array.from({ length: 24 }, (_, i) => ({
    left: Math.random() * 100,
    delay: Math.random() * 4,
    duration: 5 + Math.random() * 4,
    size: 6 + Math.random() * 6,
    color: CONFETTI_COLORS[i % CONFETTI_COLORS.length],
    round: i % 3 === 0,
  })), []);
  const sparkles = useMemo<SparklePoint[]>(() => Array.from({ length: 18 }, () => ({
    left: Math.random() * 100,
    top: Math.random() * 100,
    delay: Math.random() * 3,
    duration: 2 + Math.random() * 2,
    size: 3 + Math.random() * 3,
  })), []);

  return (
    <AnimatePresence>
      {open && (
        <motion.div
          role="dialog"
          aria-modal="true"
          aria-label="Work anniversary celebration"
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          exit={{ opacity: 0 }}
          transition={{ duration: reduceAnimations ? 0 : 0.35 }}
          style={{
            position: 'fixed', inset: 0, zIndex: 950,
            background: 'rgba(8,8,10,.68)', backdropFilter: 'blur(10px)', WebkitBackdropFilter: 'blur(10px)',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            padding: 'clamp(16px, 4vw, 40px)', overflow: 'hidden',
          }}
        >
          {!reduceAnimations && <ConfettiField pieces={confetti} />}
          {!reduceAnimations && <SparkleField points={sparkles} />}
          {!reduceAnimations && (
            <>
              <Balloon left="6%" color={BALLOON_COLORS[0]} delay={0} size={64} />
              <Balloon left="14%" color={BALLOON_COLORS[1]} delay={1.2} size={44} />
              <Balloon left="88%" color={BALLOON_COLORS[2]} delay={0.6} size={58} />
              <Balloon left="80%" color={BALLOON_COLORS[0]} delay={1.8} size={38} />
            </>
          )}

          <button
            onClick={onClose}
            aria-label="Close celebration"
            style={{
              position: 'absolute', top: 'clamp(14px, 3vw, 28px)', right: 'clamp(14px, 3vw, 28px)',
              width: 36, height: 36, borderRadius: '50%', display: 'grid', placeItems: 'center',
              background: 'rgba(255,255,255,.12)', border: '1px solid rgba(255,255,255,.25)',
              color: '#fff', cursor: 'pointer',
            }}
          >
            <X size={17} aria-hidden="true" />
          </button>

          <motion.div
            custom={reduceAnimations}
            variants={containerVariants}
            initial="hidden"
            animate="show"
            style={{
              position: 'relative', display: 'flex', flexDirection: 'column', alignItems: 'center',
              textAlign: 'center', maxWidth: 'clamp(280px, 88vw, 480px)', gap: 'clamp(10px, 2vw, 16px)',
            }}
          >
            <motion.div
              variants={itemVariants}
              style={{
                borderRadius: '50%', animation: reduceAnimations ? 'none' : 'nf-anniv-ring-glow 2.6s ease-in-out infinite',
                padding: 6,
              }}
            >
              <EmployeeAvatar
                name={employeeName}
                photoDataUrl={photoDataUrl}
                size={128}
                fontSize={40}
                border="4px solid #fff"
              />
            </motion.div>

            <motion.h1
              variants={itemVariants}
              style={{
                margin: 0, fontSize: 'clamp(24px, 4.5vw, 34px)', fontWeight: 800, color: '#fff',
                fontFamily: 'Inter, sans-serif', textShadow: '0 2px 12px rgba(0,0,0,.4)',
              }}
            >
              Happy Work Anniversary, {firstName}! 🎉
            </motion.h1>

            <motion.div
              variants={itemVariants}
              style={{
                fontSize: 'clamp(15px, 2.6vw, 19px)', fontWeight: 700, color: 'var(--brand-bright)',
                textShadow: '0 1px 8px rgba(0,0,0,.4)',
              }}
            >
              {years} {years === 1 ? 'Year' : 'Years'} of Excellence
            </motion.div>

            <motion.p
              variants={itemVariants}
              style={{ margin: 0, fontSize: 'clamp(13px, 2.2vw, 15px)', color: 'rgba(255,255,255,.82)' }}
            >
              Celebrating another amazing year with us!
            </motion.p>

            <motion.button
              variants={itemVariants}
              onClick={onClose}
              style={{
                marginTop: 'clamp(6px, 1.6vw, 10px)', padding: '10px 28px', borderRadius: 8,
                background: 'var(--brand)', border: 'none', color: '#fff', fontSize: 14, fontWeight: 700,
                cursor: 'pointer', boxShadow: '0 4px 16px rgba(0,0,0,.3)',
              }}
            >
              Continue
            </motion.button>
          </motion.div>
        </motion.div>
      )}
    </AnimatePresence>
  );
}
