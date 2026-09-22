import { useState } from 'react';
import { useToast } from '../../../context/ToastContext';
import { profileApi, type ProfileData } from '../../../api/profile';
import { EditModal, EditField, ReadField } from '../shared';

// National ID and Bank Account Number are masked by the backend on read (e.g. "•••• •••• 5591")
// — this modal trusts that masking rather than re-deriving it client-side. Editing either field
// replaces the stored value entirely with whatever is typed here (the raw, unmasked value).
export function IdentityStatutoryModal({ profile, token, onClose, onSaved }: {
  profile: ProfileData;
  token: string;
  onClose: () => void;
  onSaved: (p: ProfileData) => void;
}) {
  const { showToast } = useToast();
  const [nationalId, setNationalId] = useState('');
  const [passportNumber, setPassportNumber] = useState(profile.passportNumber ?? '');
  const [passportExpiry, setPassportExpiry] = useState(profile.passportExpiry ?? '');
  const [bankAccountNumber, setBankAccountNumber] = useState('');
  const [bankName, setBankName] = useState(profile.bankName ?? '');
  const [bankIfsc, setBankIfsc] = useState(profile.bankIfsc ?? '');
  const [saving, setSaving] = useState(false);

  async function handleSave() {
    setSaving(true);
    try {
      const payload: Record<string, string> = { passportNumber, passportExpiry, bankName, bankIfsc };
      // Only send if the employee actually typed a new value — otherwise leave the masked value
      // stored server-side untouched instead of overwriting it with an empty string.
      if (nationalId) payload.nationalId = nationalId;
      if (bankAccountNumber) payload.bankAccountNumber = bankAccountNumber;
      const updated = await profileApi.update(token, payload);
      onSaved(updated);
      showToast('success', 'Identity & Statutory details updated');
      onClose();
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Save failed');
    } finally {
      setSaving(false);
    }
  }

  return (
    <EditModal title="Edit Identity & Statutory" onClose={onClose} onSave={handleSave} saving={saving} width={640}>
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }} className="nf-grid-2col-collapse">
        <div>
          <ReadField label="National ID (current)" value={profile.nationalId} />
          <div style={{ marginTop: 8 }}>
            <EditField label="Replace National ID" value={nationalId} onChange={setNationalId} placeholder="Enter new value…" />
          </div>
        </div>
        <div>
          <ReadField label="Bank Account Number (current)" value={profile.bankAccountNumber} />
          <div style={{ marginTop: 8 }}>
            <EditField label="Replace Bank Account Number" value={bankAccountNumber} onChange={setBankAccountNumber} placeholder="Enter new value…" />
          </div>
        </div>
        <EditField label="Passport Number" value={passportNumber} onChange={setPassportNumber} />
        <EditField label="Passport Expiry" value={passportExpiry} onChange={setPassportExpiry} type="date" />
        <div style={{ gridColumn: '1/-1' }}>
          <EditField label="Bank Name & IFSC" value={bankName} onChange={setBankName} placeholder="Bank name" />
        </div>
        <EditField label="IFSC Code" value={bankIfsc} onChange={v => setBankIfsc(v.toUpperCase())} />
      </div>
    </EditModal>
  );
}
