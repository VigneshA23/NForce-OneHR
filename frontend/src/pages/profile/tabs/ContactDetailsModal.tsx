import { useState } from 'react';
import { useToast } from '../../../context/ToastContext';
import { profileApi, type ProfileData } from '../../../api/profile';
import { EditModal, EditField, PhoneField, ReadField, validateEmail, validatePhone, digitsOnly } from '../shared';

export function ContactDetailsModal({ profile, token, onClose, onSaved }: {
  profile: ProfileData;
  token: string;
  onClose: () => void;
  onSaved: (p: ProfileData) => void;
}) {
  const { showToast } = useToast();
  const [personalEmail, setPersonalEmail] = useState(profile.personalEmail ?? '');
  const [phone, setPhone] = useState(profile.phone ?? '');
  const [saving, setSaving] = useState(false);

  const errors = {
    personalEmail: validateEmail(personalEmail),
    phone: validatePhone(phone, 'Mobile number'),
  };
  const hasErrors = Object.values(errors).some(Boolean);

  async function handleSave() {
    if (hasErrors) {
      showToast('error', Object.values(errors).find(Boolean)!);
      return;
    }
    setSaving(true);
    try {
      const updated = await profileApi.update(token, { personalEmail, phone: digitsOnly(phone) });
      onSaved(updated);
      showToast('success', 'Contact details updated');
      onClose();
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Save failed');
    } finally {
      setSaving(false);
    }
  }

  return (
    <EditModal title="Edit Contact Details" onClose={onClose} onSave={handleSave} saving={saving} saveDisabled={hasErrors}>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
        <ReadField label="Work Email (managed)" value={profile.email} />
        <EditField label="Personal Email" value={personalEmail} onChange={setPersonalEmail} type="email" error={errors.personalEmail} />
        <PhoneField label="Mobile Number" value={phone} onChange={setPhone} error={errors.phone} />
      </div>
    </EditModal>
  );
}
