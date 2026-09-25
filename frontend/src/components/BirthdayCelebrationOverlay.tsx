import { useMemo } from 'react';
import { motion, AnimatePresence, type Variants } from 'framer-motion';
import { X, Cake } from 'lucide-react';
import { EmployeeAvatar } from './EmployeeAvatar';
import { useAccessibilityPrefs } from '../lib/accessibilityPrefs';

interface BirthdayCelebrationOverlayProps {
  open: boolean;
  employeeName: string;
  photoDataUrl?: string | null;
  /** Present only for the combined variant (birthday + work anniversary on the same day). */
  anniversaryYears?: number;
  onClose: () => void;
}

// Same accent-derived palette as WorkAnniversaryOverlay, for the same reason: it should pick up
// whichever Theme color the viewer has chosen, not a fixed "party" palette.
const CONFETTI_COLORS = ['var(--brand)', 'var(--brand-bright)', 'var(--ok)', 'var(--info)'];

interface ConfettiPiece { left: number; delay: number; duration: number; size: number; color: string; round: boolean; }
interface SparklePoint { left: number; top: number; delay: number; duration: number; size: number; }

// Deliberately fewer/slower/more muted than WorkAnniversaryOverlay's own fields — this is meant to
// read as "subtle confetti", not the full celebration: 12 pieces instead of 24, a lower peak
// opacity, and no balloons at all (kept out entirely per the brief: a premium corporate moment,
// not a party screen).
function ConfettiField({ pieces }: { pieces: ConfettiPiece[] }) {
  return (
    <div style={{ position: 'absolute', inset: 0, overflow: 'hidden', pointerEvents: 'none' }} aria-hidden="true">
      {pieces.map((p, i) => (
        <span
          key={i}
          style={{
            position: 'absolute', top: 0, left: `${p.left}%`,
            width: p.size, height: p.size * 1.6,
            background: p.color, opacity: .55,
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
            background: '#fff', boxShadow: '0 0 6px 1px rgba(255,255,255,.7)',
            animation: `nf-anniv-sparkle-twinkle ${s.duration}s ease-in-out ${s.delay}s infinite`,
            willChange: 'transform, opacity',
          }}
        />
      ))}
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
 * Full-screen birthday celebration — a deliberately restrained sibling of
 * WorkAnniversaryOverlay.tsx (same backdrop/avatar-ring/entrance-sequencing structure, reusing its
 * `nf-anniv-*` CSS keyframes rather than inventing near-duplicate ones), toned down per the brief:
 * fewer/softer confetti pieces, no balloons, and a small Cake glyph instead of anything cartoonish.
 * Renders one of two copy variants:
 *  - birthday-only: "Happy Birthday" + a wish for the year ahead.
 *  - combined (`anniversaryYears` set): a single "Double Celebration" card acknowledging both the
 *    birthday and the work anniversary, rather than stacking two separate overlays back to back.
 *
 * Mounted globally in Shell.tsx exactly like WorkAnniversaryOverlay — Shell owns the "should this
 * appear, and which variant" decision (see lib/birthday.ts and the profile-sync effect), this
 * component only renders the experience itself.
 */
export function BirthdayCelebrationOverlay({ open, employeeName, photoDataUrl, anniversaryYears, onClose }: BirthdayCelebrationOverlayProps) {
  const { reduceAnimations } = useAccessibilityPrefs();
  const firstName = employeeName.trim().split(/\s+/)[0] || employeeName;
  const isCombined = anniversaryYears != null && anniversaryYears > 0;

  const confetti = useMemo<ConfettiPiece[]>(() => Array.from({ length: 12 }, (_, i) => ({
    left: Math.random() * 100,
    delay: Math.random() * 4,
    duration: 6 + Math.random() * 4,
    size: 5 + Math.random() * 5,
    color: CONFETTI_COLORS[i % CONFETTI_COLORS.length],
    round: i % 3 === 0,
  })), []);
  const sparkles = useMemo<SparklePoint[]>(() => Array.from({ length: 14 }, () => ({
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
          aria-label={isCombined ? 'Birthday and work anniversary celebration' : 'Birthday celebration'}
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
              textAlign: 'center', maxWidth: 'clamp(280px, 88vw, 480px)', gap: 'clamp(8px, 1.8vw, 14px)',
            }}
          >
            {isCombined && (
              <motion.div
                variants={itemVariants}
                style={{
                  fontSize: 'clamp(11px, 1.8vw, 13px)', fontWeight: 700, letterSpacing: '.06em',
                  textTransform: 'uppercase', color: 'var(--brand-bright)',
                }}
              >
                🎉 Double Celebration
              </motion.div>
            )}

            <motion.div
              variants={itemVariants}
              style={{
                position: 'relative', borderRadius: '50%', padding: 6,
                animation: reduceAnimations ? 'none' : 'nf-anniv-ring-glow 2.6s ease-in-out infinite',
              }}
            >
              <EmployeeAvatar
                name={employeeName}
                photoDataUrl={photoDataUrl}
                size={128}
                fontSize={40}
                border="4px solid #fff"
              />
              <div
                aria-hidden="true"
                style={{
                  position: 'absolute', bottom: -4, right: -4, width: 40, height: 40, borderRadius: '50%',
                  background: 'var(--brand)', border: '3px solid #fff',
                  display: 'grid', placeItems: 'center', boxShadow: '0 2px 10px rgba(0,0,0,.35)',
                }}
              >
                <Cake size={19} color="#fff" aria-hidden="true" />
              </div>
            </motion.div>

            <motion.h1
              variants={itemVariants}
              style={{
                margin: 0, fontSize: 'clamp(22px, 4.2vw, 32px)', fontWeight: 800, color: '#fff',
                fontFamily: 'Inter, sans-serif', textShadow: '0 2px 12px rgba(0,0,0,.4)',
              }}
            >
              🎂 Happy Birthday, {firstName}!
            </motion.h1>

            {isCombined && (
              <motion.div
                variants={itemVariants}
                style={{
                  fontSize: 'clamp(14px, 2.4vw, 17px)', fontWeight: 700, color: 'var(--brand-bright)',
                  textShadow: '0 1px 8px rgba(0,0,0,.4)',
                }}
              >
                🏆 Congratulations on completing {anniversaryYears} {anniversaryYears === 1 ? 'year' : 'years'} with us!
              </motion.div>
            )}

            <motion.p
              variants={itemVariants}
              style={{ margin: 0, fontSize: 'clamp(13px, 2.2vw, 15px)', color: 'rgba(255,255,255,.82)' }}
            >
              Wishing you a wonderful year ahead!
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
