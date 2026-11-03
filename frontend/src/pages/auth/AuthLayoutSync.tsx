// Login-only RIGHT-SIDE container. Visual port of the NForce Sync login container
// (glass panel + dot grid + glow + 420px card). Presentation only — the left side of the
// login page stays the existing OneHR design in Login.tsx.
// AuthLayout.tsx (shared with Forgot Password) is intentionally left untouched.
import { motion, useReducedMotion } from 'framer-motion';

interface SyncLoginPanelProps {
  children: React.ReactNode;
}

export function SyncLoginPanel({ children }: SyncLoginPanelProps) {
  const reduced = useReducedMotion();

  return (
    <div
      className="nfs-auth-panel"
      style={{
        position: 'relative',
        zIndex: 2,
        // No panel background, border or shadow: the page's own dark environment continues
        // straight through, so the artwork and this panel read as one screen.
        background: 'linear-gradient(135deg, #f5f6f9 0%, #e7eaf0 100%)',
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        minHeight: '100dvh',
        overflow: 'hidden',
        // Breathing space on all four sides of the card, and the scale unit the card is drawn in.
        // --m / --mx: vertical / horizontal margin around the card (responsive, never touching an edge).
        // --u: 1px on roomy windows; shrinks smoothly so card + margins always fit the panel
        //      (panel = 35vw wide on desktop, card design size = 420 x 560).
        ['--m' as string]: 'max(16px, 5dvh)',
        ['--mx' as string]: 'max(32px, 3.6vw)',
        // The artwork frame width, exactly as LoginArtwork computes it (also overridden there for wide windows).
        ['--nf-art-w' as string]: 'min(100vw, calc(100dvh * 1671 / 941))',
        // --u follows the artwork's scale (1px at the 1440x900 design point) and is only reduced when the card
        // + margins would not fit the panel. It has a lower floor (readability) but NO upper limit, so the card
        // keeps scaling with the artwork at every zoom level / viewport size.
        ['--u' as string]: 'max(0.78px, min(1.3px, calc((100dvh - 2 * var(--m)) / 560), calc((38vw - 2 * var(--mx)) / 420)))',
        padding: 'var(--m) var(--mx)',
        minWidth: 0,
        boxSizing: 'border-box',
        fontFamily: 'Inter, "Segoe UI", sans-serif',
      }}
    >
      {/* Form card */}
      <motion.div
        initial={reduced ? false : { opacity: 0, y: 16 }}
        animate={{ opacity: 1, y: 0 }}
        transition={reduced ? { duration: 0 } : { duration: 0.5, ease: [0.25, 0.46, 0.45, 0.94] }}
        style={{
          position: 'relative', zIndex: 1,
          width: '100%', maxWidth: 'calc(420 * var(--u))',
          background: '#ffffff',
          border: '1px solid rgba(20,24,40,0.06)',
          borderTop: '3px solid #c8141b',
          borderRadius: 'calc(20 * var(--u))',
          padding: 'calc(40 * var(--u)) calc(36 * var(--u))',
          boxShadow: '0 20px 50px rgba(20,24,40,0.14), 0 2px 8px rgba(20,24,40,0.06)',
        }}
      >
        {children}
      </motion.div>

      <style>{`
        /* ── Input: gradient border wrapper ── */
        .nfs-input-wrap {
          position: relative;
          border-radius: calc(9 * var(--u));
          isolation: isolate;
        }
        .nfs-input-wrap::before {
          content: '';
          position: absolute;
          inset: 0;
          border-radius: inherit;
          padding: 1px;
          background: rgba(20,24,40,0.16);
          -webkit-mask:
            linear-gradient(#fff 0 0) content-box,
            linear-gradient(#fff 0 0);
          -webkit-mask-composite: xor;
          mask-composite: exclude;
          pointer-events: none;
          transition: background 0.3s;
          z-index: 2;
        }
        .nfs-input-wrap:focus-within::before {
          background: linear-gradient(
            120deg,
            rgba(228,55,61,0.9)  0%,
            rgba(255,130,130,0.7) 20%,
            rgba(177,17,22,0.5)  45%,
            rgba(255,100,100,0.8) 65%,
            rgba(228,55,61,0.9)  100%
          );
          background-size: 250% 250%;
          animation: nfs-border-flow 2.8s ease infinite;
        }
        @keyframes nfs-border-flow {
          0%   { background-position: 0%   50%; }
          50%  { background-position: 100% 50%; }
          100% { background-position: 0%   50%; }
        }

        /* ── Input inner field ── */
        .nfs-input-inner {
          display: block;
          width: 100%;
          background: #f6f7f9;
          border: none;
          border-radius: calc(8 * var(--u));
          padding: calc(12 * var(--u)) calc(14 * var(--u));
          color: #15161a;
          font-size: calc(14 * var(--u));
          font-family: Inter, "Segoe UI", sans-serif;
          outline: none;
          box-sizing: border-box;
          box-shadow: none;
          transition: background 0.25s, box-shadow 0.25s;
          position: relative;
          z-index: 1;
        }
        .nfs-input-inner:focus {
          background: #ffffff;
          box-shadow: none;
        }
        .nfs-input-inner:disabled {
          opacity: 0.55;
          cursor: not-allowed;
        }
        /* Email + password fields stay BLACK in every state — empty, focused, typing, typed, autofilled.
           Autofilled fields keep white text on the same black field (never the browser's light autofill colour). */
        .nfs-input-inner:-webkit-autofill,
        .nfs-input-inner:-webkit-autofill:hover,
        .nfs-input-inner:-webkit-autofill:focus,
        .nfs-input-inner:-webkit-autofill:active {
          -webkit-text-fill-color: #15161a;
          caret-color: #15161a;
          -webkit-box-shadow: 0 0 0 1000px #f6f7f9 inset;
          box-shadow: 0 0 0 1000px #f6f7f9 inset;
          background-color: #f6f7f9 !important;
          transition: background-color 9999s ease-out 0s;
        }
        /* The eye is a bare GREY icon: transparent button, no box / border / shadow (focus ring for keyboard users only). */
        .nfs-eye { color: #9CA3AF !important; background: transparent !important; border: 0 !important; box-shadow: none !important; }
        .nfs-eye:focus-visible { outline: 2px solid rgba(228,55,61,0.85); outline-offset: 1px; }
        .nfs-input-inner::placeholder {
          color: rgba(20,24,40,0.38);
        }

        /* ── Label brightens on focus ── */
        .nfs-field:focus-within .nfs-label {
          color: rgba(228,55,61,0.85) !important;
        }

        /* ── Card border breathing glow ── */
        @keyframes nfs-card-glow {
          0%, 100% {
            box-shadow:
              inset 0 1px 0 rgba(255,255,255,0.12),
              0 0 0 calc(1 * var(--u)) rgba(228,55,61,0.10),
              0 0 calc(26 * var(--u)) rgba(228,55,61,0.20),
              0 0 calc(60 * var(--u)) rgba(120,70,220,0.10),
              0 calc(24 * var(--u)) calc(64 * var(--u)) rgba(0,0,0,0.40);
          }
          50% {
            box-shadow:
              inset 0 1px 0 rgba(255,255,255,0.16),
              0 0 0 calc(1 * var(--u)) rgba(228,55,61,0.22),
              0 0 calc(34 * var(--u)) rgba(228,55,61,0.30),
              0 0 calc(72 * var(--u)) rgba(120,70,220,0.14),
              0 calc(24 * var(--u)) calc(64 * var(--u)) rgba(0,0,0,0.44);
          }
        }

        /* ── Submit button shimmer sweep ── */
        @keyframes nfs-shimmer {
          0%   { transform: translateX(-130%) skewX(-18deg); }
          100% { transform: translateX(230%)  skewX(-18deg); }
        }
        .nfs-submit-btn {
          position: relative;
          overflow: hidden;
        }
        .nfs-submit-btn::after {
          content: '';
          position: absolute;
          top: 0; left: 0; width: 60%; height: 100%;
          background: linear-gradient(90deg, transparent 0%, rgba(255,255,255,0.22) 50%, transparent 100%);
          transform: translateX(-130%) skewX(-18deg);
          pointer-events: none;
        }
        .nfs-submit-btn:not(:disabled):hover::after {
          animation: nfs-shimmer 0.6s ease-out forwards;
        }

        @media (prefers-reduced-motion: reduce) {
          .nfs-input-wrap:focus-within::before { animation: none; }
          .nfs-submit-btn::after { display: none; }
        }
        @media (max-width: 1024px) {
          .nfs-auth-panel { --u: 1px !important; --m: 40px !important; --mx: clamp(16px, 5vw, 32px) !important; }
        }
      `}</style>
    </div>
  );
}
