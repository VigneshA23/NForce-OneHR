import { useState } from 'react';
import { useToast } from '../../../context/ToastContext';
import { profileApi, type ProfileData } from '../../../api/profile';
import { EditModal, EditField, TextAreaField } from '../shared';

export function PreferredNameBioModal({ profile, token, onClose, onSaved }: {
  profile: ProfileData;
  token: string;
  onClose: () => void;
  onSaved: (p: ProfileData) => void;
}) {
  const { showToast } = useToast();
  const [preferredName, setPreferredName] = useState(profile.preferredName ?? '');
  const [bio, setBio] = useState(profile.bio ?? '');
  const [saving, setSaving] = useState(false);

  async function handleSave() {
    setSaving(true);
    try {
      const updated = await profileApi.update(token, { preferredName, bio });
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
        <EditField label="Preferred Name" value={preferredName} onChange={setPreferredName} placeholder="What should we call you?" />
        <TextAreaField label="About Me / Bio" value={bio} onChange={setBio} rows={4} placeholder="A short bio…" />
      </div>
    </EditModal>
  );
}
