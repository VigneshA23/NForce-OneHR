/**
 * ============================================================================
 *  MOCK / VISUAL PREVIEW — NOT CONNECTED TO ANY BACKEND
 * ============================================================================
 *  Design-review preview of two related features:
 *   1. The Profile header's "Weekly Off"/"Holiday" status badge + designation icon
 *      (ProfileDayStatusBadge / DesignationLine, pages/profile/shared.tsx).
 *   2. The Attendance Log's W-OFF/HLDY badges and the "worked on a non-working
 *      day" positive indicator (InlineDayBadge / AttendanceTimeline /
 *      NonWorkingDayWorkedIcon / NonWorkingDayWorkedNote, all exported from the
 *      real pages/AttendancePage.tsx for exactly this reuse).
 *
 *  - No network requests, no auth/session state, no import of the real
 *    ProfilePage.tsx, AttendancePage.tsx's default export, api/profile.ts,
 *    api/attendance.ts's network calls, api/holidays.ts's network calls, or
 *    Shell.tsx. Every mock AttendanceRecord/holiday name below is invented
 *    locally, never fetched.
 *  - `?state=` selects one of 5 scenarios, matching every case in the request:
 *      working            → normal working day (no badges, plain PRESENT)
 *      weekly-off         → weekly-off day, no work done
 *      holiday            → holiday, no work done
 *      worked-weekly-off  → weekly-off day, but the employee worked anyway
 *      worked-holiday     → holiday, but the employee worked anyway
 *    The Profile badge and the Attendance Log row below it always reflect the
 *    SAME scenario, since both features answer the same underlying question
 *    ("is today a weekly-off/holiday, and did this person work anyway?").
 * ============================================================================
 */
import { useSearchParams } from 'react-router-dom';
import { Home, Users, Clock, Calendar, HelpCircle, Bell, Search, Mail, Phone as PhoneIcon, MapPin, Hash } from 'lucide-react';
import logoUrl from '../../assets/nforce-logo.png';
import profileBannerRed from '../../assets/profile-banner-red.png';
import { ProfileCoverBanner, ProfileDayStatusBadge, DesignationLine, type ProfileDayStatus } from '../profile/shared';
import { InlineDayBadge, AttendanceTimeline, NonWorkingDayWorkedIcon, type DayInfo } from '../AttendancePage';
import type { AttendanceRecord } from '../../api/attendance';
import { TimeFormatProvider } from '../../context/TimeFormatContext';

type PreviewState = 'working' | 'weekly-off' | 'holiday' | 'worked-weekly-off' | 'worked-holiday';
const VALID_STATES: PreviewState[] = ['working', 'weekly-off', 'holiday', 'worked-weekly-off', 'worked-holiday'];

const MOCK_PROFILE = {
  displayName: 'Ananya Sharma',
  designation: 'Senior Product Designer',
  employeeCode: 'NF-2291',
  email: 'ananya.sharma@nforceone.com',
  phone: '98765 43210',
  location: 'Hyderabad, IN',
  initials: 'AS',
};

const THEME_BACKGROUND = `url(${profileBannerRed}) top center / cover no-repeat`;
const HOLIDAY_NAME = 'Diwali';

function todayIso(): string {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

function mockRecord(overrides: Partial<AttendanceRecord>): AttendanceRecord {
  const iso = todayIso();
  return {
    id: 'mock-record', employeeUserId: 'mock-user', employeeCode: MOCK_PROFILE.employeeCode, fullName: MOCK_PROFILE.displayName,
    workDate: iso, checkInAt: `${iso}T09:15:00`, checkOutAt: `${iso}T18:20:00`, sessionStartedAt: `${iso}T09:15:00`,
    workedMinutes: 545, status: 'PRESENT', lateByMinutes: null, source: 'SYSTEM', workMode: 'ONSITE', timezone: 'Asia/Kolkata',
    shiftStartAt: `${iso}T09:00:00`, shiftEndAt: `${iso}T18:00:00`, workdayStartAt: `${iso}T00:00:00`, workdayEndAt: `${iso}T23:59:59`,
    penalized: false,
    ...overrides,
  };
}

function buildScenario(state: PreviewState): { dayStatus: ProfileDayStatus; dayInfo: DayInfo } {
  const iso = todayIso();
  const base: DayInfo = { iso, day: new Date().getDate(), isFuture: false, isBeforeJoining: false, isToday: true, isWeekend: false };
  switch (state) {
    case 'weekly-off':
      return { dayStatus: 'weekly-off', dayInfo: { ...base, isWeekend: true } };
    case 'holiday':
      return { dayStatus: 'holiday', dayInfo: { ...base, holidayName: HOLIDAY_NAME } };
    case 'worked-weekly-off':
      return { dayStatus: 'weekly-off', dayInfo: { ...base, isWeekend: true, record: mockRecord({}) } };
    case 'worked-holiday':
      return { dayStatus: 'holiday', dayInfo: { ...base, holidayName: HOLIDAY_NAME, record: mockRecord({}) } };
    case 'working':
    default:
      return { dayStatus: null, dayInfo: { ...base, record: mockRecord({}) } };
  }
}

const STATE_DESCRIPTIONS: Record<PreviewState, string> = {
  working: 'Normal working day — no Profile badge, no Attendance badge, plain PRESENT status.',
  'weekly-off': 'Weekly-off day, no work done — Profile shows "Weekly Off", Attendance Log shows W-OFF with no working-time metrics.',
  holiday: 'Holiday, no work done — Profile shows "Holiday", Attendance Log shows HLDY and the holiday name, no working-time metrics.',
  'worked-weekly-off': 'Weekly-off day, but the employee worked — Profile still shows "Weekly Off" (the day type doesn\'t change), but the Attendance Log shows the real worked duration plus the celebration indicator ("Good working on a weekend").',
  'worked-holiday': 'Holiday, but the employee worked — Profile still shows "Holiday", Attendance Log shows the real worked duration plus the celebration indicator ("Good working on a holiday").',
};

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

export default function AttendanceStatusPreviewPage() {
  const [params] = useSearchParams();
  const requested = params.get('state');
  const state: PreviewState = (VALID_STATES as string[]).includes(requested ?? '') ? (requested as PreviewState) : 'working';
  const { dayStatus, dayInfo } = buildScenario(state);
  const showSmiley = !!dayInfo.record?.checkInAt && (dayInfo.isWeekend || !!dayInfo.holidayName);

  return (
    <TimeFormatProvider>
    <style>{'@media (max-width: 767px) { .nf-preview-sidebar { display: none !important; } }'}</style>
    <div style={{ display: 'flex', minHeight: '100vh', background: '#0a0b0e', fontFamily: 'Inter, sans-serif' }}>
      <div className="nf-preview-sidebar" style={{ width: 220, flexShrink: 0, background: '#0d0e12', borderRight: '1px solid #1c1e24', padding: '18px 12px', display: 'flex', flexDirection: 'column', gap: 2 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '4px 10px 18px' }}>
          <img src={logoUrl} alt="" style={{ width: 26, height: 26, borderRadius: 6 }} />
          <div>
            <div style={{ fontSize: 13, fontWeight: 800, color: '#E8EAED' }}>NForce OneHR</div>
            <div style={{ fontSize: 9.5, color: '#6B7280', letterSpacing: '.04em' }}>EMPLOYEE EXPERIENCE</div>
          </div>
        </div>
        <NavRow icon={Home} label="Home" />
        <NavRow icon={Users} label="My Team" />
        <NavRow icon={Clock} label="My Attendance" active />
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
            <div style={{ width: 30, height: 30, borderRadius: '50%', background: '#B11116', display: 'grid', placeItems: 'center', color: '#fff', fontSize: 12, fontWeight: 700 }}>
              {MOCK_PROFILE.initials}
            </div>
          </div>
        </div>

        <div style={{ padding: 24, maxWidth: 980, width: '100%', margin: '0 auto', display: 'flex', flexDirection: 'column', gap: 16 }}>
          <div style={{ background: '#15171c', border: '1px solid #23262f', borderRadius: 10, padding: '10px 16px', fontSize: 12.5, color: '#9CA3AF' }}>
            Production components reused unmodified: <code style={{ color: '#E8EAED' }}>pages/profile/shared.tsx</code> (<code style={{ color: '#E8EAED' }}>ProfileDayStatusBadge</code>, <code style={{ color: '#E8EAED' }}>DesignationLine</code>) and <code style={{ color: '#E8EAED' }}>pages/AttendancePage.tsx</code> (<code style={{ color: '#E8EAED' }}>InlineDayBadge</code>, <code style={{ color: '#E8EAED' }}>AttendanceTimeline</code>, <code style={{ color: '#E8EAED' }}>NonWorkingDayWorkedIcon</code>). Everything else (sidebar/topbar/identity row/table chrome) is a static visual replica for layout context only — no network requests, no real data.
            <div style={{ marginTop: 8, color: '#6EE7B7' }}>
              state = <code>{state}</code> — {STATE_DESCRIPTIONS[state]}
            </div>
            <div style={{ marginTop: 6 }}>
              Try: {VALID_STATES.map(s => (
                <a key={s} href={`?state=${s}`} style={{ color: s === state ? '#E4373D' : '#6B7280', marginRight: 10, textDecoration: s === state ? 'underline' : 'none' }}>{s}</a>
              ))}
            </div>
          </div>

          {/* ── 1. Profile header preview ──────────────────────────────────────────── */}
          <div style={{ fontSize: 11, fontWeight: 700, color: '#6B7280', textTransform: 'uppercase', letterSpacing: '.06em' }}>Profile header</div>
          <div style={{ background: '#15171c', border: '1px solid #23262f', borderRadius: 12, overflow: 'hidden' }}>
            <ProfileCoverBanner coverDataUrl={null} themeBackground={THEME_BACKGROUND} editable={false} uploading={false} removing={false} onUpload={() => {}} onRemove={() => {}} />
            <div className="nf-profile-row" style={{ display: 'flex', alignItems: 'center', gap: 20, padding: '0 24px 20px' }}>
              <div className="nf-profile-top" style={{ display: 'flex', alignItems: 'flex-end', gap: 20, flex: 1, minWidth: 0 }}>
                <div style={{ position: 'relative', flexShrink: 0, marginTop: -44 }}>
                  <div style={{ width: 104, height: 104, borderRadius: '50%', background: '#B11116', display: 'grid', placeItems: 'center', color: '#fff', fontSize: 30, fontWeight: 700, border: '4px solid #15171c', boxShadow: '0 2px 10px rgba(0,0,0,.25)' }}>
                    {MOCK_PROFILE.initials}
                  </div>
                </div>
                <div style={{ flex: 1, minWidth: 0, paddingBottom: 4 }}>
                  <div style={{ fontSize: 26, fontWeight: 800, color: '#E8EAED', marginBottom: 3 }}>{MOCK_PROFILE.displayName}</div>
                  <DesignationLine text={MOCK_PROFILE.designation} />
                  <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', alignItems: 'center' }}>
                    <ProfileDayStatusBadge status={dayStatus} />
                    <span style={{ fontSize: 11, fontWeight: 600, padding: '2px 8px', borderRadius: 20, background: 'rgba(177,17,22,.16)', color: '#E4373D' }}>Employee</span>
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
          </div>

          {/* ── 2. Attendance Log preview ───────────────────────────────────────────── */}
          <div style={{ fontSize: 11, fontWeight: 700, color: '#6B7280', textTransform: 'uppercase', letterSpacing: '.06em', marginTop: 8 }}>Attendance log — today's row</div>
          <div style={{ background: '#15171c', border: '1px solid #23262f', borderRadius: 10, overflow: 'hidden' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead>
                <tr>
                  {['Date', 'Attendance Visual', 'In / Out'].map(h => (
                    <th key={h} style={{ textAlign: 'left', padding: '10px 14px', fontSize: 11, fontWeight: 700, color: '#6B7280', textTransform: 'uppercase', letterSpacing: '.06em', borderBottom: '1px solid #23262f' }}>{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                <tr>
                  <td style={{ padding: '14px', verticalAlign: 'top' }}>
                    <div style={{ color: '#E8EAED', fontWeight: 600 }}>Today</div>
                    <div style={{ marginTop: 3 }}>
                      <InlineDayBadge info={dayInfo} />
                      {showSmiley && <NonWorkingDayWorkedIcon reason={dayInfo.holidayName ? 'holiday' : 'weekend'} />}
                    </div>
                  </td>
                  <td style={{ padding: '14px', verticalAlign: 'top', width: 260 }}>
                    <AttendanceTimeline info={dayInfo} punches={undefined} punchesLoading={false} />
                  </td>
                  <td style={{ padding: '14px', verticalAlign: 'top' }}>
                    <div style={{ fontSize: 11.5, color: '#E8EAED' }}>{dayInfo.record?.checkInAt ? new Date(dayInfo.record.checkInAt).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' }) : '—'}</div>
                    <div style={{ fontSize: 11.5, color: '#6B7280', marginTop: 2 }}>{dayInfo.record?.checkOutAt ? new Date(dayInfo.record.checkOutAt).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' }) : '—'}</div>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>

          <div style={{ background: '#15171c', border: '1px solid #23262f', borderRadius: 10, padding: 16, fontSize: 12.5, color: '#6B7280', lineHeight: 1.6 }}>
            On desktop, hover the celebration icon (when shown) for its tooltip. On any device, opening the real day-detail drawer (not reproduced in this lightweight preview) shows the same message as visible text — see <code>NonWorkingDayWorkedNote</code> in the real <code>AttendancePage.tsx</code>.
          </div>
        </div>
      </div>
    </div>
    </TimeFormatProvider>
  );
}
