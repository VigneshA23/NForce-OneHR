import { useState } from 'react';
import { useToast } from '../../../context/ToastContext';
import { profileApi, type ProfileData, type UpdateProfilePayload } from '../../../api/profile';
import { EditModal, EditField, ReadField } from '../shared';

// National ID, Bank Account Number, Passport Number and IFSC are masked by the backend on read (e.g. "•••• •••• 5591")
// — this modal trusts that masking rather than re-deriving it client-side. Each is edited with a "Replace ..." box:
// only a value actually typed here is sent, and it replaces the stored one once HR approves it.
export function IdentityStatutoryModal({ profile, token, onClose, onSaved }: {
  profile: ProfileData;
  token: string;
  onClose: () => void;
  onSaved: (p: ProfileData) => void;
}) {
  const { showToast } = useToast();
  const [nationalId, setNationalId] = useState('');
  const [passportNumber, setPassportNumber] = useState('');
  const [passportExpiry, setPassportExpiry] = useState(profile.passportExpiry ?? '');
  const [bankAccountNumber, setBankAccountNumber] = useState('');
  const [bankName, setBankName] = useState(profile.bankName ?? '');
  const [bankIfsc, setBankIfsc] = useState('');
  const [panNumber, setPanNumber] = useState('');
  const [uanNumber, setUanNumber] = useState('');
  const [esiNumber, setEsiNumber] = useState('');
  const [accountHolderName, setAccountHolderName] = useState(profile.accountHolderName ?? '');
  const [pfEnabled, setPfEnabled] = useState(profile.pfEnabled);
  const [pfNumber, setPfNumber] = useState('');
  const [pfRegistrationDate, setPfRegistrationDate] = useState(profile.pfRegistrationDate ?? '');
  const [nameAsPerPf, setNameAsPerPf] = useState(profile.nameAsPerPf ?? '');
  const [esiEnabled, setEsiEnabled] = useState(profile.esiEnabled);
  const [professionalTaxNumber, setProfessionalTaxNumber] = useState('');
  const [saving, setSaving] = useState(false);

  async function handleSave() {
    setSaving(true);
    try {
      const payload: UpdateProfilePayload = {
        bankName,
        // A cleared field sends no passportExpiry at all, plus the explicit clear flag — see
        // UpdateProfilePayload's doc comment for why "" / omitted alone isn't enough.
        ...(passportExpiry ? { passportExpiry } : { clearPassportExpiry: true }),
        accountHolderName,
        pfEnabled,
        nameAsPerPf,
        esiEnabled,
      };
      // Only send if the employee actually typed a new value — otherwise leave the masked value
      // stored server-side untouched instead of overwriting it with an empty string.
      if (nationalId) payload.nationalId = nationalId;
      if (passportNumber) payload.passportNumber = passportNumber;
      if (bankIfsc) payload.bankIfsc = bankIfsc;
      if (bankAccountNumber) payload.bankAccountNumber = bankAccountNumber;
      if (panNumber) payload.panNumber = panNumber;
      if (uanNumber) payload.uanNumber = uanNumber;
      if (esiNumber) payload.esiNumber = esiNumber;
      if (pfNumber) payload.pfNumber = pfNumber;
      if (pfRegistrationDate) payload.pfRegistrationDate = pfRegistrationDate;
      if (professionalTaxNumber) payload.professionalTaxNumber = professionalTaxNumber;
      const updated = await profileApi.update(token, payload);
      onSaved(updated);
      // Bank and ID changes don't apply on save: HR approves them first, so say so.
      showToast('success', updated.pendingSensitiveChange
        ? 'Sent for HR approval. Your bank and ID changes take effect once approved.'
        : 'Identity & Statutory details updated');
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
        <div>
          <ReadField label="Passport Number (current)" value={profile.passportNumber} />
          <div style={{ marginTop: 8 }}>
            <EditField label="Replace Passport Number" value={passportNumber} onChange={setPassportNumber} placeholder="Enter new value…" />
          </div>
        </div>
        <EditField label="Passport Expiry" value={passportExpiry} onChange={setPassportExpiry} type="date" />
        <EditField label="Bank Name" value={bankName} onChange={setBankName} placeholder="Bank name" />
        <div>
          <ReadField label="IFSC Code (current)" value={profile.bankIfsc} />
          <div style={{ marginTop: 8 }}>
            <EditField label="Replace IFSC Code" value={bankIfsc} onChange={v => setBankIfsc(v.toUpperCase())} placeholder="Enter new value…" />
          </div>
        </div>
        <div>
          <ReadField label="PAN (current)" value={profile.panNumber} />
          <div style={{ marginTop: 8 }}>
            <EditField label="Replace PAN" value={panNumber} onChange={v => setPanNumber(v.toUpperCase())} placeholder="ABCDE1234F" />
          </div>
        </div>
        <div>
          <ReadField label="UAN (current)" value={profile.uanNumber} />
          <div style={{ marginTop: 8 }}>
            <EditField label="Replace UAN" value={uanNumber} onChange={setUanNumber} placeholder="12-digit UAN" />
          </div>
        </div>
        <div>
          <ReadField label="ESI Number (current)" value={profile.esiNumber} />
          <div style={{ marginTop: 8 }}>
            <EditField label="Replace ESI Number" value={esiNumber} onChange={setEsiNumber} placeholder="Enter new value…" />
          </div>
        </div>
        <EditField label="Account Holder Name" value={accountHolderName} onChange={setAccountHolderName} placeholder="As per bank records" />
        <EditField label="Name as per PF" value={nameAsPerPf} onChange={setNameAsPerPf} placeholder="As per PF records" />
        <div>
          <ReadField label="PF Number (current)" value={profile.pfNumber} />
          <div style={{ marginTop: 8 }}>
            <EditField label="Replace PF Number" value={pfNumber} onChange={setPfNumber} placeholder="Enter new value…" />
          </div>
        </div>
        <EditField label="PF Registration Date" value={pfRegistrationDate} onChange={setPfRegistrationDate} type="date" />
        <div>
          <ReadField label="Professional Tax Number (current)" value={profile.professionalTaxNumber} />
          <div style={{ marginTop: 8 }}>
            <EditField label="Replace Professional Tax Number" value={professionalTaxNumber} onChange={setProfessionalTaxNumber} placeholder="Enter new value…" />
          </div>
        </div>
        <div style={{ display: 'flex', gap: 24, alignItems: 'center', gridColumn: '1/-1' }}>
          <label style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 13, color: 'var(--txt)' }}>
            <input type="checkbox" checked={pfEnabled} onChange={e => setPfEnabled(e.target.checked)} />
            PF Enabled
          </label>
          <label style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 13, color: 'var(--txt)' }}>
            <input type="checkbox" checked={esiEnabled} onChange={e => setEsiEnabled(e.target.checked)} />
            ESI Enabled
          </label>
        </div>
      </div>
    </EditModal>
  );
}
