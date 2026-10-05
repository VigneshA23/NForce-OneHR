import { useState } from 'react';
import { useToast } from '../../../context/ToastContext';
import {
  profileLearningApi, LEARNING_TYPES, LEARNING_STATUSES,
  type LearningEntry, type LearningType, type LearningStatus,
} from '../../../api/profile';
import { EditModal, EditField, SelectField, TextAreaField, FieldLabel } from '../shared';

export function LearningEntryModal({ token, existing, statusOptions = LEARNING_STATUSES, onClose, onSaved }: {
  token: string;
  existing: LearningEntry | null;
  // Which statuses are selectable for a brand-new entry, matching whichever tab Add Learning
  // was clicked from (see GrowthTab) — e.g. Recent Learning only offers Completed, since that
  // tab is specifically the "already learned" list. Defaults to all three (and GrowthTab always
  // passes all three) when editing an existing entry, since you still need to be able to move
  // it along its own lifecycle — e.g. marking a Planned/In Progress entry Completed.
  statusOptions?: LearningStatus[];
  onClose: () => void;
  onSaved: (e: LearningEntry) => void;
}) {
  const { showToast } = useToast();
  const [title, setTitle] = useState(existing?.title ?? '');
  const [learningType, setLearningType] = useState<LearningType | ''>(existing?.learningType ?? '');
  const [status, setStatus] = useState<LearningStatus | ''>(existing?.status ?? statusOptions[0] ?? '');
  const [provider, setProvider] = useState(existing?.provider ?? '');
  const [startDate, setStartDate] = useState(existing?.startDate ?? '');
  const [completedDate, setCompletedDate] = useState(existing?.completedDate ?? '');
  const [description, setDescription] = useState(existing?.description ?? '');
  const [certificateName, setCertificateName] = useState(existing?.certificateName ?? '');
  const [certificateIssueDate, setCertificateIssueDate] = useState(existing?.certificateIssueDate ?? '');
  const [certificateExpiryDate, setCertificateExpiryDate] = useState(existing?.certificateExpiryDate ?? '');
  const [certificateUrl, setCertificateUrl] = useState(existing?.certificateUrl ?? '');
  const [notes, setNotes] = useState(existing?.notes ?? '');
  const [saving, setSaving] = useState(false);

  const needsCompletedDate = status === 'Completed';
  const hasErrors = !title.trim() || !learningType || !status || (needsCompletedDate && !completedDate);

  async function handleSave() {
    if (hasErrors) {
      showToast('error', needsCompletedDate && !completedDate
        ? 'Completion date is required when status is Completed'
        : 'Title, learning type, and status are required');
      return;
    }
    setSaving(true);
    try {
      const payload = {
        title: title.trim(),
        description: description.trim() || undefined,
        learningType: learningType as LearningType,
        status: status as LearningStatus,
        provider: provider.trim() || undefined,
        startDate: startDate || undefined,
        completedDate: completedDate || undefined,
        certificateName: certificateName.trim() || undefined,
        certificateIssueDate: certificateIssueDate || undefined,
        certificateExpiryDate: certificateExpiryDate || undefined,
        certificateUrl: certificateUrl.trim() || undefined,
        notes: notes.trim() || undefined,
      };
      const saved = existing
        ? await profileLearningApi.update(token, existing.id, payload)
        : await profileLearningApi.create(token, payload);
      onSaved(saved);
      showToast('success', existing ? 'Learning entry updated' : 'Learning entry added');
      onClose();
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Save failed');
    } finally {
      setSaving(false);
    }
  }

  return (
    <EditModal title={existing ? 'Edit Learning' : 'Add Learning'} onClose={onClose} onSave={handleSave} saving={saving} saveDisabled={hasErrors} width={560}>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
        <EditField label="Learning Title" value={title} onChange={setTitle} placeholder="e.g. Playwright Automation" />

        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 14 }}>
          <SelectField label="Learning Type" value={learningType} onChange={v => setLearningType(v as LearningType)} options={LEARNING_TYPES} />
          <EditField label="Provider / Platform" value={provider} onChange={setProvider} placeholder="Udemy, Coursera, Internal…" />
        </div>

        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 14 }}>
          <SelectField label="Status" value={status} onChange={v => setStatus(v as LearningStatus)} options={statusOptions} />
          <EditField label="Started On" value={startDate} onChange={setStartDate} type="date" />
          <EditField label="Completed On" value={completedDate} onChange={setCompletedDate} type="date"
            error={needsCompletedDate && !completedDate ? 'Required when status is Completed' : null} />
        </div>

        <TextAreaField label="Description" value={description} onChange={setDescription} rows={3}
          placeholder="What did this cover — locators, waits, Page Object Model, etc.?" />

        <div style={{ borderTop: '1px solid var(--line)', paddingTop: 14, display: 'flex', flexDirection: 'column', gap: 14 }}>
          <FieldLabel>Certificate / Evidence (optional)</FieldLabel>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 14 }}>
            <EditField label="Certificate Name" value={certificateName} onChange={setCertificateName} placeholder="e.g. AWS Certified Cloud Practitioner" />
            <EditField label="Certificate URL" value={certificateUrl} onChange={setCertificateUrl} placeholder="Link to certificate or badge" />
          </div>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 14 }}>
            <EditField label="Issue Date" value={certificateIssueDate} onChange={setCertificateIssueDate} type="date" />
            <EditField label="Expiry Date" value={certificateExpiryDate} onChange={setCertificateExpiryDate} type="date" />
          </div>
        </div>

        <TextAreaField label="Notes" value={notes} onChange={setNotes} rows={2} placeholder="Optional" />
      </div>
    </EditModal>
  );
}
