import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Camera, Lock, Shield } from 'lucide-react';
import { profileApi, type ProfileData, type UpdateProfilePayload } from '../api/profile';
import { useAuthStore } from '../store/authStore';
import { useToast } from '../context/ToastContext';

const WORK_MODES = ['ONSITE', 'HYBRID', 'REMOTE'] as const;
const GENDERS = ['Male', 'Female', 'Non-binary', 'Prefer not to say'];

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

function EditField({ label, value, onChange, type = 'text', placeholder }: {
  label: string;
  value: string;
  onChange: (v: string) => void;
  type?: string;
  placeholder?: string;
}) {
  return (
    <div>
      <label style={{ display: 'block', fontSize: 11, fontWeight: 600, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.05em', marginBottom: 5 }}>{label}</label>
      <input type={type} value={value} onChange={e => onChange(e.target.value)} placeholder={placeholder}
        style={INPUT_STYLE} />
    </div>
  );
}

export default function ProfilePage() {
  const token    = useAuthStore(s => s.token) ?? '';
  const navigate = useNavigate();
  const { showToast } = useToast();
  const photoInputRef = useRef<HTMLInputElement>(null);

  const [profile, setProfile]   = useState<ProfileData | null>(null);
  const [loading, setLoading]   = useState(true);
  const [editing, setEditing]   = useState(false);
  const [saving, setSaving]     = useState(false);
  const [uploading, setUploading] = useState(false);
  const [form, setForm]         = useState<UpdateProfilePayload>({});

  useEffect(() => {
    profileApi.get(token)
      .then(p => { setProfile(p); setForm(toForm(p)); })
      .catch(() => showToast('error', 'Failed to load profile'))
      .finally(() => setLoading(false));
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
    if (form.personalEmail && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.personalEmail)) {
      showToast('error', 'Personal email format is invalid.');
      return;
    }
    const digitsOnly = (v: string) => v.replace(/\D/g, '');
    if (form.phone && digitsOnly(form.phone).length !== 10) {
      showToast('error', 'Phone number must be exactly 10 digits.');
      return;
    }
    if (form.emergencyContactPhone && digitsOnly(form.emergencyContactPhone).length !== 10) {
      showToast('error', 'Contact phone must be exactly 10 digits.');
      return;
    }
    setSaving(true);
    try {
      const cleaned: UpdateProfilePayload = {};
      if (form.phone !== undefined) cleaned.phone = form.phone;
      if (form.dateOfBirth) cleaned.dateOfBirth = form.dateOfBirth;
      if (form.gender) cleaned.gender = form.gender;
      if (form.personalEmail) cleaned.personalEmail = form.personalEmail;
      if (form.address) cleaned.address = form.address;
      if (form.emergencyContactName) cleaned.emergencyContactName = form.emergencyContactName;
      if (form.emergencyContactPhone) cleaned.emergencyContactPhone = form.emergencyContactPhone;
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
      showToast('success', 'Photo updated');
    } catch (err) {
      showToast('error', err instanceof Error ? err.message : 'Upload failed');
    } finally {
      setUploading(false);
      if (photoInputRef.current) photoInputRef.current.value = '';
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
      <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '20px 24px', display: 'flex', alignItems: 'center', gap: 20 }}>
        <div style={{ position: 'relative', flexShrink: 0 }}>
          {profile.photoDataUrl ? (
            <img src={profile.photoDataUrl} alt="Profile" style={{ width: 72, height: 72, borderRadius: '50%', objectFit: 'cover', border: '2px solid var(--line2)' }} />
          ) : (
            <div style={{ width: 72, height: 72, borderRadius: '50%', background: '#B11116', display: 'grid', placeItems: 'center', color: '#fff', fontSize: 22, fontWeight: 700, border: '2px solid rgba(177,17,22,.4)' }}>
              {initials}
            </div>
          )}
          {profile.hasEmployeeRecord && (
            <>
              <button
                onClick={() => photoInputRef.current?.click()}
                disabled={uploading}
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

        {profile.hasEmployeeRecord && (
          <div style={{ display: 'flex', gap: 8 }}>
            {editing ? (
              <>
                <button onClick={() => { setEditing(false); setForm(toForm(profile)); }}
                  style={{ padding: '7px 14px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, fontSize: 12.5, color: 'var(--txt-mut)', cursor: 'pointer' }}>
                  Cancel
                </button>
                <button onClick={handleSave} disabled={saving}
                  style={{ padding: '7px 16px', background: 'var(--brand)', border: 'none', borderRadius: 6, fontSize: 12.5, fontWeight: 600, color: '#fff', cursor: saving ? 'not-allowed' : 'pointer', opacity: saving ? .7 : 1 }}>
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

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 20 }}>
        {/* Personal Information */}
        <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '20px 24px' }}>
          <SectionHeader title="Personal Information" />
          {editing ? (
            <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
              <EditField label="Phone" value={field('phone')} onChange={set('phone')} placeholder="+91 99999 99999" />
              <EditField label="Date of Birth" value={field('dateOfBirth')} onChange={set('dateOfBirth')} type="date" />
              <div>
                <label style={{ display: 'block', fontSize: 11, fontWeight: 600, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.05em', marginBottom: 5 }}>Gender</label>
                <select value={field('gender')} onChange={e => set('gender')(e.target.value)} style={{ ...INPUT_STYLE }}>
                  <option value="">Select…</option>
                  {GENDERS.map(g => <option key={g} value={g}>{g}</option>)}
                </select>
              </div>
              <EditField label="Personal Email" value={field('personalEmail')} onChange={set('personalEmail')} type="email" placeholder="personal@email.com" />
              <div>
                <label style={{ display: 'block', fontSize: 11, fontWeight: 600, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.05em', marginBottom: 5 }}>Address</label>
                <textarea value={field('address')} onChange={e => set('address')(e.target.value)}
                  placeholder="Your address…" rows={3}
                  style={{ ...INPUT_STYLE, resize: 'vertical', fontFamily: 'inherit' }} />
              </div>
            </div>
          ) : (
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
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
          <SectionHeader title="Employment" badge="HR Managed" />
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
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
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 14 }}>
            <EditField label="Contact Name" value={field('emergencyContactName')} onChange={set('emergencyContactName')} placeholder="Full name" />
            <EditField label="Contact Phone" value={field('emergencyContactPhone')} onChange={set('emergencyContactPhone')} placeholder="+91 99999 99999" />
          </div>
        ) : (
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
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
    </div>
  );
}
