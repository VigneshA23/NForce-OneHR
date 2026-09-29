import { useEffect, useState } from 'react';
import { useAuthStore } from '../store/authStore';
import { profileApi } from '../api/profile';
import nf1Logo from '../assets/nforce-logo.png';
import heroPersonMale from '../assets/hero-person-male.png';
import heroPersonFemale from '../assets/hero-person-female.png';
import heroMaskMale from '../assets/hero-person-male-accent-mask.png';
import heroMaskFemale from '../assets/hero-person-female-accent-mask.png';

// ── Gender-aware illustration for the right side of AttendanceHeroBanner ─────────
// Gender comes from the existing GET /api/profile (ProfileData.gender) — no new backend field.
// Anything other than a clear Male/Female value (null, "Non-binary", "Prefer not to say") gets
// the person-free workspace scene rather than a guessed figure.
//
// Male/female are an IMAGE-ASSET SWAP, not generated artwork: hero-person-male.png and
// hero-person-female.png are used exactly as supplied (see PersonPhoto below) — never redrawn,
// recolored, or reshaped. Only the calendar/shift-timing text is live, overlaid on top of the
// image so it can reflect the viewer's own assigned shift instead of the image's baked-in time.

export type HeroIllustrationVariant = 'female' | 'male' | 'neutral';

function variantFromGender(gender: string | null | undefined): HeroIllustrationVariant {
  const g = (gender ?? '').trim().toLowerCase();
  if (g === 'female' || g === 'f' || g === 'woman') return 'female';
  if (g === 'male' || g === 'm' || g === 'man') return 'male';
  return 'neutral';
}

// One profile read per session token — the banner re-mounts on every dashboard visit and on
// each of its own state changes, so this avoids a /api/profile call each time.
let cached: { token: string; promise: Promise<HeroIllustrationVariant> } | null = null;

function loadVariant(token: string): Promise<HeroIllustrationVariant> {
  if (!cached || cached.token !== token) {
    cached = {
      token,
      promise: profileApi.get(token).then(p => variantFromGender(p.gender)).catch(() => 'neutral' as const),
    };
  }
  return cached.promise;
}

// The exact uploaded employee image. It's drawn at its OWN aspect ratio (full hero height,
// width auto), anchored to the card's right edge — never cover-cropped to the flex box's shape.
// Cropping was what produced the visible vertical seam: on narrower cards the image's dark
// lead-in (its left ~15%, which already blends from dark into the red artwork) got cut off, so
// the box's left edge landed on bright red. It also cut the plant/pot off the right. Uncropped,
// the image may extend left past its flex box (under the text column's dark area — see
// .nf-hero-illus in index.css for the stacking), and only that dark lead-in is faded out with a
// smootherstep curve (zero slope at both ends, so no Mach-band "edge" where the fade starts).
// The calendar card (~17%+) is ≥98% opaque; laptop, person, cup, plant and chart
// (30%+) are fully opaque.
const EDGE_FADE_MASK =
  'linear-gradient(to right, rgba(0,0,0,0) 0%, rgba(0,0,0,.016) 2.5%, rgba(0,0,0,.104) 5%, rgba(0,0,0,.275) 7.5%, '
  + 'rgba(0,0,0,.5) 10%, rgba(0,0,0,.725) 12.5%, rgba(0,0,0,.896) 15%, rgba(0,0,0,.984) 17.5%, #000 20%, #000 98%, rgba(0,0,0,0) 100%)';

// Per-image geometry of the calendar card's text slot (fractions of the image), where the live
// shift label is drawn — the images' own baked "9:00 AM - 5:00 PM" text was removed so it can't
// contradict the employee's real shift.
//
// Theme color: the PNGs' red BACKDROP (leaves, glow, glass-card tint, vignette) follows the User
// Preferences accent via an overlay of var(--brand) with mix-blend-mode: hue — hue only, so the
// artwork keeps its own shading/saturation, and Red maps back to ~the original. The overlay is
// clipped by a per-image accent mask (hero-person-*-accent-mask.png, alpha = backdrop) that
// excludes the person (face, hair, skin, the female's red jacket), laptop, cup, both NF1 logos,
// plant/pot and chart bars. The employee PNG itself is never modified.
const PHOTO = {
  female: { src: heroPersonFemale, mask: heroMaskFemale, aspect: 935 / 308, x: 0.275, w: 0.18, todayBase: 0.243, timeBase: 0.334, font: 5.5 },
  male:   { src: heroPersonMale,   mask: heroMaskMale,   aspect: 874 / 277, x: 0.281, w: 0.19, todayBase: 0.253, timeBase: 0.345, font: 6.1 },
} as const;

function PersonPhoto({ variant, shiftLabel }: { variant: 'male' | 'female'; shiftLabel?: string | null }) {
  const p = PHOTO[variant];
  const label = shiftLabel ?? '—';
  const line = (base: number, extra: React.CSSProperties, scale = 1): React.CSSProperties => ({
    position: 'absolute', left: `${p.x * 100}%`, maxWidth: `${p.w * 100}%`, top: `calc(${base * 100}% - 0.86em)`,
    fontSize: `${p.font * scale}cqh`, lineHeight: 1, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis',
    fontFamily: 'Inter, system-ui, sans-serif', ...extra,
  });
  const imgW = `(100cqh * ${p.aspect})`;
  return (
    // Visible window: natural aspect at full height, capped at 130% of the box on very narrow
    // cards. The inner layer is always the full image; when capped it's shifted so the crop comes
    // from the dark left side (85/15 split), never the plant — and the label moves with it.
    <div
      className="nf-hero-illus-fig"
      style={{
        position: 'absolute', top: 0, right: 0, height: '100%', aspectRatio: p.aspect, maxWidth: '130%',
        overflow: 'hidden', containerType: 'size',
        WebkitMaskImage: EDGE_FADE_MASK, maskImage: EDGE_FADE_MASK,
        WebkitMaskRepeat: 'no-repeat', maskRepeat: 'no-repeat',
        WebkitMaskSize: '100% 100%', maskSize: '100% 100%',
      }}
    >
      <div style={{ position: 'absolute', top: 0, height: '100%', width: `calc${imgW}`, right: `calc((100cqw - ${imgW}) * 0.15)` }}>
        <img src={p.src} alt="" aria-hidden="true" style={{ display: 'block', width: '100%', height: '100%' }} />
        <div
          aria-hidden="true"
          style={{
            position: 'absolute', inset: 0, background: 'var(--brand)', mixBlendMode: 'hue',
            WebkitMaskImage: `url(${p.mask})`, maskImage: `url(${p.mask})`,
            WebkitMaskSize: '100% 100%', maskSize: '100% 100%',
            WebkitMaskRepeat: 'no-repeat', maskRepeat: 'no-repeat',
          }}
        />
        <span style={line(p.todayBase, { fontWeight: 500, color: 'rgba(255,255,255,.80)' })}>Today</span>
        {/* Longest real labels ("10:30 PM - 12:30 AM") still fit the card at 0.88x. */}
        <span style={line(p.timeBase, { fontWeight: 600, color: '#FFFFFF' }, label.length > 17 ? 0.88 : 1)}>{label}</span>
      </div>
    </div>
  );
}

// ── Fallback scene (no clear gender on the profile) — unchanged from the existing behavior. ──

function Workspace() {
  return (
    <g className="nf-hero-illus-fig">
      {/* Laptop facing the viewer with a mini dashboard */}
      <rect x={146} y={78} width={104} height={70} rx={5} fill="#1B1D26" />
      <rect x={151} y={83} width={94} height={60} rx={2} fill="url(#nfHeroScreen)" />
      <circle cx={174} cy={113} r={12} fill="none" stroke="#E5E7EB" strokeWidth={5} />
      <circle
        cx={174} cy={113} r={12} fill="none" stroke="#B11116" strokeWidth={5}
        strokeDasharray="52 75.4" transform="rotate(-90 174 113)" strokeLinecap="round"
      />
      <image href={nf1Logo} xlinkHref={nf1Logo} x={166} y={105} width={16} height={16} clipPath="url(#nfHeroScreenLogoClip)" preserveAspectRatio="xMidYMid slice" />
      <rect x={196} y={92} width={38} height={4} rx={2} fill="#C9CDD6" />
      <rect x={196} y={124 - 10} width={6} height={10} rx={1.5} fill="#D6D9E0" />
      <rect x={205} y={124 - 18} width={6} height={18} rx={1.5} fill="#D6D9E0" />
      <rect x={214} y={124 - 13} width={6} height={13} rx={1.5} fill="#D6D9E0" />
      <rect x={223} y={124 - 24} width={6} height={24} rx={1.5} fill="#B11116" />
      <rect x={196} y={130} width={30} height={3} rx={1.5} fill="#E1E4EA" />
      <path d="M136 148 L260 148 L268 155 L128 155 Z" fill="#B7BBC6" />
      <rect x={186} y={148} width={24} height={2.5} rx={1.2} fill="#9CA1AD" />
    </g>
  );
}

function FallbackScene({ shiftLabel }: { shiftLabel?: string | null }) {
  return (
    <svg viewBox="0 0 320 180" width="100%" height="100%" preserveAspectRatio="xMidYMid slice" aria-hidden="true" focusable="false" style={{ display: 'block' }}>
      <defs>
        <linearGradient id="nfHeroBg" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0%" stopColor="#2A080A" />
          <stop offset="55%" stopColor="#7E1015" />
          <stop offset="100%" stopColor="#C0252A" />
        </linearGradient>
        <radialGradient id="nfHeroGlow" cx="0.5" cy="0.5" r="0.5">
          <stop offset="0%" stopColor="#FF9A88" stopOpacity={0.42} />
          <stop offset="100%" stopColor="#FF9A88" stopOpacity={0} />
        </radialGradient>
        <linearGradient id="nfHeroDesk" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor="#3A0C0F" />
          <stop offset="100%" stopColor="#1A0506" />
        </linearGradient>
        <linearGradient id="nfHeroScreen" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor="#FFFFFF" />
          <stop offset="100%" stopColor="#F1F2F5" />
        </linearGradient>
        <clipPath id="nfHeroScreenLogoClip"><circle cx={174} cy={113} r={8} /></clipPath>
        <clipPath id="nfHeroMugLogoClip"><circle cx={114} cy={143.5} r={6.2} /></clipPath>
      </defs>

      {/* Backdrop */}
      <rect width={320} height={180} fill="url(#nfHeroBg)" />
      <path d="M-20 180 L120 -10 L190 -10 L50 180 Z" fill="#FFFFFF" opacity={0.04} />
      <circle cx={200} cy={78} r={96} fill="url(#nfHeroGlow)" />
      <circle cx={196} cy={66} r={58} fill="none" stroke="#FFFFFF" strokeOpacity={0.09} />
      <circle cx={196} cy={66} r={76} fill="none" stroke="#FFFFFF" strokeOpacity={0.05} />
      <circle cx={132} cy={24} r={1.4} fill="#FFFFFF" opacity={0.55} />
      <circle cx={292} cy={40} r={1.1} fill="#FFFFFF" opacity={0.45} />
      <circle cx={262} cy={16} r={0.9} fill="#FFFFFF" opacity={0.4} />

      {/* Calendar / shift-timing card — reflects the employee's own assigned shift
          (AttendanceConfig.shiftStart/shiftEnd, the same source the banner's own
          "Shift starts at…" line uses), never a hardcoded time. */}
      <g>
        <rect x={16} y={24} width={96} height={38} rx={8} fill="#FFFFFF" fillOpacity={0.1} stroke="#FFFFFF" strokeOpacity={0.18} />
        <rect x={27} y={34} width={16} height={15} rx={2.5} fill="none" stroke="#FFFFFF" strokeOpacity={0.85} strokeWidth={1.4} />
        <rect x={27} y={34} width={16} height={4} rx={2} fill="#FF8A80" />
        <line x1={31} y1={32} x2={31} y2={36} stroke="#FFFFFF" strokeOpacity={0.85} strokeWidth={1.2} strokeLinecap="round" />
        <line x1={39} y1={32} x2={39} y2={36} stroke="#FFFFFF" strokeOpacity={0.85} strokeWidth={1.2} strokeLinecap="round" />
        <circle cx={31.5} cy={42} r={1.1} fill="#FFFFFF" fillOpacity={0.6} />
        <circle cx={35.5} cy={42} r={1.1} fill="#FFFFFF" fillOpacity={0.6} />
        <circle cx={31.5} cy={45.5} r={1.1} fill="#FFFFFF" fillOpacity={0.6} />
        <text x={49} y={39} fontSize={5.5} fontWeight={700} letterSpacing={0.6} fill="#FFFFFF" fillOpacity={0.7} style={{ fontFamily: '"Space Grotesk", sans-serif' }}>
          TODAY
        </text>
        <text x={49} y={49} fontSize={6.4} fontWeight={700} fill="#FFFFFF" fillOpacity={0.94} style={{ fontFamily: '"Space Grotesk", sans-serif' }}>
          {shiftLabel ?? '—'}
        </text>
      </g>
      <g>
        <rect x={28} y={74} width={88} height={54} rx={8} fill="#FFFFFF" fillOpacity={0.08} stroke="#FFFFFF" strokeOpacity={0.15} />
        <rect x={40} y={84} width={36} height={4} rx={2} fill="#FFFFFF" fillOpacity={0.55} />
        <rect x={40} y={106} width={8} height={12} rx={2} fill="#FFFFFF" fillOpacity={0.3} />
        <rect x={54} y={100} width={8} height={18} rx={2} fill="#FFFFFF" fillOpacity={0.3} />
        <rect x={68} y={103} width={8} height={15} rx={2} fill="#FFFFFF" fillOpacity={0.3} />
        <rect x={82} y={96} width={8} height={22} rx={2} fill="#FFFFFF" fillOpacity={0.3} />
        <rect x={96} y={90} width={8} height={28} rx={2} fill="#FF8A80" />
      </g>

      {/* Plant */}
      <g>
        <path d="M284 128 C270 112 266 96 272 84 C282 96 286 112 284 128 Z" fill="#5C0F13" />
        <path d="M286 128 C284 108 290 90 302 82 C306 100 298 118 286 128 Z" fill="#8A161B" />
        <path d="M284 130 C276 120 262 116 252 120 C260 130 274 134 284 130 Z" fill="#6E1216" />
        <path d="M288 130 C296 118 308 114 316 116 C312 128 300 134 288 130 Z" fill="#A51C21" />
        <rect x={274} y={128} width={24} height={26} rx={4} fill="#2A0709" />
        <rect x={274} y={128} width={24} height={4} rx={2} fill="#FFFFFF" fillOpacity={0.08} />
      </g>

      {/* Desk */}
      <rect x={0} y={154} width={320} height={26} fill="url(#nfHeroDesk)" />
      <rect x={0} y={154} width={320} height={1} fill="#FFFFFF" fillOpacity={0.1} />

      {/* Mug — NF1 logo printed on the body, in place of a flat brand stripe */}
      <g>
        <rect x={106} y={134} width={16} height={20} rx={3} fill="#F3E3DA" />
        <path d="M122 139 Q129 139 129 144 Q129 149 122 149" fill="none" stroke="#F3E3DA" strokeWidth={2.4} />
        <image href={nf1Logo} xlinkHref={nf1Logo} x={107.5} y={137} width={13} height={13} clipPath="url(#nfHeroMugLogoClip)" preserveAspectRatio="xMidYMid slice" />
      </g>

      <Workspace />
    </svg>
  );
}

// ── Root: picks the exact photo asset for a clear gender, else the existing fallback scene. ──

export function HeroIllustrationArt({
  variant,
  shiftLabel,
}: {
  variant: HeroIllustrationVariant | null;
  shiftLabel?: string | null;
}) {
  if (variant === 'female' || variant === 'male') {
    return <PersonPhoto variant={variant} shiftLabel={shiftLabel} />;
  }
  return <FallbackScene shiftLabel={shiftLabel} />;
}

export function HeroIllustration({ shiftLabel }: { shiftLabel?: string | null }) {
  const token = useAuthStore(s => s.token) ?? '';
  // null while the profile read is in flight — the backdrop renders immediately and the figure
  // fades in once the variant is known, so there's no neutral→person flash.
  const [variant, setVariant] = useState<HeroIllustrationVariant | null>(null);

  useEffect(() => {
    let alive = true;
    if (!token) { setVariant('neutral'); return; }
    loadVariant(token).then(v => { if (alive) setVariant(v); });
    return () => { alive = false; };
  }, [token]);

  return (
    <div className="nf-hero-illus" data-variant={variant ?? 'pending'}>
      <HeroIllustrationArt variant={variant} shiftLabel={shiftLabel} />
    </div>
  );
}
