import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Camera, Lock, Shield, X } from 'lucide-react';
import { profileApi, type ProfileData, type UpdateProfilePayload } from '../api/profile';
import { useAuthStore } from '../store/authStore';
import { useToast } from '../context/ToastContext';

const WORK_MODES = ['ONSITE', 'HYBRID', 'REMOTE'] as const;
const GENDERS = ['Male', 'Female', 'Non-binary', 'Prefer not to say'];

// Rejects: multiple/misplaced '@', missing local or domain part, spaces, consecutive dots in
// the domain, and trailing junk after the TLD. A syntactically well-formed but non-existent
// TLD (e.g. "gmail.comabc") is otherwise indistinguishable from a real one by format alone —
// see the explicit ".com" check in validateEmail below for the one case called out by name.
const EMAIL_RE = /^[a-zA-Z0-9](?:[a-zA-Z0-9._%+-]*[a-zA-Z0-9])?@(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]*[a-zA-Z0-9])?\.)+[a-zA-Z]{2,}$/;
// Once the TLD starts with "com", nothing may follow it (blocks "gmail.comabc" while still
// allowing "gmail.com" itself) — a deliberate special case for the specific reported input,
// not a general TLD whitelist.
const TLD_COM_WITH_TRAILING_CHARS_RE = /^com.+/i;
// Letters only, with a single space/hyphen/apostrophe/period allowed between word groups
// (e.g. "Mary Jane", "O'Brien", "Anne-Marie") — no digits, no leading/trailing/consecutive
// separators, no other symbols.
const NAME_RE = /^[A-Za-z]+(?:[ '.-][A-Za-z]+)*$/;
const digitsOnly = (v: string) => v.replace(/\D/g, '');
const nameCharsOnly = (v: string) => v.replace(/[^A-Za-z '.-]/g, '');
// Strips emoji/pictographs (plus the variation-selector and zero-width-joiner marks used to
// combine them, e.g. skin-tone modifiers, flag sequences) from free-text fields like Address —
// unlike Name, Address needs to stay open to digits/punctuation/most Unicode text, so this only
// removes emoji specifically rather than restricting to an allow-list.
const EMOJI_RE = /\p{Extended_Pictographic}|\p{Emoji_Presentation}|[\u{1F1E6}-\u{1F1FF}]|[\u200D\uFE0F]/gu;
const stripEmoji = (v: string) => v.replace(EMOJI_RE, '');

function validateEmail(v: string): string | null {
  if (!v) return null;
  if (!EMAIL_RE.test(v)) return 'Enter a valid email address (e.g. name@example.com).';
  const tld = v.slice(v.lastIndexOf('.') + 1);
  if (TLD_COM_WITH_TRAILING_CHARS_RE.test(tld)) return 'Enter a valid email address (e.g. name@example.com).';
  return null;
}

function validatePhone(v: string, label: string): string | null {
  if (!v) return null;
  return digitsOnly(v).length === 10 ? null : `${label} must be exactly 10 digits.`;
}

function validateName(v: string, label: string): string | null {
  if (!v) return null;
  return NAME_RE.test(v) ? null : `${label} can only contain letters, spaces, hyphens, apostrophes, and periods.`;
}

const ROLE_LABELS: Record<string, string> = {
  SUPER_ADMIN: 'Super Admin',
  HR_ADMIN: 'HR Admin',
  MANAGER: 'Manager',
  EMPLOYEE: 'Employee',
};

function SectionHeader({ title, badge }: { title: string; badge?: string }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 18 }}>
      <h2 style={{ margin: 0, fontSize: 13, fontWeight: 700, color: 'var(--txt)', fontFamily: '"Space Grotesk", sans-serif', textTransform: 'uppercase', letterSpacing: '.06em' }}>
        {title}
      </h2>
      {badge && (
        <span style={{ fontSize: 10, fontWeight: 600, padding: '2px 7px', borderRadius: 20, background: 'rgba(107,114,128,.15)', color: 'var(--txt-dim)', letterSpacing: '.04em', textTransform: 'uppercase' }}>
          {badge}
        </span>
      )}
    </div>
  );
}

function ReadField({ label, value }: { label: string; value: string | null | undefined }) {
  return (
    <div>
      <div style={{ fontSize: 11, fontWeight: 600, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.05em', marginBottom: 4 }}>{label}</div>
      <div style={{ fontSize: 13, color: value ? 'var(--txt)' : 'var(--txt-dim)', minHeight: 20 }}>{value || '—'}</div>
    </div>
  );
}

const INPUT_STYLE: React.CSSProperties = {
  width: '100%', boxSizing: 'border-box',
  background: 'var(--raised)', border: '1px solid var(--line2)',
  borderRadius: 6, padding: '7px 10px', fontSize: 13, color: 'var(--txt)', outline: 'none',
};

function EditField({ label, value, onChange, type = 'text', placeholder, error }: {
  label: string;
  value: string;
  onChange: (v: string) => void;
  type?: string;
  placeholder?: string;
  error?: string | null;
}) {
  return (
    <div>
      <label style={{ display: 'block', fontSize: 11, fontWeight: 600, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.05em', marginBottom: 5 }}>{label}</label>
      <input type={type} value={value} onChange={e => onChange(e.target.value)} placeholder={placeholder}
        style={{ ...INPUT_STYLE, ...(error ? { borderColor: 'var(--risk)' } : {}) }} />
      {error && <div style={{ fontSize: 11, color: 'var(--risk)', marginTop: 4 }}>{error}</div>}
    </div>
  );
}

function PhoneField({ label, value, onChange, placeholder, error }: {
  label: string;
  value: string;
  onChange: (v: string) => void;
  placeholder?: string;
  error?: string | null;
}) {
  return (
    <div>
      <label style={{ display: 'block', fontSize: 11, fontWeight: 600, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.05em', marginBottom: 5 }}>{label}</label>
      <input
        type="tel"
        inputMode="numeric"
        maxLength={10}
        value={value}
        onChange={e => onChange(digitsOnly(e.target.value).slice(0, 10))}
        placeholder={placeholder}
        style={{ ...INPUT_STYLE, ...(error ? { borderColor: 'var(--risk)' } : {}) }}
      />
      {error && <div style={{ fontSize: 11, color: 'var(--risk)', marginTop: 4 }}>{error}</div>}
    </div>
  );
}

// Profile photo popup — expands the avatar into a preview with Remove/Edit actions below it.
// Every size (dialog width/padding, the enlarged photo, button type) scales continuously with
// `clamp()` driven by `vw`, with no max-width media query gating it, so it resizes smoothly at
// every viewport width — phone through ultrawide desktop — rather than jumping between a
// handful of fixed breakpoint sizes.
function PhotoModal({ photoDataUrl, initials, uploading, removing, onEditClick, onRemove, onClose }: {
  photoDataUrl: string | null;
  initials: string;
  uploading: boolean;
  removing: boolean;
  onEditClick: () => void;
  onRemove: () => void;
  onClose: () => void;
}) {
  const busy = uploading || removing;
  return (
    <div
      style={{ position: 'fixed', inset: 0, background: 'rgba(0,0,0,.7)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 600, padding: 'clamp(16px, 4vw, 40px)' }}
      onClick={onClose}
    >
      <div
        onClick={e => e.stopPropagation()}
        style={{
          background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 'clamp(10px, 1.4vw, 16px)',
          padding: 'clamp(20px, 3.4vw, 32px)', display: 'flex', flexDirection: 'column', alignItems: 'center',
          gap: 'clamp(14px, 2.4vw, 22px)', width: 'clamp(240px, 34vw, 420px)', maxWidth: '92vw',
          boxShadow: '0 24px 64px rgba(0,0,0,.55)',
        }}
      >
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', width: '100%' }}>
          <span style={{ fontSize: 'clamp(13px, 1.4vw, 15px)', fontWeight: 700, color: 'var(--txt)', fontFamily: '"Space Grotesk", sans-serif' }}>
            Profile Photo
          </span>
          <button onClick={onClose} aria-label="Close" style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt-dim)', padding: 4, display: 'flex' }}>
            <X size={16} />
          </button>
        </div>

        {photoDataUrl ? (
          <img
            src={photoDataUrl}
            alt="Profile"
            style={{
              width: 'clamp(140px, 24vw, 280px)', height: 'clamp(140px, 24vw, 280px)',
              borderRadius: '50%', objectFit: 'cover', border: '3px solid var(--line2)',
            }}
          />
        ) : (
          <div
            style={{
              width: 'clamp(140px, 24vw, 280px)', height: 'clamp(140px, 24vw, 280px)', borderRadius: '50%',
              background: '#B11116', display: 'grid', placeItems: 'center', color: '#fff',
              fontSize: 'clamp(32px, 6vw, 64px)', fontWeight: 700, border: '3px solid rgba(177,17,22,.4)',
            }}
          >
            {initials}
          </div>
        )}

        <div style={{ display: 'flex', gap: 'clamp(8px, 1.4vw, 12px)', width: '100%' }}>
          {photoDataUrl && (
            <button
              onClick={onRemove}
              disabled={busy}
              style={{
                flex: 1, padding: 'clamp(8px, 1.4vw, 10px) clamp(10px, 2vw, 16px)', background: 'var(--raised)',
                border: '1px solid var(--line2)', borderRadius: 7, fontSize: 'clamp(12px, 1.3vw, 13.5px)',
                fontWeight: 600, color: 'var(--risk)', cursor: busy ? 'not-allowed' : 'pointer',
                opacity: busy ? .7 : 1,
              }}
            >
              {removing ? 'Removing…' : 'Remove'}
            </button>
          )}
          <button
            onClick={onEditClick}
            disabled={busy}
            style={{
              flex: 1, padding: 'clamp(8px, 1.4vw, 10px) clamp(10px, 2vw, 16px)', background: 'var(--brand)',
              border: 'none', borderRadius: 7, fontSize: 'clamp(12px, 1.3vw, 13.5px)', fontWeight: 600,
              color: '#fff', cursor: busy ? 'not-allowed' : 'pointer', opacity: busy ? .7 : 1,
            }}
          >
            {uploading ? 'Uploading…' : 'Edit'}
          </button>
        </div>
      </div>
    </div>
  );
}

export default function ProfilePage() {
  const token     = useAuthStore(s => s.token) ?? '';
  const storeUser = useAuthStore(s => s.user);
  const setAuth   = useAuthStore(s => s.setAuth);
  const navigate = useNavigate();
  const { showToast } = useToast();
  const photoInputRef = useRef<HTMLInputElement>(null);

  const [profile, setProfile]   = useState<ProfileData | null>(null);
  const [loading, setLoading]   = useState(true);
  const [editing, setEditing]   = useState(false);
  const [saving, setSaving]     = useState(false);
  const [uploading, setUploading] = useState(false);
  const [removing, setRemoving] = useState(false);
  const [showPhotoModal, setShowPhotoModal] = useState(false);
  const [form, setForm]         = useState<UpdateProfilePayload>({});

  const errors = {
    personalEmail: validateEmail(form.personalEmail ?? ''),
    phone: validatePhone(form.phone ?? '', 'Phone number'),
    emergencyContactPhone: validatePhone(form.emergencyContactPhone ?? '', 'Contact phone'),
    emergencyContactName: validateName(form.emergencyContactName ?? '', 'Contact name'),
  };
  const hasErrors = Object.values(errors).some(Boolean);

  useEffect(() => {
    profileApi.get(token)
      .then(p => {
        setProfile(p);
        setForm(toForm(p));
        // Keep the shared auth store's photo in sync in case it's stale/missing here too.
        if (storeUser && storeUser.photoDataUrl !== p.photoDataUrl) {
          setAuth(token, { ...storeUser, photoDataUrl: p.photoDataUrl });
        }
      })
      .catch(() => showToast('error', 'Failed to load profile'))
      .finally(() => setLoading(false));
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token]);

  function toForm(p: ProfileData): UpdateProfilePayload {
    return {
      phone: p.phone ?? '',
      dateOfBirth: p.dateOfBirth ?? '',
      gender: p.gender ?? '',
      personalEmail: p.personalEmail ?? '',
      address: p.address ?? '',
      emergencyContactName: p.emergencyContactName ?? '',
      emergencyContactPhone: p.emergencyContactPhone ?? '',
      workMode: p.workMode ?? 'ONSITE',
    };
  }

  async function handleSave() {
    if (errors.personalEmail) {
      showToast('error', errors.personalEmail);
      return;
    }
    if (errors.phone) {
      showToast('error', errors.phone);
      return;
    }
    if (errors.emergencyContactPhone) {
      showToast('error', errors.emergencyContactPhone);
      return;
    }
    if (errors.emergencyContactName) {
      showToast('error', errors.emergencyContactName);
      return;
    }
    setSaving(true);
    try {
      // Phone fields are normalized to bare digits here so the payload matches
      // the backend's `^\d{10}$` validation regardless of how the user typed it.
      const cleaned: UpdateProfilePayload = {};
      if (form.phone) cleaned.phone = digitsOnly(form.phone);
      if (form.dateOfBirth) cleaned.dateOfBirth = form.dateOfBirth;
      if (form.gender) cleaned.gender = form.gender;
      if (form.personalEmail) cleaned.personalEmail = form.personalEmail;
      if (form.address) cleaned.address = form.address;
      if (form.emergencyContactName) cleaned.emergencyContactName = form.emergencyContactName;
      if (form.emergencyContactPhone) cleaned.emergencyContactPhone = digitsOnly(form.emergencyContactPhone);
      if (form.workMode) cleaned.workMode = form.workMode;

      const updated = await profileApi.update(token, cleaned);
      setProfile(updated);
      setForm(toForm(updated));
      setEditing(false);
      showToast('success', 'Profile updated');
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Save failed');
    } finally {
      setSaving(false);
    }
  }

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
      showToast('success', 'Photo removed');
      setShowPhotoModal(false);
    } catch (err) {
      showToast('error', err instanceof Error ? err.message : 'Remove failed');
    } finally {
      setRemoving(false);
    }
  }

  function field(key: keyof UpdateProfilePayload) {
    return (String(form[key] ?? ''));
  }
  function set(key: keyof UpdateProfilePayload) {
    return (v: string) => setForm(f => ({ ...f, [key]: v }));
  }

  if (loading) {
    return (
      <div style={{ display: 'flex', justifyContent: 'center', alignItems: 'center', height: 200 }}>
        <div style={{ color: 'var(--txt-mut)', fontSize: 13 }}>Loading profile…</div>
      </div>
    );
  }

  if (!profile) return null;

  const initials = profile.fullName ? profile.fullName.split(' ').map(w => w[0]).join('').slice(0, 2).toUpperCase() : profile.email.slice(0, 2).toUpperCase();

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 20, maxWidth: 900 }}>
      {/* Page header */}
      <div>
        <h1 style={{ margin: 0, marginBottom: 4, fontSize: 20, fontWeight: 700, color: 'var(--txt)', fontFamily: '"Space Grotesk", sans-serif' }}>My Profile</h1>
        <p style={{ margin: 0, fontSize: 13, color: 'var(--txt-mut)' }}>View and update your personal information.</p>
      </div>

      {/* Avatar + identity card */}
      <div className="nf-profile-header" style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '20px 24px', display: 'flex', alignItems: 'center', gap: 20 }}>
        <div className="nf-profile-top" style={{ display: 'flex', alignItems: 'center', gap: 20, flex: 1, minWidth: 0 }}>
          <div style={{ position: 'relative', flexShrink: 0 }}>
            <button
              type="button"
              onClick={() => { if (profile.hasEmployeeRecord) setShowPhotoModal(true); }}
              aria-label={profile.hasEmployeeRecord ? 'View profile photo' : 'Profile photo'}
              style={{ padding: 0, border: 'none', background: 'none', cursor: profile.hasEmployeeRecord ? 'pointer' : 'default', display: 'block', borderRadius: '50%' }}
            >
              {profile.photoDataUrl ? (
                <img src={profile.photoDataUrl} alt="Profile" style={{ width: 72, height: 72, borderRadius: '50%', objectFit: 'cover', border: '2px solid var(--line2)' }} />
              ) : (
                <div style={{ width: 72, height: 72, borderRadius: '50%', background: '#B11116', display: 'grid', placeItems: 'center', color: '#fff', fontSize: 22, fontWeight: 700, border: '2px solid rgba(177,17,22,.4)' }}>
                  {initials}
                </div>
              )}
            </button>
            {profile.hasEmployeeRecord && (
              <>
                <button
                  onClick={() => setShowPhotoModal(true)}
                  aria-label="Change photo"
                  style={{ position: 'absolute', bottom: 0, right: 0, width: 24, height: 24, borderRadius: '50%', background: 'var(--brand)', border: '2px solid var(--panel)', display: 'grid', placeItems: 'center', cursor: 'pointer' }}
                >
                  <Camera size={11} color="#fff" aria-hidden />
                </button>
                <input ref={photoInputRef} type="file" accept="image/*" style={{ display: 'none' }} onChange={handlePhotoChange} />
              </>
            )}
          </div>

          <div style={{ flex: 1, minWidth: 0 }}>
            <div style={{ fontSize: 17, fontWeight: 700, color: 'var(--txt)', fontFamily: '"Space Grotesk", sans-serif', marginBottom: 3 }}>{profile.fullName}</div>
            <div style={{ fontSize: 12, color: 'var(--txt-mut)', marginBottom: 6 }}>{profile.email}</div>
            <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
              <span style={{ fontSize: 11, fontWeight: 600, padding: '2px 8px', borderRadius: 20, background: 'rgba(177,17,22,.18)', color: '#e4373d' }}>
                {ROLE_LABELS[profile.role] ?? profile.role}
              </span>
              <span style={{ fontSize: 11, fontWeight: 600, padding: '2px 8px', borderRadius: 20, background: 'rgba(47,182,124,.15)', color: 'var(--ok)' }}>
                {profile.active ? 'Active' : 'Inactive'}
              </span>
              <span style={{ fontSize: 11, fontWeight: 500, padding: '2px 8px', borderRadius: 20, background: 'rgba(107,114,128,.15)', color: 'var(--txt-dim)' }}>
                {profile.employeeCode}
              </span>
            </div>
          </div>
        </div>

        {profile.hasEmployeeRecord && (
          <div className="nf-profile-actions" style={{ display: 'flex', gap: 8, flexShrink: 0 }}>
            {editing ? (
              <>
                <button onClick={() => { setEditing(false); setForm(toForm(profile)); }}
                  style={{ padding: '7px 14px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, fontSize: 12.5, color: 'var(--txt-mut)', cursor: 'pointer' }}>
                  Cancel
                </button>
                <button onClick={handleSave} disabled={saving || hasErrors}
                  style={{ padding: '7px 16px', background: 'var(--brand)', border: 'none', borderRadius: 6, fontSize: 12.5, fontWeight: 600, color: '#fff', cursor: (saving || hasErrors) ? 'not-allowed' : 'pointer', opacity: (saving || hasErrors) ? .7 : 1 }}>
                  {saving ? 'Saving…' : 'Save Changes'}
                </button>
              </>
            ) : (
              <button onClick={() => setEditing(true)}
                style={{ padding: '7px 16px', background: 'var(--brand)', border: 'none', borderRadius: 6, fontSize: 12.5, fontWeight: 600, color: '#fff', cursor: 'pointer' }}>
                Edit Profile
              </button>
            )}
          </div>
        )}
      </div>

      <div className="nf-grid-2col-collapse" style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 20 }}>
        {/* Personal Information */}
        <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '20px 24px' }}>
          <SectionHeader title="Personal Information" />
          {editing ? (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
              <PhoneField label="Phone" value={field('phone')} onChange={set('phone')} placeholder="9876543210" error={errors.phone} />
              <EditField label="Date of Birth" value={field('dateOfBirth')} onChange={set('dateOfBirth')} type="date" />
              <div>
                <label style={{ display: 'block', fontSize: 11, fontWeight: 600, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.05em', marginBottom: 5 }}>Gender</label>
                <select value={field('gender')} onChange={e => set('gender')(e.target.value)} style={{ ...INPUT_STYLE }}>
                  <option value="">Select…</option>
                  {GENDERS.map(g => <option key={g} value={g}>{g}</option>)}
                </select>
              </div>
              <EditField label="Personal Email" value={field('personalEmail')} onChange={set('personalEmail')} type="email" placeholder="personal@email.com" error={errors.personalEmail} />
              <div>
                <label style={{ display: 'block', fontSize: 11, fontWeight: 600, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.05em', marginBottom: 5 }}>Address</label>
                <textarea value={field('address')} onChange={e => set('address')(stripEmoji(e.target.value))}
                  placeholder="Your address…" rows={3}
                  style={{ ...INPUT_STYLE, resize: 'vertical', fontFamily: 'inherit' }} />
              </div>
            </div>
          ) : (
            <div className="nf-grid-2col-collapse" style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
              <ReadField label="Phone" value={profile.phone} />
              <ReadField label="Date of Birth" value={profile.dateOfBirth} />
              <ReadField label="Gender" value={profile.gender} />
              <ReadField label="Personal Email" value={profile.personalEmail} />
              <div style={{ gridColumn: '1/-1' }}>
                <ReadField label="Address" value={profile.address} />
              </div>
            </div>
          )}
        </div>

        {/* Employment Information */}
        <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '20px 24px' }}>
          <SectionHeader title="Employment" badge={profile.role === 'SUPER_ADMIN' ? undefined : 'HR Managed'} />
          <div className="nf-grid-2col-collapse" style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
            <ReadField label="Employee Code" value={profile.employeeCode} />
            <ReadField label="Department" value={profile.departmentName} />
            <ReadField label="Designation" value={profile.designationName} />
            <ReadField label="Location" value={profile.locationName} />
            <ReadField label="Employment Type" value={profile.employmentType?.replace('_', ' ')} />
            <ReadField label="Joining Date" value={profile.joiningDate} />
            <ReadField label="Manager" value={profile.managerName} />
            <div>
              <div style={{ fontSize: 11, fontWeight: 600, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.05em', marginBottom: 4 }}>Work Mode</div>
              {editing ? (
                <select value={field('workMode')} onChange={e => set('workMode')(e.target.value)} style={{ ...INPUT_STYLE }}>
                  {WORK_MODES.map(m => <option key={m} value={m}>{m}</option>)}
                </select>
              ) : (
                <div style={{ fontSize: 13, color: 'var(--txt)' }}>{profile.workMode}</div>
              )}
            </div>
          </div>
        </div>
      </div>

      {/* Emergency Contact */}
      <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '20px 24px' }}>
        <SectionHeader title="Emergency Contact" />
        {editing ? (
          <div className="nf-grid-2col-collapse" style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 14 }}>
            <EditField label="Contact Name" value={field('emergencyContactName')} onChange={v => set('emergencyContactName')(nameCharsOnly(v))} placeholder="Full name" error={errors.emergencyContactName} />
            <PhoneField label="Contact Phone" value={field('emergencyContactPhone')} onChange={set('emergencyContactPhone')} placeholder="9876543210" error={errors.emergencyContactPhone} />
          </div>
        ) : (
          <div className="nf-grid-2col-collapse" style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
            <ReadField label="Contact Name" value={profile.emergencyContactName} />
            <ReadField label="Contact Phone" value={profile.emergencyContactPhone} />
          </div>
        )}
      </div>

      {/* Security */}
      <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '20px 24px' }}>
        <SectionHeader title="Security" />
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
          <div>
            <div style={{ fontSize: 13, color: 'var(--txt)', fontWeight: 500, marginBottom: 3 }}>Password</div>
            <div style={{ fontSize: 12, color: 'var(--txt-mut)' }}>Change your account password at any time.</div>
          </div>
          <button
            onClick={() => navigate('/change-password')}
            style={{ display: 'flex', alignItems: 'center', gap: 6, padding: '7px 14px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, fontSize: 12.5, color: 'var(--txt)', cursor: 'pointer' }}
          >
            <Lock size={13} aria-hidden />
            Change Password
          </button>
        </div>
        <div style={{ marginTop: 16, paddingTop: 16, borderTop: '1px solid var(--line)', display: 'flex', alignItems: 'center', gap: 8 }}>
          <Shield size={14} color="var(--txt-dim)" aria-hidden />
          <span style={{ fontSize: 12, color: 'var(--txt-mut)' }}>
            Role: <strong style={{ color: 'var(--txt)' }}>{ROLE_LABELS[profile.role] ?? profile.role}</strong>
            {' · '}Account status: <strong style={{ color: profile.active ? 'var(--ok)' : 'var(--risk)' }}>{profile.active ? 'Active' : 'Inactive'}</strong>
          </span>
        </div>
      </div>

      {showPhotoModal && profile.hasEmployeeRecord && (
        <PhotoModal
          photoDataUrl={profile.photoDataUrl}
          initials={initials}
          uploading={uploading}
          removing={removing}
          onEditClick={() => photoInputRef.current?.click()}
          onRemove={handleRemovePhoto}
          onClose={() => setShowPhotoModal(false)}
        />
      )}
    </div>
  );
}
