import { useEffect, useRef, useState } from 'react';
import { Camera, Mail, Phone as PhoneIcon, MapPin, Hash, UserRound, IdCard, Briefcase, TrendingUp, FileText, Package } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import { profileApi, profileEducationApi, type ProfileData } from '../api/profile';
import { useAuthStore } from '../store/authStore';
import { useToast } from '../context/ToastContext';
import { invalidateEmployeePhoto } from '../components/EmployeeAvatar';
import { useAccentColor, type AccentColor } from '../lib/accentColor';
import profileBannerRed from '../assets/profile-banner-red.png';
import profileBannerBlue from '../assets/profile-banner-blue.png';
import profileBannerPink from '../assets/profile-banner-pink.png';
import profileBannerGreen from '../assets/profile-banner-green.png';
import profileBannerPurple from '../assets/profile-banner-purple.png';
import { PhotoModal, AvatarPickerModal, ProfileCoverBanner, ProfileDayStatusBadge, DesignationLine, dicebearUrl, ROLE_LABELS, computeDisplayName, computeProfileCompletion, ProfileCompletionCard, type ProfileDayStatus, type ProfileFieldTarget } from './profile/shared';
import { attendanceApi } from '../api/attendance';
import { holidaysApi } from '../api/holidays';
import { AboutTab } from './profile/tabs/AboutTab';
import { ProfileTab } from './profile/tabs/ProfileTab';
import { JobTab } from './profile/tabs/JobTab';
import { DocumentsTab } from './profile/tabs/DocumentsTab';
import { AssetsTab } from './profile/tabs/AssetsTab';
import { GrowthTab } from './profile/tabs/GrowthTab';

type TabKey = 'about' | 'profile' | 'job' | 'growth' | 'documents' | 'assets';
const TABS: { key: TabKey; label: string; icon: LucideIcon }[] = [
  { key: 'about', label: 'About', icon: UserRound },
  { key: 'profile', label: 'Profile', icon: IdCard },
  { key: 'job', label: 'Job', icon: Briefcase },
  { key: 'growth', label: 'Growth', icon: TrendingUp },
  { key: 'documents', label: 'Documents', icon: FileText },
  { key: 'assets', label: 'Assets', icon: Package },
];

export default function ProfilePage() {
  const token     = useAuthStore(s => s.token) ?? '';
  const storeUser = useAuthStore(s => s.user);
  const setAuth   = useAuthStore(s => s.setAuth);
  const { showToast } = useToast();
  const { accent } = useAccentColor();
  const photoInputRef = useRef<HTMLInputElement>(null);

  const [profile, setProfile]   = useState<ProfileData | null>(null);
  const [loading, setLoading]   = useState(true);
  const [uploading, setUploading] = useState(false);
  const [removing, setRemoving] = useState(false);
  const [showPhotoModal, setShowPhotoModal] = useState(false);
  const [showAvatarPicker, setShowAvatarPicker] = useState(false);
  const [settingAvatar, setSettingAvatar] = useState<string | null>(null);
  const [coverUploading, setCoverUploading] = useState(false);
  const [coverRemoving, setCoverRemoving] = useState(false);
  const [activeTab, setActiveTab] = useState<TabKey>('about');
  const [dayStatus, setDayStatus] = useState<ProfileDayStatus>(null);
  // Profile Completion counts "has logged at least one Education entry" as one of its items —
  // Education itself lives in ProfileTab/EducationCard, not on `profile`, so it needs its own
  // lightweight fetch here just to know whether the list is non-empty. Reflects the count as of
  // this page load; adding/removing an entry from the Education section updates it on next load,
  // not live, since EducationCard owns that list's state independently.
  const [hasEducation, setHasEducation] = useState(false);

  useEffect(() => {
    profileApi.get(token)
      .then(p => {
        setProfile(p);
        // Keep the shared auth store's photo in sync in case it's stale/missing here too.
        if (storeUser && storeUser.photoDataUrl !== p.photoDataUrl) {
          setAuth(token, { ...storeUser, photoDataUrl: p.photoDataUrl });
        }
      })
      .catch(() => showToast('error', 'Failed to load profile'))
      .finally(() => setLoading(false));
    profileEducationApi.list(token).then(list => setHasEducation(list.length > 0)).catch(() => {});
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token]);

  // "Today" status badge (Weekly Off / Holiday) — reuses the exact same source of truth and
  // client-side classification AttendancePage.tsx/DashboardPage.tsx already use for the same
  // question (attendanceApi.config().weeklyOffDays + holidaysApi.listForMyLocation()), rather
  // than inventing a second calculation: the backend's employee-specific WeeklyOffPolicy and
  // location Holiday calendar are already fully expressed in those two responses. Holiday takes
  // precedence over weekly-off when both apply on the same date — same precedence
  // InlineDayBadge/primaryDayBadge/AttendanceTimeline use everywhere on the Attendance page.
  useEffect(() => {
    if (!profile?.hasEmployeeRecord) { setDayStatus(null); return; }
    let alive = true;
    Promise.all([attendanceApi.config(token), holidaysApi.listForMyLocation(token)])
      .then(([config, holidays]) => {
        if (!alive) return;
        const now = new Date();
        const todayIso = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`;
        const dowNames = ['SUNDAY', 'MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY'];
        const isHoliday = holidays.some(h => h.active && h.holidayDate === todayIso);
        const isWeeklyOff = config.weeklyOffDays.includes(dowNames[now.getDay()]);
        setDayStatus(isHoliday ? 'holiday' : isWeeklyOff ? 'weekly-off' : null);
      })
      .catch(() => { if (alive) setDayStatus(null); });
    return () => { alive = false; };
  }, [token, profile?.hasEmployeeRecord]);

  async function handlePhotoChange(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    if (!file) return;
    setUploading(true);
    try {
      const updated = await profileApi.uploadPhoto(token, file);
      setProfile(updated);
      // Push the new photo into the shared auth store so the Shell topbar/sidebar
      // avatars (which read storeUser.photoDataUrl) update immediately, instead of
      // the photo only ever showing up here on this page.
      if (storeUser) setAuth(token, { ...storeUser, photoDataUrl: updated.photoDataUrl });
      // Every other avatar for this person (directory, org chart, team lists, search — anywhere
      // else in the app) resolves the photo via a session-cached fetch keyed by userId; drop
      // that cache entry so those spots pick up the new photo instead of showing the old one.
      invalidateEmployeePhoto(updated.userId);
      showToast('success', 'Photo updated');
      setShowPhotoModal(false);
    } catch (err) {
      showToast('error', err instanceof Error ? err.message : 'Upload failed');
    } finally {
      setUploading(false);
      if (photoInputRef.current) photoInputRef.current.value = '';
    }
  }

  async function handleRemovePhoto() {
    setRemoving(true);
    try {
      const updated = await profileApi.removePhoto(token);
      setProfile(updated);
      if (storeUser) setAuth(token, { ...storeUser, photoDataUrl: updated.photoDataUrl });
      invalidateEmployeePhoto(updated.userId);
      showToast('success', 'Photo removed');
      setShowPhotoModal(false);
    } catch (err) {
      showToast('error', err instanceof Error ? err.message : 'Remove failed');
    } finally {
      setRemoving(false);
    }
  }

  // Mirrors handlePhotoChange/handleRemovePhoto above — entirely separate state, never touches
  // profile.photoDataUrl or the shared auth-store avatar sync those use.
  async function handleCoverUpload(file: File) {
    setCoverUploading(true);
    try {
      const updated = await profileApi.uploadCover(token, file);
      setProfile(updated);
      showToast('success', 'Cover updated');
    } catch (err) {
      showToast('error', err instanceof Error ? err.message : 'Cover upload failed');
    } finally {
      setCoverUploading(false);
    }
  }

  async function handleCoverRemove() {
    setCoverRemoving(true);
    try {
      const updated = await profileApi.removeCover(token);
      setProfile(updated);
      showToast('success', 'Cover reset to default');
    } catch (err) {
      showToast('error', err instanceof Error ? err.message : 'Failed to remove cover');
    } finally {
      setCoverRemoving(false);
    }
  }

  async function handleSetAvatar(seed: string) {
    setSettingAvatar(seed);
    try {
      const updated = await profileApi.setAvatar(token, dicebearUrl(seed));
      setProfile(updated);
      if (storeUser) setAuth(token, { ...storeUser, photoDataUrl: updated.photoDataUrl });
      invalidateEmployeePhoto(updated.userId);
      showToast('success', 'Avatar updated');
      setShowAvatarPicker(false);
      setShowPhotoModal(false);
    } catch (err) {
      showToast('error', err instanceof Error ? err.message : 'Failed to set avatar');
    } finally {
      setSettingAvatar(null);
    }
  }

  // Profile Completion/Health cards' "click a missing item" dropdown — 'about'/'profile' just
  // switch the tab the field actually lives on; 'photo' has no tab of its own (it's the header's
  // own button), so it opens that modal directly instead.
  function handleJumpTo(target: ProfileFieldTarget) {
    if (target === 'photo') setShowPhotoModal(true);
    else setActiveTab(target);
  }

  if (loading) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: 200 }}>
        <div style={{ color: 'var(--txt-mut)', fontSize: 13 }}>Loading profile…</div>
      </div>
    );
  }

  if (!profile) return null;

  const displayName = computeDisplayName(profile);
  const initials = displayName ? displayName.split(' ').map(w => w[0]).join('').slice(0, 2).toUpperCase() : profile.email.slice(0, 2).toUpperCase();

  // Every Theme color now has a real banner image (orange has none — it was dropped from the
  // accent palette entirely, see accentColor.tsx). Each is square with a watermark sitting in its
  // vertical middle; "top center" plus this short/wide banner box means CSS only ever paints
  // roughly the top 15-20% of any of them, which is comfortably above the watermark in every one
  // — no manual cropping needed.
  const BANNER_IMAGES: Partial<Record<AccentColor, string>> = {
    red: profileBannerRed,
    blue: profileBannerBlue,
    pink: profileBannerPink,
    green: profileBannerGreen,
    purple: profileBannerPurple,
  };
  const bannerImage = BANNER_IMAGES[accent];
  const heroBackground = bannerImage
    ? `url(${bannerImage}) top center / cover no-repeat`
    : 'linear-gradient(135deg, var(--brand-deep) 0%, var(--brand) 100%)';

  const isIn = profile.attendanceStatus === 'IN';

  const subMetaItems: { icon: typeof Mail; label: string; value: string }[] = [
    { icon: Mail,      label: 'Work Email',    value: profile.email },
    { icon: PhoneIcon, label: 'Mobile Number', value: profile.phone || '—' },
    { icon: MapPin,    label: 'Location',      value: profile.locationName || '—' },
    { icon: Hash,      label: 'Employee Code', value: profile.employeeCode },
  ];

  // Growth (Skills & Expertise, What I Learned) is self-service career tracking for individual
  // contributors — Super Admin/HR Admin don't need it on their own profile. Managers keep it:
  // they're still individual contributors tracking their own growth, same as Employees.
  const visibleTabs = (profile.role === 'SUPER_ADMIN' || profile.role === 'HR_ADMIN')
    ? TABS.filter(t => t.key !== 'growth')
    : TABS;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
      {/* Hero banner + identity card — page starts here, no outer page header/title. */}
      <div className="nf-profile-header" style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, overflow: 'hidden' }}>
        <ProfileCoverBanner
          coverDataUrl={profile.coverDataUrl}
          themeBackground={heroBackground}
          editable={profile.hasEmployeeRecord}
          uploading={coverUploading}
          removing={coverRemoving}
          onUpload={handleCoverUpload}
          onRemove={handleCoverRemove}
        />

        <div className="nf-profile-row" style={{ display: 'flex', alignItems: 'center', gap: 20, padding: '0 24px 20px' }}>
          <div className="nf-profile-top" style={{ display: 'flex', alignItems: 'flex-end', gap: 20, flex: 1, minWidth: 0 }}>
            <div style={{ position: 'relative', flexShrink: 0, marginTop: 'clamp(-56px, -7vw, -44px)' }}>
              <button
                type="button"
                onClick={() => { if (profile.hasEmployeeRecord) setShowPhotoModal(true); }}
                aria-label={profile.hasEmployeeRecord ? 'View profile photo' : 'Profile photo'}
                style={{ padding: 0, border: 'none', background: 'none', cursor: profile.hasEmployeeRecord ? 'pointer' : 'default', display: 'block', borderRadius: '50%' }}
              >
                {profile.photoDataUrl ? (
                  <img src={profile.photoDataUrl} alt="Profile" style={{ width: 104, height: 104, borderRadius: '50%', objectFit: 'cover', border: '4px solid var(--panel)', boxShadow: '0 2px 10px rgba(0,0,0,.25)' }} />
                ) : (
                  <div style={{ width: 104, height: 104, borderRadius: '50%', background: 'var(--brand)', display: 'grid', placeItems: 'center', color: '#fff', fontSize: 30, fontWeight: 700, border: '4px solid var(--panel)', boxShadow: '0 2px 10px rgba(0,0,0,.25)' }}>
                    {initials}
                  </div>
                )}
              </button>
              {profile.hasEmployeeRecord && (
                <>
                  <button
                    onClick={() => setShowPhotoModal(true)}
                    aria-label="Change photo"
                    style={{ position: 'absolute', bottom: 4, right: 4, width: 26, height: 26, borderRadius: '50%', background: 'var(--brand)', border: '2px solid var(--panel)', display: 'grid', placeItems: 'center', cursor: 'pointer' }}
                  >
                    <Camera size={12} color="#fff" aria-hidden />
                  </button>
                  <input ref={photoInputRef} type="file" accept="image/*" style={{ display: 'none' }} onChange={handlePhotoChange} />
                </>
              )}
            </div>

            <div style={{ flex: 1, minWidth: 0, paddingBottom: 4 }}>
              <div style={{ fontSize: 26, fontWeight: 800, color: 'var(--txt)', fontFamily: 'Inter, sans-serif', marginBottom: 3 }}>{displayName}</div>
              <DesignationLine text={profile.designationName ?? ROLE_LABELS[profile.role] ?? profile.role} />
              <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', alignItems: 'center' }}>
                <ProfileDayStatusBadge status={dayStatus} />
                <span style={{ fontSize: 11, fontWeight: 600, padding: '2px 8px', borderRadius: 20, background: 'color-mix(in srgb, var(--brand) 16%, transparent)', color: 'var(--brand-bright)' }}>
                  {ROLE_LABELS[profile.role] ?? profile.role}
                </span>
                <span style={{ fontSize: 11, fontWeight: 600, padding: '2px 8px', borderRadius: 20, background: 'rgba(47,182,124,.15)', color: 'var(--ok)' }}>
                  {profile.active ? 'Active' : 'Inactive'}
                </span>
                {/* Attendance In/Out indicator — dot painted with currentColor so it tracks the
                    same --ok/--risk tokens as the badge color with no separate declaration. */}
                <span style={{
                  display: 'flex', alignItems: 'center', gap: 5, fontSize: 11, fontWeight: 600, padding: '2px 8px', borderRadius: 20,
                  background: isIn ? 'rgba(47,182,124,.15)' : 'rgba(228,55,61,.15)',
                  color: isIn ? 'var(--ok)' : 'var(--risk)',
                }}>
                  <span style={{ width: 6, height: 6, borderRadius: '50%', background: 'currentColor' }} aria-hidden />
                  {isIn ? 'In' : 'Out'}
                </span>
                <span style={{ fontSize: 11, fontWeight: 500, padding: '2px 8px', borderRadius: 20, background: 'rgba(107,114,128,.15)', color: 'var(--txt-dim)' }}>
                  {profile.employeeCode}
                </span>
              </div>
            </div>
          </div>

          {profile.hasEmployeeRecord && (
            <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
              <ProfileCompletionCard detail={computeProfileCompletion(profile, hasEducation)} onJumpTo={handleJumpTo} />
            </div>
          )}
        </div>

        {/* Sub-meta bar */}
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: '10px 28px', padding: '14px 24px', borderTop: '1px solid var(--line)' }}>
          {subMetaItems.map(({ icon: Icon, label, value }) => (
            <div key={label} style={{ display: 'flex', alignItems: 'center', gap: 7 }}>
              <Icon size={13} color="var(--txt-dim)" aria-hidden />
              <span style={{ fontSize: 12.5, color: 'var(--txt-mut)' }}>{value}</span>
            </div>
          ))}
        </div>

        {/* Primary tabs */}
        <div style={{ display: 'flex', gap: 4, padding: '0 24px', borderTop: '1px solid var(--line)', overflowX: 'auto' }}>
          {visibleTabs.map(t => (
            <button key={t.key} onClick={() => setActiveTab(t.key)} style={{
              display: 'flex', alignItems: 'center', gap: 6,
              background: 'none', border: 'none', borderBottom: activeTab === t.key ? '2px solid var(--brand-bright)' : '2px solid transparent',
              padding: '12px 14px', fontSize: 13, fontWeight: activeTab === t.key ? 700 : 600,
              color: activeTab === t.key ? 'var(--brand-bright)' : 'var(--txt-mut)', cursor: 'pointer', whiteSpace: 'nowrap',
            }}>
              <t.icon size={14} aria-hidden="true" />
              {t.label}
            </button>
          ))}
        </div>
      </div>

      {activeTab === 'about' && <AboutTab profile={profile} token={token} onSaved={setProfile} />}
      {activeTab === 'profile' && <ProfileTab profile={profile} token={token} onSaved={setProfile} />}
      {activeTab === 'job' && <JobTab profile={profile} token={token} />}
      {activeTab === 'growth' && <GrowthTab token={token} />}
      {activeTab === 'documents' && <DocumentsTab token={token} />}
      {activeTab === 'assets' && <AssetsTab token={token} />}

      {showPhotoModal && profile.hasEmployeeRecord && (
        <PhotoModal
          photoDataUrl={profile.photoDataUrl}
          initials={initials}
          uploading={uploading}
          removing={removing}
          onEditClick={() => photoInputRef.current?.click()}
          onRemove={handleRemovePhoto}
          onChooseAvatarClick={() => setShowAvatarPicker(true)}
          onClose={() => setShowPhotoModal(false)}
        />
      )}

      {showAvatarPicker && profile.hasEmployeeRecord && (
        <AvatarPickerModal
          currentAvatarUrl={profile.photoDataUrl}
          settingAvatar={settingAvatar}
          onPick={handleSetAvatar}
          onClose={() => setShowAvatarPicker(false)}
        />
      )}
    </div>
  );
}
