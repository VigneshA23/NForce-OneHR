import { useState } from 'react';
import { useToast } from '../../../context/ToastContext';
import { profileLearningApi, type LearningEntry } from '../../../api/profile';
import { EditModal, EditField, TextAreaField } from '../shared';

export function LearningEntryModal({ token, existing, onClose, onSaved }: {
  token: string;
  existing: LearningEntry | null;
  onClose: () => void;
  onSaved: (e: LearningEntry) => void;
}) {
  const { showToast } = useToast();
  const [title, setTitle] = useState(existing?.title ?? '');
  const [description, setDescription] = useState(existing?.description ?? '');
  const [entryDate, setEntryDate] = useState(existing?.entryDate ?? '');
  const [saving, setSaving] = useState(false);

  const hasErrors = !title.trim() || !entryDate;

  async function handleSave() {
    if (hasErrors) {
      showToast('error', 'Title and date are required');
      return;
    }
    setSaving(true);
    try {
      const payload = { title: title.trim(), description: description.trim() || undefined, entryDate };
      const saved = existing
        ? await profileLearningApi.update(token, existing.id, payload)
        : await profileLearningApi.create(token, payload);
      onSaved(saved);
      showToast('success', existing ? 'Entry updated' : 'Entry added');
      onClose();
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Save failed');
    } finally {
      setSaving(false);
    }
  }

  return (
    <EditModal title={existing ? 'Edit Entry' : 'Add What I Learned'} onClose={onClose} onSave={handleSave} saving={saving} saveDisabled={hasErrors} width={480}>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
        <EditField label="Title" value={title} onChange={setTitle} placeholder="e.g. Completed AWS Certification" />
        <TextAreaField label="Description" value={description} onChange={setDescription} rows={3} placeholder="A short note about what you learned or did…" />
        <EditField label="Date" value={entryDate} onChange={setEntryDate} type="date" />
      </div>
    </EditModal>
  );
}
