import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Lock, Shield, Trash2, Pencil, Plus } from 'lucide-react';
import { useToast } from '../../../context/ToastContext';
import { profileEducationApi, type ProfileData, type EducationEntry } from '../../../api/profile';
import { SectionHeader, ReadField, ROLE_LABELS } from '../shared';
import { PrimaryDetailsModal } from './PrimaryDetailsModal';
import { ContactDetailsModal } from './ContactDetailsModal';
import { AddressesModal } from './AddressesModal';
import { IdentityStatutoryModal } from './IdentityStatutoryModal';
import { EducationModal } from './EducationModal';

function SectionCard({ title, badge, onEdit, children }: {
  title: string; badge?: string; onEdit?: () => void; children: React.ReactNode;
}) {
  return (
    <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '20px 24px' }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 18 }}>
        <SectionHeader title={title} badge={badge} />
        {onEdit && (
          <button onClick={onEdit}
            style={{ padding: '5px 12px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, fontSize: 12, fontWeight: 600, color: 'var(--txt-mut)', cursor: 'pointer' }}>
            Edit
          </button>
        )}
      </div>
      {children}
    </div>
  );
}

function EducationCard({ token }: { token: string }) {
  const { showToast } = useToast();
  const [entries, setEntries] = useState<EducationEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [modalTarget, setModalTarget] = useState<EducationEntry | 'new' | null>(null);

  function reload() {
    setLoading(true);
    profileEducationApi.list(token).then(setEntries).catch(() => {}).finally(() => setLoading(false));
  }

  useEffect(reload, [token]);

  async function handleDelete(id: string) {
    try {
      await profileEducationApi.remove(token, id);
      setEntries(prev => prev.filter(e => e.id !== id));
      showToast('success', 'Education entry removed');
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Delete failed');
    }
  }

  return (
    <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '20px 24px' }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 18 }}>
        <SectionHeader title="Education" />
        <button onClick={() => setModalTarget('new')}
          style={{ display: 'flex', alignItems: 'center', gap: 5, padding: '5px 12px', background: 'var(--brand)', border: 'none', borderRadius: 6, fontSize: 12, fontWeight: 600, color: '#fff', cursor: 'pointer' }}>
          <Plus size={13} /> Add Education
        </button>
      </div>
      {loading ? (
        <div style={{ color: 'var(--txt-mut)', fontSize: 13 }}>Loading…</div>
      ) : entries.length === 0 ? (
        <div style={{ color: 'var(--txt-dim)', fontSize: 13 }}>No education records added yet.</div>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
          {entries.map(e => (
            <div key={e.id} style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 10, padding: '12px 14px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 8 }}>
              <div>
                <div style={{ fontSize: 13.5, fontWeight: 600, color: 'var(--txt)' }}>{e.degree}{e.fieldOfStudy ? ` · ${e.fieldOfStudy}` : ''}</div>
                <div style={{ fontSize: 12.5, color: 'var(--txt-mut)', marginTop: 2 }}>{e.institutionName}</div>
                <div style={{ fontSize: 11, color: 'var(--txt-dim)', marginTop: 2 }}>
                  {e.startDate ?? '—'} – {e.endDate ?? 'Present'}
                </div>
              </div>
              <div style={{ display: 'flex', gap: 6, flexShrink: 0 }}>
                <button onClick={() => setModalTarget(e)} aria-label="Edit"
                  style={{ background: 'none', border: '1px solid var(--line2)', borderRadius: 6, padding: 6, cursor: 'pointer', color: 'var(--txt-mut)', display: 'flex' }}>
                  <Pencil size={13} />
                </button>
                <button onClick={() => handleDelete(e.id)} aria-label="Delete"
                  style={{ background: 'none', border: '1px solid var(--line2)', borderRadius: 6, padding: 6, cursor: 'pointer', color: 'var(--risk)', display: 'flex' }}>
                  <Trash2 size={13} />
                </button>
              </div>
            </div>
          ))}
        </div>
      )}
      {modalTarget && (
        <EducationModal
          token={token}
          existing={modalTarget === 'new' ? null : modalTarget}
          onClose={() => setModalTarget(null)}
          onSaved={saved => {
            setEntries(prev => {
              const idx = prev.findIndex(e => e.id === saved.id);
              return idx >= 0 ? prev.map((e, i) => i === idx ? saved : e) : [saved, ...prev];
            });
          }}
        />
      )}
    </div>
  );
}

export function ProfileTab({ profile, token, onSaved }: { profile: ProfileData; token: string; onSaved: (p: ProfileData) => void }) {
  const navigate = useNavigate();
  const [modal, setModal] = useState<'primary' | 'contact' | 'addresses' | 'identity' | null>(null);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
      <SectionCard title="Primary Details" onEdit={() => setModal('primary')}>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 16 }} className="nf-grid-2col-collapse">
          <ReadField label="First Name" value={profile.firstName} />
          <ReadField label="Middle Name" value={profile.middleName} />
          <ReadField label="Last Name" value={profile.lastName} />
          <ReadField label="Date of Birth" value={profile.dateOfBirth} />
          <ReadField label="Gender" value={profile.gender} />
          <ReadField label="Marital Status" value={profile.maritalStatus} />
          <ReadField label="Emergency Contact Name" value={profile.emergencyContactName} />
          <ReadField label="Emergency Contact Relationship" value={profile.emergencyContactRelationship} />
          <ReadField label="Emergency Contact Phone" value={profile.emergencyContactPhone} />
        </div>
      </SectionCard>

      <SectionCard title="Contact Details" onEdit={() => setModal('contact')}>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 16 }} className="nf-grid-2col-collapse">
          <ReadField label="Work Email" value={profile.email} />
          <ReadField label="Personal Email" value={profile.personalEmail} />
          <ReadField label="Mobile Number" value={profile.phone} />
        </div>
      </SectionCard>

      <SectionCard title="Addresses" onEdit={() => setModal('addresses')}>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }} className="nf-grid-2col-collapse">
          <ReadField label="Current Address" value={profile.address} />
          <ReadField label="Permanent Address" value={profile.permanentAddress} />
        </div>
      </SectionCard>

      <EducationCard token={token} />

      <SectionCard title="Identity & Statutory" badge="PII Masked" onEdit={() => setModal('identity')}>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 16 }} className="nf-grid-2col-collapse">
          <ReadField label="National ID" value={profile.nationalId} />
          <ReadField label="Passport Number" value={profile.passportNumber} />
          <ReadField label="Passport Expiry" value={profile.passportExpiry} />
          <ReadField label="Bank Account Number" value={profile.bankAccountNumber} />
          <div style={{ gridColumn: 'span 2' }}>
            <ReadField label="Bank Name & IFSC" value={profile.bankName ? `${profile.bankName}${profile.bankIfsc ? ` · IFSC: ${profile.bankIfsc}` : ''}` : null} />
          </div>
        </div>
      </SectionCard>

      {/* Security — carried over from the previous flat profile page; not part of the prototype's
          5-tab scope, but has no other home in the new layout. */}
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

      {modal === 'primary' && <PrimaryDetailsModal profile={profile} token={token} onClose={() => setModal(null)} onSaved={onSaved} />}
      {modal === 'contact' && <ContactDetailsModal profile={profile} token={token} onClose={() => setModal(null)} onSaved={onSaved} />}
      {modal === 'addresses' && <AddressesModal profile={profile} token={token} onClose={() => setModal(null)} onSaved={onSaved} />}
      {modal === 'identity' && <IdentityStatutoryModal profile={profile} token={token} onClose={() => setModal(null)} onSaved={onSaved} />}
    </div>
  );
}
