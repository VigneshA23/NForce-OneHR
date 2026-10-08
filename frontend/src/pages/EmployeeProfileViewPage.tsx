import { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  Mail, Phone as PhoneIcon, MapPin, Hash, UserRound, IdCard, Briefcase, TrendingUp, ArrowLeft,
  Building2, Clock, Network, Sparkles, Award, BookOpen, CheckCircle2, Target, ExternalLink,
} from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import { useAuthStore } from '../store/authStore';
import { useToast } from '../context/ToastContext';
import { useAccentColor, type AccentColor } from '../lib/accentColor';
import profileBannerRed from '../assets/profile-banner-red.png';
import profileBannerBlue from '../assets/profile-banner-blue.png';
import profileBannerPink from '../assets/profile-banner-pink.png';
import profileBannerGreen from '../assets/profile-banner-green.png';
import profileBannerPurple from '../assets/profile-banner-purple.png';
import {
  ProfileCoverBanner, DesignationLine, ROLE_LABELS, SectionHeader, ReadField,
} from './profile/shared';
import { hierarchyApi, type PersonCard } from '../api/hierarchy';
import { directoryProfileApi, type DirectoryProfileData } from '../api/directoryProfile';
import type { ProfileData } from '../api/profile';

type TabKey = 'about' | 'profile' | 'job' | 'skills';
const TABS: { key: TabKey; label: string; icon: LucideIcon }[] = [
  { key: 'about', label: 'About', icon: UserRound },
  { key: 'profile', label: 'Profile', icon: IdCard },
  { key: 'job', label: 'Job', icon: Briefcase },
  { key: 'skills', label: 'Skills', icon: TrendingUp },
];

function Card({ title, icon, badge, children }: { title: string; icon?: LucideIcon; badge?: string; children: React.ReactNode }) {
  return (
    <div className="nf-profile-card" style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '20px 24px' }}>
      <SectionHeader title={title} icon={icon} badge={badge} />
      {children}
    </div>
  );
}

function AboutContent({ profile }: { profile: ProfileData }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
      <Card title="Personal Summary & Bio" icon={UserRound}>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }} className="nf-grid-2col-collapse">
          <ReadField label="Display Name" value={profile.fullName} />
          <div style={{ gridColumn: '1/-1' }}>
            <ReadField label="About Me / Bio" value={profile.bio} />
          </div>
        </div>
      </Card>
      <Card title="Employment & Organization Context" icon={Building2}>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 16 }} className="nf-grid-2col-collapse">
          <ReadField label="Job Title" value={profile.designationName} />
          <ReadField label="Department" value={profile.departmentName} />
          <ReadField label="Work Email" value={profile.email} />
          <ReadField label="Work Phone" value={profile.phone} />
          <ReadField label="Reporting Manager" value={profile.managerName} />
          <ReadField label="Location" value={profile.locationName} />
          <div style={{ gridColumn: '1/-1' }}>
            <ReadField label="Date of Joining" value={profile.joiningDate} />
          </div>
        </div>
      </Card>
    </div>
  );
}

function ProfileContent({ profile, hasSensitiveAccess }: { profile: ProfileData; hasSensitiveAccess: boolean }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
      <Card title="Primary Details" icon={IdCard}>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 16 }} className="nf-grid-2col-collapse">
          <ReadField label="First Name" value={profile.firstName} />
          <ReadField label="Middle Name" value={profile.middleName} />
          <ReadField label="Last Name" value={profile.lastName} />
          {hasSensitiveAccess && <ReadField label="Date of Birth" value={profile.dateOfBirth} />}
          {hasSensitiveAccess && <ReadField label="Gender" value={profile.gender} />}
          {hasSensitiveAccess && <ReadField label="Marital Status" value={profile.maritalStatus} />}
        </div>
      </Card>

      {!hasSensitiveAccess ? (
        <div style={{ fontSize: 12.5, color: 'var(--txt-dim)', fontStyle: 'italic', padding: '0 4px' }}>
          Personal details (contact, address, emergency contact, identity & banking) are restricted to HR Admin and Super Admin.
        </div>
      ) : (
        <>
          <Card title="Emergency Contact" icon={Mail}>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 16 }} className="nf-grid-2col-collapse">
              <ReadField label="Name" value={profile.emergencyContactName} />
              <ReadField label="Relationship" value={profile.emergencyContactRelationship} />
              <ReadField label="Phone" value={profile.emergencyContactPhone} />
            </div>
          </Card>

          <Card title="Contact Details" icon={Mail}>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 16 }} className="nf-grid-2col-collapse">
              <ReadField label="Work Email" value={profile.email} />
              <ReadField label="Personal Email" value={profile.personalEmail} />
              <ReadField label="Mobile Number" value={profile.phone} />
            </div>
          </Card>

          <Card title="Addresses" icon={MapPin}>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }} className="nf-grid-2col-collapse">
              <ReadField label="Current Address" value={profile.address} />
              <ReadField label="Permanent Address" value={profile.permanentAddress} />
            </div>
          </Card>

          <Card title="Identity & Statutory" badge="PII Masked" icon={IdCard}>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 16 }} className="nf-grid-2col-collapse">
              <ReadField label="National ID" value={profile.nationalId} />
              <ReadField label="Passport Number" value={profile.passportNumber} />
              <ReadField label="Passport Expiry" value={profile.passportExpiry} />
              <ReadField label="Bank Account Number" value={profile.bankAccountNumber} />
              <div style={{ gridColumn: 'span 2' }}>
                <ReadField label="Bank Name & IFSC" value={profile.bankName ? `${profile.bankName}${profile.bankIfsc ? ` · IFSC: ${profile.bankIfsc}` : ''}` : null} />
              </div>
            </div>
          </Card>
        </>
      )}
    </div>
  );
}

function JobContent({ profile, l2Manager, directReports }: {
  profile: ProfileData; l2Manager: PersonCard | null | undefined; directReports: PersonCard[];
}) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
      <Card title="Job Details" icon={Briefcase}>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 16 }} className="nf-grid-2col-collapse">
          <ReadField label="Employee ID" value={profile.employeeCode} />
          <ReadField label="Job Title / Job Code" value={profile.designationName ? `${profile.designationName}${profile.jobCode ? ` (${profile.jobCode})` : ''}` : profile.jobCode} />
          <ReadField label="Employment Type" value={profile.employmentType?.replace('_', ' ')} />
          <ReadField label="Employment Status" value={profile.active ? 'Active' : 'Inactive'} />
          <ReadField label="Date of Joining" value={profile.joiningDate} />
          <ReadField label="Confirmation Date" value={profile.confirmationDate} />
          <ReadField label="Reporting Manager" value={profile.managerName} />
          <ReadField label="Work Mode" value={profile.workMode} />
        </div>
      </Card>

      <Card title="Employee Time" icon={Clock}>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 16 }} className="nf-grid-2col-collapse">
          <ReadField label="Shift / Work Schedule" value={profile.shiftName} />
          <ReadField label="Attendance Penalization Policy" value={profile.attendancePenalizationPolicyName} />
        </div>
      </Card>

      <Card title="Organization" icon={Network}>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 16 }} className="nf-grid-2col-collapse">
          <ReadField label="Business Unit / Division" value={profile.businessUnitName} />
          <ReadField label="Department" value={profile.departmentName} />
          <ReadField label="Location / Office" value={profile.locationName} />
          <ReadField label="Reports To" value={profile.managerName} />
          <ReadField label="Manager of Manager (L2)" value={l2Manager === undefined ? undefined : l2Manager?.name ?? null} />
          <div style={{ gridColumn: '1/-1' }}>
            <div style={{ fontSize: 11, fontWeight: 600, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.05em', marginBottom: 6 }}>Direct Reports</div>
            {directReports.length === 0 ? (
              <div style={{ fontSize: 13, color: 'var(--txt-dim)' }}>None</div>
            ) : (
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                {directReports.map(r => (
                  <span key={r.id} style={{ fontSize: 11.5, fontWeight: 600, padding: '3px 9px', borderRadius: 20, background: 'var(--raised)', border: '1px solid var(--line2)', color: 'var(--txt)' }}>
                    {r.name}
                  </span>
                ))}
              </div>
            )}
          </div>
        </div>
      </Card>
    </div>
  );
}

const LEVEL_TONE: Record<string, string> = {
  Beginner: 'var(--txt-dim)', Intermediate: 'var(--info)', Advanced: 'var(--brand-bright)', Expert: 'var(--ok)',
};
function parseLocalDate(d: string): Date {
  const [y, m, day] = d.split('-').map(Number);
  return new Date(y, m - 1, day);
}
function fmtDate(d: string | null): string {
  if (!d) return '—';
  return parseLocalDate(d).toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' });
}
function certificateStatus(expiry: string | null): { label: string; tone: string } | null {
  if (!expiry) return null;
  const days = (parseLocalDate(expiry).getTime() - Date.now()) / 86_400_000;
  if (days < 0) return { label: 'Expired', tone: 'var(--risk)' };
  if (days <= 60) return { label: 'Expiring Soon', tone: 'var(--warn)' };
  return { label: 'Active', tone: 'var(--ok)' };
}
function Pill({ tone, children }: { tone: string; children: React.ReactNode }) {
  return (
    <span style={{ fontSize: 10, fontWeight: 700, padding: '2px 8px', borderRadius: 20, background: `color-mix(in srgb, ${tone} 15%, transparent)`, color: tone, whiteSpace: 'nowrap' }}>
      {children}
    </span>
  );
}

function SkillsContent({ data }: { data: DirectoryProfileData }) {
  const [tab, setTab] = useState<'skills' | 'certificates' | 'learning'>('skills');
  const tabs: { key: typeof tab; label: string; icon: LucideIcon; count: number }[] = [
    { key: 'skills', label: 'Skills', icon: Sparkles, count: data.skills.length },
    { key: 'certificates', label: 'Certificates', icon: Award, count: data.certificates.length },
    { key: 'learning', label: 'Learning', icon: BookOpen, count: data.learningEntries.length },
  ];

  return (
    <div className="nf-profile-card" style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '20px 24px' }}>
      <div style={{ display: 'flex', gap: 6, marginBottom: 18 }}>
        {tabs.map(t => {
          const Icon = t.icon;
          return (
            <button key={t.key} onClick={() => setTab(t.key)} style={{
              display: 'flex', alignItems: 'center', gap: 6, padding: '6px 14px', borderRadius: 20, fontSize: 12, fontWeight: 600, cursor: 'pointer',
              border: '1px solid ' + (tab === t.key ? 'var(--brand)' : 'var(--line2)'),
              background: tab === t.key ? 'color-mix(in srgb, var(--brand) 14%, transparent)' : 'var(--raised)',
              color: tab === t.key ? 'var(--brand-bright)' : 'var(--txt-mut)',
            }}>
              <Icon size={13} /> {t.label} <span style={{ opacity: .7 }}>({t.count})</span>
            </button>
          );
        })}
      </div>

      {tab === 'skills' && (
        data.skills.length === 0 ? <div style={{ color: 'var(--txt-dim)', fontSize: 13 }}>Nothing added yet.</div> : (
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
            {data.skills.map(s => (
              <div key={s.id} style={{ display: 'flex', alignItems: 'center', gap: 7, padding: '6px 12px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 20 }}>
                <span style={{ fontSize: 12.5, fontWeight: 600, color: 'var(--txt)' }}>{s.skillName}</span>
                <span style={{ fontSize: 9.5, fontWeight: 700, padding: '2px 7px', borderRadius: 10, background: `color-mix(in srgb, ${LEVEL_TONE[s.proficiencyLevel]} 16%, transparent)`, color: LEVEL_TONE[s.proficiencyLevel] }}>
                  {s.proficiencyLevel}
                </span>
              </div>
            ))}
          </div>
        )
      )}

      {tab === 'certificates' && (
        data.certificates.length === 0 ? <div style={{ color: 'var(--txt-dim)', fontSize: 13 }}>Nothing added yet.</div> : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
            {data.certificates.map(c => {
              const status = certificateStatus(c.expiryDate);
              return (
                <div key={c.id} style={{ padding: '12px 14px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 8 }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 7, flexWrap: 'wrap' }}>
                    <Award size={13} color="var(--info)" />
                    <span style={{ fontSize: 13, fontWeight: 600, color: 'var(--txt)' }}>{c.name}</span>
                    {status && <Pill tone={status.tone}>{status.label}</Pill>}
                  </div>
                  <div style={{ fontSize: 11.5, color: 'var(--txt-mut)', marginTop: 3 }}>
                    {c.issuingOrganization ?? 'Issuer not specified'}{c.credentialId ? ` · ID ${c.credentialId}` : ''}
                  </div>
                  <div style={{ fontSize: 11, color: 'var(--txt-dim)', marginTop: 3 }}>
                    {c.issueDate ? `Issued ${fmtDate(c.issueDate)}` : ''}{c.expiryDate ? `${c.issueDate ? ' · ' : ''}Expires ${fmtDate(c.expiryDate)}` : ''}
                  </div>
                  {c.credentialUrl && (
                    <a href={c.credentialUrl} target="_blank" rel="noreferrer" style={{ display: 'inline-flex', alignItems: 'center', gap: 4, marginTop: 6, fontSize: 11.5, fontWeight: 600, color: 'var(--brand-bright)', textDecoration: 'none' }}>
                      View credential <ExternalLink size={10} />
                    </a>
                  )}
                </div>
              );
            })}
          </div>
        )
      )}

      {tab === 'learning' && (
        data.learningEntries.length === 0 ? <div style={{ color: 'var(--txt-dim)', fontSize: 13 }}>Nothing added yet.</div> : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
            {data.learningEntries.map(e => {
              const Icon = e.status === 'Completed' ? CheckCircle2 : e.status === 'Planned' ? Target : Clock;
              const color = e.status === 'Completed' ? 'var(--ok)' : e.status === 'Planned' ? 'var(--info)' : 'var(--warn)';
              return (
                <div key={e.id} style={{ padding: '12px 14px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 8 }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 7, flexWrap: 'wrap' }}>
                    <Icon size={13} color={color} />
                    <span style={{ fontSize: 13, fontWeight: 600, color: 'var(--txt)' }}>{e.title}</span>
                    <Pill tone={color}>{e.status}</Pill>
                  </div>
                  <div style={{ fontSize: 11.5, color: 'var(--txt-mut)', marginTop: 3 }}>
                    {e.learningType}{e.provider ? ` · ${e.provider}` : ''}
                    {e.completedDate ? ` · Completed ${fmtDate(e.completedDate)}` : e.startDate ? ` · Started ${fmtDate(e.startDate)}` : ''}
                  </div>
                  {e.description && <div style={{ fontSize: 12, color: 'var(--txt-mut)', marginTop: 6 }}>{e.description}</div>}
                </div>
              );
            })}
          </div>
        )
      )}
    </div>
  );
}

export default function EmployeeProfileViewPage() {
  const { userId } = useParams<{ userId: string }>();
  const navigate = useNavigate();
  const token = useAuthStore(s => s.token) ?? '';
  const { showToast } = useToast();
  const { accent } = useAccentColor();

  const [data, setData] = useState<DirectoryProfileData | null>(null);
  const [loading, setLoading] = useState(true);
  const [activeTab, setActiveTab] = useState<TabKey>('about');
  const [l2Manager, setL2Manager] = useState<PersonCard | null | undefined>(undefined);
  const [directReports, setDirectReports] = useState<PersonCard[]>([]);

  useEffect(() => {
    if (!userId) return;
    setLoading(true);
    directoryProfileApi.get(token, userId).then(setData).catch(e => {
      showToast('error', e instanceof Error ? e.message : 'Could not load this profile');
    }).finally(() => setLoading(false));
  }, [token, userId]);

  useEffect(() => {
    if (!userId) return;
    let cancelled = false;
    hierarchyApi.getContext(token, userId).then(ctx => {
      if (cancelled) return;
      setDirectReports(ctx.directReports);
      if (!ctx.manager) { setL2Manager(null); return; }
      return hierarchyApi.getContext(token, ctx.manager.id).then(l1 => { if (!cancelled) setL2Manager(l1.manager); });
    }).catch(() => { if (!cancelled) setL2Manager(null); });
    return () => { cancelled = true; };
  }, [token, userId]);

  if (loading || !data) {
    return (
      <div style={{ padding: 40, textAlign: 'center' }}>
        <div style={{ color: 'var(--txt-mut)', fontSize: 13 }}>Loading profile…</div>
      </div>
    );
  }

  const p = data.profile;
  const displayName = p.fullName;
  const initials = displayName ? displayName.split(' ').map(w => w[0]).join('').slice(0, 2).toUpperCase() : p.email.slice(0, 2).toUpperCase();
  // From the backend (ProfileService#hasSensitiveAccess), not inferred from field nullness here —
  // an authorized HR/Super Admin viewer looking at an employee who never filled these fields in
  // would otherwise be indistinguishable from an unauthorized viewer seeing redacted nulls.
  const hasSensitiveAccess = data.hasSensitiveAccess;

  const BANNER_IMAGES: Partial<Record<AccentColor, string>> = {
    red: profileBannerRed, blue: profileBannerBlue, pink: profileBannerPink, green: profileBannerGreen, purple: profileBannerPurple,
  };
  const bannerImage = BANNER_IMAGES[accent];
  const heroBackground = bannerImage
    ? `url(${bannerImage}) top center / cover no-repeat`
    : 'linear-gradient(135deg, var(--brand-deep) 0%, var(--brand) 100%)';

  const isIn = p.attendanceStatus === 'IN';
  const subMetaItems: { icon: LucideIcon; label: string; value: string }[] = [
    { icon: Mail, label: 'Work Email', value: p.email },
    { icon: PhoneIcon, label: 'Mobile Number', value: p.phone || '—' },
    { icon: MapPin, label: 'Location', value: p.locationName || '—' },
    { icon: Hash, label: 'Employee Code', value: p.employeeCode },
  ];

  // Mirrors ProfilePage's own visibleTabs: Skills is self-service career tracking for individual
  // contributors — Super Admin/HR Admin don't have it on their own profile, so viewing their
  // profile through search/directory shouldn't show it either.
  const visibleTabs = (p.role === 'SUPER_ADMIN' || p.role === 'HR_ADMIN')
    ? TABS.filter(t => t.key !== 'skills')
    : TABS;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
      <button onClick={() => navigate(-1)} style={{
        display: 'flex', alignItems: 'center', gap: 6, alignSelf: 'flex-start',
        background: 'none', border: 'none', color: 'var(--txt-mut)', fontSize: 12.5, fontWeight: 600, cursor: 'pointer', padding: 0,
      }}>
        <ArrowLeft size={14} /> Back
      </button>

      <div className="nf-profile-header" style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, overflow: 'hidden' }}>
        <ProfileCoverBanner
          coverDataUrl={p.coverDataUrl}
          themeBackground={heroBackground}
          editable={false}
          uploading={false}
          removing={false}
          onUpload={() => {}}
          onRemove={() => {}}
        />

        <div className="nf-profile-row" style={{ display: 'flex', alignItems: 'center', gap: 20, padding: '0 24px 20px' }}>
          <div className="nf-profile-top" style={{ display: 'flex', alignItems: 'flex-end', gap: 20, flex: 1, minWidth: 0 }}>
            <div style={{ position: 'relative', flexShrink: 0, marginTop: 'clamp(-56px, -7vw, -44px)' }}>
              {p.photoDataUrl ? (
                <img src={p.photoDataUrl} alt="Profile" style={{ width: 104, height: 104, borderRadius: '50%', objectFit: 'cover', border: '4px solid var(--panel)', boxShadow: '0 2px 10px rgba(0,0,0,.25)' }} />
              ) : (
                <div style={{ width: 104, height: 104, borderRadius: '50%', background: 'var(--brand)', display: 'grid', placeItems: 'center', color: '#fff', fontSize: 30, fontWeight: 700, border: '4px solid var(--panel)', boxShadow: '0 2px 10px rgba(0,0,0,.25)' }}>
                  {initials}
                </div>
              )}
            </div>

            <div style={{ flex: 1, minWidth: 0, paddingBottom: 4 }}>
              <div style={{ fontSize: 26, fontWeight: 800, color: 'var(--txt)', fontFamily: 'Inter, sans-serif', marginBottom: 3 }}>{displayName}</div>
              <DesignationLine text={p.designationName ?? ROLE_LABELS[p.role] ?? p.role} />
              <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', alignItems: 'center' }}>
                <span style={{ fontSize: 11, fontWeight: 600, padding: '2px 8px', borderRadius: 20, background: 'color-mix(in srgb, var(--brand) 16%, transparent)', color: 'var(--brand-bright)' }}>
                  {ROLE_LABELS[p.role] ?? p.role}
                </span>
                <span style={{ fontSize: 11, fontWeight: 600, padding: '2px 8px', borderRadius: 20, background: 'rgba(47,182,124,.15)', color: 'var(--ok)' }}>
                  {p.active ? 'Active' : 'Inactive'}
                </span>
                <span style={{
                  display: 'flex', alignItems: 'center', gap: 5, fontSize: 11, fontWeight: 600, padding: '2px 8px', borderRadius: 20,
                  background: isIn ? 'rgba(47,182,124,.15)' : 'rgba(228,55,61,.15)',
                  color: isIn ? 'var(--ok)' : 'var(--risk)',
                }}>
                  <span style={{ width: 6, height: 6, borderRadius: '50%', background: 'currentColor' }} aria-hidden />
                  {isIn ? 'In' : 'Out'}
                </span>
                <span style={{ fontSize: 11, fontWeight: 500, padding: '2px 8px', borderRadius: 20, background: 'rgba(107,114,128,.15)', color: 'var(--txt-dim)' }}>
                  {p.employeeCode}
                </span>
              </div>
            </div>
          </div>
        </div>

        <div style={{ display: 'flex', flexWrap: 'wrap', gap: '10px 28px', padding: '14px 24px', borderTop: '1px solid var(--line)' }}>
          {subMetaItems.map(({ icon: Icon, label, value }) => (
            <div key={label} style={{ display: 'flex', alignItems: 'center', gap: 7 }}>
              <Icon size={13} color="var(--txt-dim)" aria-hidden />
              <span style={{ fontSize: 12.5, color: 'var(--txt-mut)' }}>{value}</span>
            </div>
          ))}
        </div>

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

      {activeTab === 'about' && <AboutContent profile={p} />}
      {activeTab === 'profile' && <ProfileContent profile={p} hasSensitiveAccess={hasSensitiveAccess} />}
      {activeTab === 'job' && <JobContent profile={p} l2Manager={l2Manager} directReports={directReports} />}
      {activeTab === 'skills' && <SkillsContent data={data} />}
    </div>
  );
}
