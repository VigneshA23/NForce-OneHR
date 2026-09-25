import { useState } from 'react';
import { useToast } from '../../../context/ToastContext';
import { profileEducationApi, type EducationEntry } from '../../../api/profile';
import { EditModal, EditField } from '../shared';

export function EducationModal({ token, existing, onClose, onSaved }: {
  token: string;
  existing: EducationEntry | null;
  onClose: () => void;
  onSaved: (e: EducationEntry) => void;
}) {
  const { showToast } = useToast();
  const [institutionName, setInstitutionName] = useState(existing?.institutionName ?? '');
  const [degree, setDegree] = useState(existing?.degree ?? '');
  const [fieldOfStudy, setFieldOfStudy] = useState(existing?.fieldOfStudy ?? '');
  const [startDate, setStartDate] = useState(existing?.startDate ?? '');
  const [endDate, setEndDate] = useState(existing?.endDate ?? '');
  const [saving, setSaving] = useState(false);

  const dateError = startDate && endDate && endDate < startDate ? 'End date cannot be before start date' : null;
  const hasErrors = !institutionName.trim() || !degree.trim() || !!dateError;

  async function handleSave() {
    if (hasErrors) {
      showToast('error', dateError ?? 'Institution and degree are required');
      return;
    }
    setSaving(true);
    try {
      const payload = {
        institutionName: institutionName.trim(),
        degree: degree.trim(),
        fieldOfStudy: fieldOfStudy.trim() || undefined,
        startDate: startDate || undefined,
        endDate: endDate || undefined,
      };
      const saved = existing
        ? await profileEducationApi.update(token, existing.id, payload)
        : await profileEducationApi.create(token, payload);
      onSaved(saved);
      showToast('success', existing ? 'Education entry updated' : 'Education entry added');
      onClose();
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Save failed');
    } finally {
      setSaving(false);
    }
  }

  return (
    <EditModal title={existing ? 'Edit Education' : 'Add Education'} onClose={onClose} onSave={handleSave} saving={saving} saveDisabled={hasErrors}>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
        <EditField label="Institution Name" value={institutionName} onChange={setInstitutionName} />
        <EditField label="Degree / Qualification" value={degree} onChange={setDegree} />
        <EditField label="Field of Study" value={fieldOfStudy} onChange={setFieldOfStudy} />
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 14 }}>
          <EditField label="Start Date" value={startDate} onChange={setStartDate} type="date" />
          <EditField label="End Date" value={endDate} onChange={setEndDate} type="date" error={dateError} />
        </div>
      </div>
    </EditModal>
  );
}
