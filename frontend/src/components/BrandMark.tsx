import logoUrl from '../assets/nforce-logo.png';

interface BrandMarkProps {
  size?: 'sm' | 'lg';
}

const dimensions = {
  sm: { px: 34, ring: 34, glow: 52 },
  lg: { px: 96, ring: 96, glow: 140 },
} as const;

export function BrandMark({ size = 'sm' }: BrandMarkProps) {
  const { px, glow } = dimensions[size];

  return (
    <span
      role="img"
      aria-label="NForce One logo"
      style={{
        position: 'relative',
        display: 'inline-flex',
        alignItems: 'center',
        justifyContent: 'center',
        width: px,
        height: px,
        flexShrink: 0,
      }}
    >
      <span
        aria-hidden="true"
        style={{
          position: 'absolute',
          inset: 0,
          margin: 'auto',
          width: glow,
          height: glow,
          borderRadius: '50%',
          background: 'var(--bm-glow)',
          animation: 'glow-pulse 2.4s ease-in-out infinite',
          pointerEvents: 'none',
        }}
      />
      <span
        style={{
          position: 'relative',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          width: px,
          height: px,
          borderRadius: '50%',
          border: '1px solid var(--bm-ring)',
          boxShadow: 'var(--bm-inner-shadow)',
          overflow: 'hidden',
        }}
      >
        <img
          src={logoUrl}
          alt=""
          aria-hidden="true"
          width={px}
          height={px}
          style={{ display: 'block', borderRadius: '50%', objectFit: 'cover' }}
        />
      </span>
    </span>
  );
}
