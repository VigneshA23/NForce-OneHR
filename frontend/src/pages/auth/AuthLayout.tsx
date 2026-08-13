import { motion, useReducedMotion } from 'framer-motion';
import { BrandMark } from '../../components/BrandMark';

interface StreakDef { top: string; width: number; opacity: number; duration: number; delay: number; }

const STREAKS: StreakDef[] = [
  { top:  '6.0%', width: 180, opacity: 0.28, duration: 11, delay:   0 },
  { top: '10.5%', width: 120, opacity: 0.20, duration: 13, delay:  -4 },
  { top: '15.0%', width: 240, opacity: 0.32, duration:  9, delay:  -8 },
  { top: '19.5%', width:  95, opacity: 0.16, duration: 14, delay:  -3 },
  { top: '24.0%', width: 275, opacity: 0.25, duration: 10, delay:  -7 },
  { top: '28.5%', width: 145, opacity: 0.22, duration: 12, delay:  -2 },
  { top: '33.0%', width: 200, opacity: 0.30, duration:  8, delay: -11 },
  { top: '37.5%', width: 115, opacity: 0.17, duration: 15, delay:  -5 },
  { top: '42.0%', width: 255, opacity: 0.24, duration: 11, delay:  -9 },
  { top: '46.5%', width: 160, opacity: 0.21, duration: 13, delay:  -1 },
  { top: '51.0%', width: 210, opacity: 0.27, duration:  9, delay:  -6 },
  { top: '55.5%', width: 130, opacity: 0.19, duration: 12, delay: -10 },
  { top: '60.0%', width: 175, opacity: 0.23, duration: 10, delay:  -4 },
  { top: '64.5%', width: 105, opacity: 0.15, duration: 14, delay:  -8 },
];

function SpeedStreaks() {
  const reduced = useReducedMotion();
  if (reduced) {
    return (
      <div aria-hidden="true" style={{ position: 'absolute', inset: 0 }}>
        {STREAKS.map((s, i) => (
          <div key={i} style={{ position: 'absolute', top: s.top, right: 0, width: s.width, height: 2,
            background: 'linear-gradient(90deg, transparent, var(--brand-bright))', borderRadius: 2, opacity: s.opacity }} />
        ))}
      </div>
    );
  }
  return (
    <div aria-hidden="true" style={{ position: 'absolute', inset: 0, overflow: 'hidden' }}>
      {STREAKS.map((s, i) => (
        <motion.div key={i}
          style={{ position: 'absolute', top: s.top, left: -(s.width + 60), width: s.width, height: 2,
            background: 'linear-gradient(90deg, transparent, var(--brand-bright))', borderRadius: 2 }}
          animate={{ x: [0, 1800] }}
          transition={{ duration: s.duration, delay: s.delay, repeat: Infinity, ease: 'linear', repeatDelay: 0 }}
          initial={{ opacity: s.opacity }}
        />
      ))}
    </div>
  );
}

const CAPABILITIES = [
  { label: 'Role-based access',      detail: 'Secure access by responsibility' },
  { label: 'Full audit trail',       detail: 'Every action tracked'            },
  { label: 'Policy-driven workflows', detail: 'Standardised HR processes'      },
] as const;

interface AuthLayoutProps {
  leftHeadline?: string;
  leftSubtext?: string;
  showStats?: boolean;
  children: React.ReactNode;
}

const panelGradient = [
  'radial-gradient(120% 100% at 80% 10%, rgba(177,17,22,.26) 0%, transparent 55%)',
  'linear-gradient(160deg, #0a0b0e 0%, #12141a 100%)',
].join(', ');

export function AuthLayout({ leftHeadline, leftSubtext, showStats = false, children }: AuthLayoutProps) {
  return (
    <div data-theme="dark" style={{ display: 'grid', gridTemplateColumns: '55fr 45fr', minHeight: '100dvh' }} className="max-[900px]:block">
      <div className="max-[900px]:hidden" style={{ position: 'relative', overflow: 'hidden', background: panelGradient, display: 'flex', flexDirection: 'column', justifyContent: 'space-between', padding: '44px 48px' }}>
        <SpeedStreaks />
        <div style={{ position: 'relative', display: 'flex', alignItems: 'center', gap: 14 }}>
          <BrandMark size="lg" />
          <div>
            <div style={{ fontFamily: '"Space Grotesk", sans-serif', fontWeight: 700, fontSize: 18, letterSpacing: '0.04em', color: 'var(--txt)' }}>
              NForce OneHR
            </div>
            <div style={{ fontSize: 10, letterSpacing: '0.14em', textTransform: 'uppercase', color: 'var(--txt-dim)', marginTop: 3 }}>
              Built for modern people teams
            </div>
          </div>
        </div>

        {leftHeadline && (
          <div style={{ position: 'relative', maxWidth: 420 }}>
            <h2 style={{ fontFamily: '"Space Grotesk", sans-serif', fontSize: 36, fontWeight: 700, lineHeight: 1.1, letterSpacing: '-0.02em', color: 'var(--txt)', marginBottom: 16 }}>
              {leftHeadline}
            </h2>
            {leftSubtext && (
              <p style={{ fontSize: 14, lineHeight: 1.65, color: 'var(--txt-mut)', maxWidth: 380 }}>{leftSubtext}</p>
            )}
          </div>
        )}

        {showStats ? (
          <div style={{ position: 'relative', borderTop: '1px solid rgba(255,255,255,0.08)', paddingTop: 24, display: 'flex' }}>
            {CAPABILITIES.map((cap, i) => (
              <div key={cap.label} style={{
                flex: 1,
                paddingRight: i < CAPABILITIES.length - 1 ? 24 : 0,
                paddingLeft: i > 0 ? 24 : 0,
                borderLeft: i > 0 ? '1px solid rgba(255,255,255,0.08)' : 'none',
              }}>
                <div style={{ width: 6, height: 6, borderRadius: '50%', background: 'var(--brand-bright)', marginBottom: 10 }} />
                <div style={{ fontSize: 12, fontWeight: 600, color: 'var(--txt)', letterSpacing: '0.01em', lineHeight: 1.3, marginBottom: 4 }}>{cap.label}</div>
                <div style={{ fontSize: 10, letterSpacing: '0.06em', color: 'var(--txt-dim)', lineHeight: 1.4 }}>{cap.detail}</div>
              </div>
            ))}
          </div>
        ) : <div />}
      </div>

      <div style={{ background: 'var(--panel)', display: 'flex', alignItems: 'center', justifyContent: 'center', padding: '40px 32px', minHeight: '100dvh' }}>
        <div className="hidden max-[900px]:flex" style={{ position: 'absolute', top: 0, left: 0, right: 0, height: 80, background: panelGradient, alignItems: 'center', justifyContent: 'center', gap: 10, borderBottom: '1px solid var(--line)' }}>
          <BrandMark size="sm" />
          <span style={{ fontFamily: '"Space Grotesk", sans-serif', fontWeight: 700, fontSize: 15, letterSpacing: '0.03em' }}>
            NForce OneHR
          </span>
        </div>
        <div className="max-[900px]:mt-20" style={{ width: '100%', maxWidth: 440 }}>
          {children}
        </div>
      </div>
    </div>
  );
}
