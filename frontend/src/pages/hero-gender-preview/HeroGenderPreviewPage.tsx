/**
 * ============================================================================
 *  MOCK / VISUAL PREVIEW — NOT CONNECTED TO ANY BACKEND
 * ============================================================================
 *  Design-review preview of the Dashboard Hero's new Non-binary variant,
 *  alongside the existing Male/Female/Fallback ones for direct comparison.
 *  Renders the REAL `HeroIllustrationArt` component (components/
 *  HeroIllustration.tsx) — the exact function AttendanceHeroBanner.tsx
 *  mounts on the actual dashboard — inside a static, non-functional visual
 *  replica of the app shell (sidebar/topbar/greeting), so the feature can be
 *  reviewed "in place" without logging in.
 *
 *  - No network requests, no auth/session state, no import of the real
 *    AttendanceHeroBanner.tsx, DashboardPage.tsx, api/profile.ts's network
 *    calls, or Shell.tsx. `?gender=` picks the mock variant directly —
 *    profileApi.get() is never called.
 *  - `?gender=male|female|non-binary|fallback` (default: non-binary, since
 *    that's the variant under review). Theme Color and Mock Assigned Shift
 *    are live toggle controls on the page itself, so every combination can
 *    be checked without re-navigating.
 * ============================================================================
 */
import { useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { Home, Users, Clock, Calendar, HelpCircle, Bell, Search } from 'lucide-react';
import logoUrl from '../../assets/nforce-logo.png';
import { HeroIllustrationArt, type HeroIllustrationVariant } from '../../components/HeroIllustration';

type MockGender = 'male' | 'female' | 'non-binary' | 'fallback';
const GENDER_TO_VARIANT: Record<MockGender, HeroIllustrationVariant> = {
  male: 'male', female: 'female', 'non-binary': 'non-binary', fallback: 'neutral',
};
const GENDER_OPTIONS: MockGender[] = ['male', 'female', 'non-binary', 'fallback'];

const ACCENTS: Record<string, { '--brand': string; '--brand-bright': string; '--brand-deep': string }> = {
  Red:    { '--brand': '#B11116', '--brand-bright': '#E4373D', '--brand-deep': '#7A0C10' },
  Purple: { '--brand': '#6D28D9', '--brand-bright': '#9F67F5', '--brand-deep': '#4C1D95' },
  Blue:   { '--brand': '#1D4ED8', '--brand-bright': '#3B82F6', '--brand-deep': '#1E3A8A' },
  Green:  { '--brand': '#15803D', '--brand-bright': '#22C55E', '--brand-deep': '#14532D' },
  Pink:   { '--brand': '#BE185D', '--brand-bright': '#EC4899', '--brand-deep': '#831843' },
};

const SHIFT_OPTIONS = [
  { start: '9:00 AM', end: '5:00 PM' },
  { start: '10:30 AM', end: '7:30 PM' },
  { start: '2:00 PM', end: '11:00 PM' },
  { start: '10:00 PM', end: '7:00 AM' },
  { start: '10:30 PM', end: '12:30 AM' },
];

function ToggleButton({ active, onClick, children }: { active: boolean; onClick: () => void; children: React.ReactNode }) {
  return (
    <button
      onClick={onClick}
      style={{
        padding: '7px 14px', borderRadius: 8, fontSize: 12.5, fontWeight: 700, cursor: 'pointer',
        background: active ? 'color-mix(in srgb, var(--brand) 18%, transparent)' : '#15171c',
        border: `1px solid ${active ? 'var(--brand)' : '#23262f'}`,
        color: active ? 'var(--brand-bright)' : '#9CA3AF',
      }}
    >
      {children}
    </button>
  );
}

function NavRow({ icon: Icon, label, active }: { icon: typeof Home; label: string; active?: boolean }) {
  return (
    <div style={{
      display: 'flex', alignItems: 'center', gap: 10, padding: '9px 14px', borderRadius: 8,
      background: active ? 'color-mix(in srgb, var(--brand) 14%, transparent)' : 'transparent',
      color: active ? 'var(--brand-bright)' : '#9CA3AF', fontSize: 13, fontWeight: active ? 700 : 500,
    }}>
      <Icon size={16} aria-hidden="true" />
      {label}
    </div>
  );
}

export default function HeroGenderPreviewPage() {
  const [params] = useSearchParams();
  const requested = params.get('gender');
  const initialGender: MockGender = (GENDER_OPTIONS as string[]).includes(requested ?? '') ? (requested as MockGender) : 'non-binary';

  const [gender, setGender] = useState<MockGender>(initialGender);
  const [accent, setAccent] = useState<keyof typeof ACCENTS>('Red');
  const [shiftIdx, setShiftIdx] = useState(0);

  const variant = GENDER_TO_VARIANT[gender];
  const shift = SHIFT_OPTIONS[shiftIdx];
  const shiftLabel = `${shift.start} - ${shift.end}`;

  return (
    <div style={{ ...ACCENTS[accent], display: 'flex', minHeight: '100vh', background: '#0a0b0e', fontFamily: 'Inter, sans-serif' } as React.CSSProperties}>
      <div style={{ width: 220, flexShrink: 0, background: '#0d0e12', borderRight: '1px solid #1c1e24', padding: '18px 12px', display: 'flex', flexDirection: 'column', gap: 2 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '4px 10px 18px' }}>
          <img src={logoUrl} alt="" style={{ width: 26, height: 26, borderRadius: 6 }} />
          <div>
            <div style={{ fontSize: 13, fontWeight: 800, color: '#E8EAED' }}>NForce OneHR</div>
            <div style={{ fontSize: 9.5, color: '#6B7280', letterSpacing: '.04em' }}>EMPLOYEE EXPERIENCE</div>
          </div>
        </div>
        <NavRow icon={Home} label="Home" active />
        <NavRow icon={Users} label="My Team" />
        <NavRow icon={Clock} label="My Attendance" />
        <NavRow icon={Calendar} label="Leave & Holidays" />
        <NavRow icon={Users} label="My Profile" />
        <NavRow icon={HelpCircle} label="Help & Guidance" />
      </div>

      <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column' }}>
        <div style={{ height: 56, flexShrink: 0, borderBottom: '1px solid #1c1e24', display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '0 24px' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, background: '#15171c', border: '1px solid #23262f', borderRadius: 8, padding: '7px 12px', width: 280 }}>
            <Search size={14} color="#6B7280" aria-hidden="true" />
            <span style={{ fontSize: 12.5, color: '#6B7280' }}>Search anything…</span>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
            <Bell size={17} color="#9CA3AF" aria-hidden="true" />
            <div style={{ width: 30, height: 30, borderRadius: '50%', background: 'var(--brand)', display: 'grid', placeItems: 'center', color: '#fff', fontSize: 12, fontWeight: 700 }}>AK</div>
          </div>
        </div>

        <div style={{ padding: 24, maxWidth: 1100, width: '100%', margin: '0 auto', display: 'flex', flexDirection: 'column', gap: 16 }}>
          <div style={{ background: '#15171c', border: '1px solid #23262f', borderRadius: 10, padding: '10px 16px', fontSize: 12.5, color: '#9CA3AF' }}>
            Production component reused unmodified: <code style={{ color: '#E8EAED' }}>components/HeroIllustration.tsx</code> (<code style={{ color: '#E8EAED' }}>HeroIllustrationArt</code>), <code style={{ color: '#E8EAED' }}>src/index.css</code> (<code style={{ color: '#E8EAED' }}>.nf-hero-illus</code>). Everything above the hero card (sidebar/topbar/greeting) is a static visual replica for layout context only — no network requests, no real profile data.
          </div>

          <div style={{ display: 'flex', gap: 24, flexWrap: 'wrap', alignItems: 'flex-start', padding: 14, background: '#15171c', border: '1px solid #23262f', borderRadius: 10 }}>
            <div>
              <div style={{ fontSize: 11, fontWeight: 700, color: '#6B7280', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 8 }}>Mock profile gender</div>
              <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                {GENDER_OPTIONS.map(g => (
                  <ToggleButton key={g} active={gender === g} onClick={() => setGender(g)}>{g}</ToggleButton>
                ))}
              </div>
            </div>
            <div>
              <div style={{ fontSize: 11, fontWeight: 700, color: '#6B7280', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 8 }}>Theme color</div>
              <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                {(Object.keys(ACCENTS) as (keyof typeof ACCENTS)[]).map(a => (
                  <ToggleButton key={a} active={accent === a} onClick={() => setAccent(a)}>{a}</ToggleButton>
                ))}
              </div>
            </div>
          </div>

          <div>
            <div style={{ fontSize: 11, fontWeight: 700, color: '#6B7280', textTransform: 'uppercase', letterSpacing: '.06em', marginBottom: 8 }}>Mock assigned shift</div>
            <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
              {SHIFT_OPTIONS.map((s, i) => (
                <ToggleButton key={i} active={shiftIdx === i} onClick={() => setShiftIdx(i)}>{s.start} - {s.end}</ToggleButton>
              ))}
            </div>
          </div>

          <div style={{ fontSize: 22, fontWeight: 800, color: '#E8EAED', marginTop: 8 }}>Good afternoon, Anil Kumar.</div>
          <div style={{ fontSize: 13, color: '#6B7280', marginTop: -10 }}>Tuesday, September 29</div>

          {/* Real AttendanceHeroBanner's own HeroCard styling, reproduced verbatim — the
              illustration itself (HeroIllustrationArt) is the actual production component. */}
          <div
            data-theme="dark"
            style={{
              position: 'relative', background: [
                'radial-gradient(120% 100% at 80% 10%, color-mix(in srgb, var(--brand) 34%, transparent) 0%, transparent 55%)',
                'linear-gradient(160deg, #0a0b0e 0%, #12141a 100%)',
              ].join(', '),
              borderRadius: 12, padding: '28px 32px', overflow: 'hidden', display: 'flex',
              alignItems: 'center', gap: 24, minHeight: 140, isolation: 'isolate',
            }}
          >
            <div style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: 10, minWidth: 0 }}>
              <div style={{ fontFamily: '"Space Grotesk", sans-serif', fontSize: 22, fontWeight: 700, color: '#E8EAED' }}>
                Not checked in yet.
              </div>
              <p style={{ margin: 0, fontSize: 13, color: 'rgba(229,231,235,0.58)' }}>Shift starts at {shift.start}.</p>
              <button style={{ alignSelf: 'flex-start', display: 'flex', alignItems: 'center', gap: 7, padding: '8px 18px', background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 8, fontSize: 13, fontWeight: 600 }}>
                Check In
              </button>
            </div>
            <div className="nf-hero-illus" data-variant={variant}>
              <HeroIllustrationArt variant={variant} shiftLabel={shiftLabel} />
            </div>
          </div>

          <div style={{ background: '#15171c', border: '1px solid #23262f', borderRadius: 10, padding: 16, fontSize: 12.5, color: '#6B7280', lineHeight: 1.6 }}>
            gender = <code>{gender}</code> → variant = <code>{variant}</code>. Switching Theme Color only changes the background/accent wash — the employee artwork itself never recolors. Switching Mock Assigned Shift only changes the "Today" calendar label inside the hero image — never the surrounding layout.
          </div>
        </div>
      </div>
    </div>
  );
}
