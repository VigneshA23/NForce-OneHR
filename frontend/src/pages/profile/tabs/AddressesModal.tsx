import { useState } from 'react';
import { useToast } from '../../../context/ToastContext';
import { profileApi, type ProfileData } from '../../../api/profile';
import { EditModal, TextAreaField } from '../shared';

export function AddressesModal({ profile, token, onClose, onSaved }: {
  profile: ProfileData;
  token: string;
  onClose: () => void;
  onSaved: (p: ProfileData) => void;
}) {
  const { showToast } = useToast();
  const [address, setAddress] = useState(profile.address ?? '');
  const [permanentAddress, setPermanentAddress] = useState(profile.permanentAddress ?? '');
  const [saving, setSaving] = useState(false);

  async function handleSave() {
    setSaving(true);
    try {
      const updated = await profileApi.update(token, { address, permanentAddress });
      onSaved(updated);
      showToast('success', 'Addresses updated');
      onClose();
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Save failed');
    } finally {
      setSaving(false);
    }
  }

  return (
    <EditModal title="Edit Addresses" onClose={onClose} onSave={handleSave} saving={saving}>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
        <TextAreaField label="Current Address" value={address} onChange={setAddress} rows={3} />
        <TextAreaField label="Permanent Address" value={permanentAddress} onChange={setPermanentAddress} rows={3} />
      </div>
    </EditModal>
  );
}
