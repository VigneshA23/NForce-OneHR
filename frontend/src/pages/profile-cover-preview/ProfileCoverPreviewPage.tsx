/**
 * ============================================================================
 *  MOCK / VISUAL PREVIEW — NOT CONNECTED TO ANY BACKEND
 * ============================================================================
 *  Design-review preview of the My Profile page's new cover-image feature.
 *  Renders the REAL `ProfileCoverBanner` component (pages/profile/shared.tsx)
 *  — the exact component ProfilePage.tsx uses — inside a static, non-
 *  functional visual replica of the app shell (sidebar/topbar), so the
 *  feature can be reviewed "in place" without logging in.
 *
 *  - No network requests, no auth/session state, no import of the real
 *    ProfilePage.tsx, api/profile.ts, or Shell.tsx.
 *  - "Upload" reads the chosen file into a data URL with FileReader
 *    (client-side only) and hands it to the same onUpload prop
 *    ProfilePage.tsx wires to a real API call — nothing here ever hits the
 *    network. "Remove" clears that local state, falling back to the same
 *    theme-banner CSS value ProfilePage.tsx computes for real.
 *  - `?state=default` (or no query at all) starts with no custom cover;
 *    `?state=custom` starts with a placeholder custom cover already applied
 *    — either way, every interaction below is live and can flip between the
 *    two, so a single URL demonstrates both states.
 * ============================================================================
 */
import { useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import {
  Home, Users, Clock, Calendar, HelpCircle, Bell, Search, Mail, Phone as PhoneIcon, MapPin, Hash,
} from 'lucide-react';
import logoUrl from '../../assets/nforce-logo.png';
import profileBannerRed from '../../assets/profile-banner-red.png';
import { ProfileCoverBanner } from '../profile/shared';

const MOCK_PROFILE = {
  displayName: 'Ananya Sharma',
  designation: 'Senior Product Designer',
  role: 'Employee',
  employeeCode: 'NF-2291',
  email: 'ananya.sharma@nforceone.com',
  phone: '98765 43210',
  location: 'Hyderabad, IN',
  initials: 'AS',
};

// A small, deliberately generic placeholder used only when the preview starts in `state=custom`
// — a plain gradient data URL, not a real photo, so nothing here implies a genuine uploaded image
// exists anywhere. Any file picked via the pencil control replaces this with the real chosen file.
const PLACEHOLDER_CUSTOM_COVER =
  'data:image/svg+xml;utf8,' + encodeURIComponent(
    `<svg xmlns="http://www.w3.org/2000/svg" width="1200" height="300">
       <defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1">
         <stop offset="0%" stop-color="#1D4ED8"/><stop offset="100%" stop-color="#0F172A"/>
       </linearGradient></defs>
       <rect width="1200" height="300" fill="url(#g)"/>
       <text x="50%" y="52%" font-family="Inter, sans-serif" font-size="22" fill="#ffffff" fill-opacity="0.55" text-anchor="middle">Sample custom cover</text>
     </svg>`
  );

const THEME_BACKGROUND = `url(${profileBannerRed}) top center / cover no-repeat`;

function NavRow({ icon: Icon, label, active }: { icon: typeof Home; label: string; active?: boolean }) {
  return (
    <div style={{
      display: 'flex', alignItems: 'center', gap: 10, padding: '9px 14px', borderRadius: 8,
      background: active ? 'rgba(177,17,22,.14)' : 'transparent',
      color: active ? '#E4373D' : '#9CA3AF', fontSize: 13, fontWeight: active ? 700 : 500,
    }}>
      <Icon size={16} aria-hidden="true" />
      {label}
    </div>
  );
}

export default function ProfileCoverPreviewPage() {
  const [params] = useSearchParams();
  const initialCustom = params.get('state') === 'custom';

  const [coverDataUrl, setCoverDataUrl] = useState<string | null>(initialCustom ? PLACEHOLDER_CUSTOM_COVER : null);
  const [uploading, setUploading] = useState(false);
  const [removing, setRemoving] = useState(false);
  const [lastAction, setLastAction] = useState<string | null>(initialCustom ? 'Started at ?state=custom' : 'Started at ?state=default');

  function handleUpload(file: File) {
    setUploading(true);
    const reader = new FileReader();
    reader.onload = () => {
      setCoverDataUrl(typeof reader.result === 'string' ? reader.result : null);
      setUploading(false);
      setLastAction(`Uploaded "${file.name}" (${Math.round(file.size / 1024)} KB) — read locally, never sent anywhere`);
    };
    reader.onerror = () => setUploading(false);
    reader.readAsDataURL(file);
  }

  function handleRemove() {
    setRemoving(true);
    setTimeout(() => {
      setCoverDataUrl(null);
      setRemoving(false);
      setLastAction('Removed custom cover — restored the default theme banner');
    }, 200);
  }

  return (
    <div style={{ display: 'flex', minHeight: '100vh', background: '#0a0b0e', fontFamily: 'Inter, sans-serif' }}>
      {/* Static sidebar replica — not the real Shell/SidebarNav, purely visual context. */}
      <div style={{ width: 220, flexShrink: 0, background: '#0d0e12', borderRight: '1px solid #1c1e24', padding: '18px 12px', display: 'flex', flexDirection: 'column', gap: 2 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '4px 10px 18px' }}>
          <img src={logoUrl} alt="" style={{ width: 26, height: 26, borderRadius: 6 }} />
          <div>
            <div style={{ fontSize: 13, fontWeight: 800, color: '#E8EAED' }}>NForce OneHR</div>
            <div style={{ fontSize: 9.5, color: '#6B7280', letterSpacing: '.04em' }}>EMPLOYEE EXPERIENCE</div>
          </div>
        </div>
        <NavRow icon={Home} label="Home" />
        <NavRow icon={Users} label="My Team" />
        <NavRow icon={Users} label="People Directory" />
        <NavRow icon={Clock} label="My Attendance" />
        <NavRow icon={Calendar} label="Leave & Holidays" />
        <div style={{ marginTop: 6 }}>
          <NavRow icon={Users} label="My Profile" active />
        </div>
        <NavRow icon={HelpCircle} label="Help & Guidance" />
      </div>

      <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column' }}>
        {/* Static topbar replica. */}
        <div style={{ height: 56, flexShrink: 0, borderBottom: '1px solid #1c1e24', display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '0 24px' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, background: '#15171c', border: '1px solid #23262f', borderRadius: 8, padding: '7px 12px', width: 280 }}>
            <Search size={14} color="#6B7280" aria-hidden="true" />
            <span style={{ fontSize: 12.5, color: '#6B7280' }}>Search anything…</span>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
            <Bell size={17} color="#9CA3AF" aria-hidden="true" />
            <div style={{ width: 30, height: 30, borderRadius: '50%', background: '#B11116', display: 'grid', placeItems: 'center', color: '#fff', fontSize: 12, fontWeight: 700 }}>
              {MOCK_PROFILE.initials}
            </div>
          </div>
        </div>

        <div style={{ padding: 24, maxWidth: 980, width: '100%', margin: '0 auto', display: 'flex', flexDirection: 'column', gap: 16 }}>
          <div style={{ background: '#15171c', border: '1px solid #23262f', borderRadius: 10, padding: '10px 16px', fontSize: 12.5, color: '#9CA3AF' }}>
            Production component reused unmodified: <code style={{ color: '#E8EAED' }}>pages/profile/shared.tsx</code> (<code style={{ color: '#E8EAED' }}>ProfileCoverBanner</code>). Everything around it (sidebar/topbar/identity row) is a static visual replica for layout context only — no network requests, no real profile data.
            {lastAction && <div style={{ marginTop: 6, color: '#6EE7B7' }}>Last action: {lastAction}</div>}
          </div>

          {/* Real My Profile header structure, matching ProfilePage.tsx's own markup. */}
          <div style={{ background: '#15171c', border: '1px solid #23262f', borderRadius: 12, overflow: 'hidden' }}>
            <ProfileCoverBanner
              coverDataUrl={coverDataUrl}
              themeBackground={THEME_BACKGROUND}
              editable
              uploading={uploading}
              removing={removing}
              onUpload={handleUpload}
              onRemove={handleRemove}
            />

            <div style={{ display: 'flex', alignItems: 'center', gap: 20, padding: '0 24px 20px' }}>
              <div style={{ display: 'flex', alignItems: 'flex-end', gap: 20, flex: 1, minWidth: 0 }}>
                <div style={{ position: 'relative', flexShrink: 0, marginTop: -44 }}>
                  {/* Avatar — deliberately static/independent: never reads coverDataUrl, proving
                      the two are unrelated. */}
                  <div style={{ width: 104, height: 104, borderRadius: '50%', background: '#B11116', display: 'grid', placeItems: 'center', color: '#fff', fontSize: 30, fontWeight: 700, border: '4px solid #15171c', boxShadow: '0 2px 10px rgba(0,0,0,.25)' }}>
                    {MOCK_PROFILE.initials}
                  </div>
                </div>

                <div style={{ flex: 1, minWidth: 0, paddingBottom: 4 }}>
                  <div style={{ fontSize: 26, fontWeight: 800, color: '#E8EAED', marginBottom: 3 }}>{MOCK_PROFILE.displayName}</div>
                  <div style={{ fontSize: 13.5, color: '#9CA3AF', marginBottom: 8 }}>{MOCK_PROFILE.designation}</div>
                  <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                    <span style={{ fontSize: 11, fontWeight: 600, padding: '2px 8px', borderRadius: 20, background: 'rgba(177,17,22,.16)', color: '#E4373D' }}>{MOCK_PROFILE.role}</span>
                    <span style={{ fontSize: 11, fontWeight: 600, padding: '2px 8px', borderRadius: 20, background: 'rgba(47,182,124,.15)', color: '#2FB67C' }}>Active</span>
                    <span style={{ fontSize: 11, fontWeight: 500, padding: '2px 8px', borderRadius: 20, background: 'rgba(107,114,128,.15)', color: '#9CA3AF' }}>{MOCK_PROFILE.employeeCode}</span>
                  </div>
                </div>
              </div>
            </div>

            <div style={{ display: 'flex', flexWrap: 'wrap', gap: '10px 28px', padding: '14px 24px', borderTop: '1px solid #23262f' }}>
              {[
                { icon: Mail, value: MOCK_PROFILE.email },
                { icon: PhoneIcon, value: MOCK_PROFILE.phone },
                { icon: MapPin, value: MOCK_PROFILE.location },
                { icon: Hash, value: MOCK_PROFILE.employeeCode },
              ].map(({ icon: Icon, value }, i) => (
                <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 7 }}>
                  <Icon size={13} color="#6B7280" aria-hidden="true" />
                  <span style={{ fontSize: 12.5, color: '#9CA3AF' }}>{value}</span>
                </div>
              ))}
            </div>

            <div style={{ display: 'flex', gap: 4, padding: '0 24px', borderTop: '1px solid #23262f' }}>
              {['About', 'Profile', 'Job', 'Documents', 'Assets'].map((t, i) => (
                <div key={t} style={{
                  padding: '12px 14px', fontSize: 13, fontWeight: i === 0 ? 700 : 600,
                  color: i === 0 ? '#E4373D' : '#9CA3AF',
                  borderBottom: i === 0 ? '2px solid #E4373D' : '2px solid transparent',
                }}>
                  {t}
                </div>
              ))}
            </div>
          </div>

          <div style={{ background: '#15171c', border: '1px solid #23262f', borderRadius: 10, padding: 16, fontSize: 12.5, color: '#6B7280', lineHeight: 1.6 }}>
            Try it: click the pencil to pick an image from your device — it becomes the cover immediately, cropped/filled with <code>object-fit: cover</code> behavior (no stretching). Click the pencil again to change it, or the restore icon next to it to drop back to the default theme banner. The avatar circle above never changes regardless of what you do to the cover.
          </div>
        </div>
      </div>
    </div>
  );
}
