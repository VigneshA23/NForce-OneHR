import { useState } from 'react';
import { useToast } from '../../../context/ToastContext';
import { profileSkillsApi, PROFICIENCY_LEVELS, type SkillEntry, type ProficiencyLevel } from '../../../api/profile';
import { EditModal, EditField, SelectField, nameCharsOnly } from '../shared';

export function SkillModal({ token, existing, onClose, onSaved }: {
  token: string;
  existing: SkillEntry | null;
  onClose: () => void;
  onSaved: (s: SkillEntry) => void;
}) {
  const { showToast } = useToast();
  const [skillName, setSkillName] = useState(existing?.skillName ?? '');
  const [level, setLevel] = useState<ProficiencyLevel>(existing?.proficiencyLevel ?? 'Intermediate');
  const [saving, setSaving] = useState(false);

  const hasErrors = !skillName.trim();

  async function handleSave() {
    if (hasErrors) {
      showToast('error', 'Skill name is required');
      return;
    }
    setSaving(true);
    try {
      const payload = { skillName: skillName.trim(), proficiencyLevel: level };
      const saved = existing
        ? await profileSkillsApi.update(token, existing.id, payload)
        : await profileSkillsApi.create(token, payload);
      onSaved(saved);
      showToast('success', existing ? 'Skill updated' : 'Skill added');
      onClose();
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Save failed');
    } finally {
      setSaving(false);
    }
  }

  return (
    <EditModal title={existing ? 'Edit Skill' : 'Add Skill'} onClose={onClose} onSave={handleSave} saving={saving} saveDisabled={hasErrors} width={420}>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
        <EditField label="Skill Name" value={skillName} onChange={setSkillName} filter={nameCharsOnly} placeholder="e.g. Java, Selenium" />
        <SelectField label="Proficiency Level" value={level} onChange={v => setLevel(v as ProficiencyLevel)} options={PROFICIENCY_LEVELS} />
      </div>
    </EditModal>
  );
}
