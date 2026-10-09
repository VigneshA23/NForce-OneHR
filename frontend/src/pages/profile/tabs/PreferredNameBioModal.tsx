import { useState } from 'react';
import { useToast } from '../../../context/ToastContext';
import { profileApi, type ProfileData } from '../../../api/profile';
import { EditModal, TextAreaField } from '../shared';

// Display Name isn't edited here — it's derived from First/Middle/Last Name (see
// computeDisplayName in ../shared, edited via Primary Details). This modal only edits Bio.
export function PreferredNameBioModal({ profile, token, onClose, onSaved }: {
  profile: ProfileData;
  token: string;
  onClose: () => void;
  onSaved: (p: ProfileData) => void;
}) {
  const { showToast } = useToast();
  const [bio, setBio] = useState(profile.bio ?? '');
  const [saving, setSaving] = useState(false);

  async function handleSave() {
    setSaving(true);
    try {
      const updated = await profileApi.update(token, { bio });
      onSaved(updated);
      showToast('success', 'Summary updated');
      onClose();
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Save failed');
    } finally {
      setSaving(false);
    }
  }

  return (
    <EditModal title="Edit Personal Summary & Bio" onClose={onClose} onSave={handleSave} saving={saving}>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
        <TextAreaField label="About Me / Bio" value={bio} onChange={setBio} rows={4} placeholder="A short bio…" />
      </div>
    </EditModal>
  );
}
