import { useEffect, useState } from 'react';
import { useToast } from '../../../context/ToastContext';
import { profileSkillsApi, type SkillEntry } from '../../../api/profile';
import { skillMasterApi, proficiencyLevelApi, skillRequestApi, type SkillMasterOption, type ProficiencyLevelOption } from '../../../api/talentMasterData';
import { EditModal, EditField, SelectField, SearchableSelectField, FieldLabel, INPUT_STYLE, nameCharsOnly } from '../shared';

export function SkillModal({ token, existing, onClose, onSaved, onRequestSubmitted }: {
  token: string;
  existing: SkillEntry | null;
  onClose: () => void;
  onSaved: (s: SkillEntry) => void;
  /** Called after a "Request new skill" submission succeeds — lets the parent refresh "My Skill
   *  Requests" immediately, since that list isn't covered by onSaved (no SkillEntry is created). */
  onRequestSubmitted?: () => void;
}) {
  const { showToast } = useToast();
  const [skills, setSkills] = useState<SkillMasterOption[]>([]);
  const [levels, setLevels] = useState<ProficiencyLevelOption[]>([]);
  const [loading, setLoading] = useState(true);
  const [skillId, setSkillId] = useState(existing?.skillId ?? '');
  const [levelName, setLevelName] = useState(existing?.proficiencyLevel ?? '');
  const [saving, setSaving] = useState(false);

  // "Can't find my skill" sub-flow — a separate request, not a save of this modal.
  const [requesting, setRequesting] = useState(false);
  const [requestName, setRequestName] = useState('');
  const [requestCategory, setRequestCategory] = useState('');
  const [requestNote, setRequestNote] = useState('');
  const [submittingRequest, setSubmittingRequest] = useState(false);

  useEffect(() => {
    Promise.all([skillMasterApi.listActive(token), proficiencyLevelApi.listActive(token)])
      .then(([s, l]) => {
        setSkills(s);
        setLevels(l);
        if (!levelName && l.length > 0) setLevelName(l[0].name);
      })
      .catch(() => showToast('error', 'Could not load skill/proficiency options'))
      .finally(() => setLoading(false));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token]);

  const selectedLevel = levels.find(l => l.name === levelName);
  const hasErrors = !skillId || !selectedLevel;

  async function handleSave() {
    if (hasErrors) {
      showToast('error', 'Pick a skill and a proficiency level');
      return;
    }
    setSaving(true);
    try {
      const payload = { skillId, proficiencyLevelId: selectedLevel!.id };
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

  async function handleSubmitRequest() {
    if (!requestName.trim()) {
      showToast('error', 'Skill name is required');
      return;
    }
    setSubmittingRequest(true);
    try {
      await skillRequestApi.submit(token, {
        name: requestName.trim(),
        category: requestCategory.trim() || undefined,
        note: requestNote.trim() || undefined,
      });
      showToast('success', 'Request submitted — HR will review it. Check its status from your Skills tab.');
      onRequestSubmitted?.();
      onClose();
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Could not submit request');
    } finally {
      setSubmittingRequest(false);
    }
  }

  if (requesting) {
    return (
      <EditModal title="Request a New Skill" onClose={onClose} onSave={handleSubmitRequest} saving={submittingRequest}
        saveDisabled={!requestName.trim()} width={420}>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
          <p style={{ fontSize: 12.5, color: 'var(--txt-mut)', margin: 0 }}>
            Not in the list yet? Ask HR to add it — you'll be able to add it to your own Skills tab once it's approved.
          </p>
          <EditField label="Skill Name" value={requestName} onChange={setRequestName} filter={nameCharsOnly} placeholder="e.g. Rust" />
          <EditField label="Category (optional)" value={requestCategory} onChange={setRequestCategory} placeholder="e.g. Programming Language" />
          <div>
            <FieldLabel>Note (optional)</FieldLabel>
            <textarea value={requestNote} onChange={e => setRequestNote(e.target.value)} rows={2}
              placeholder="Why you need it, or where you'd use it" style={{ ...INPUT_STYLE, resize: 'vertical', fontFamily: 'inherit' }} />
          </div>
          <button type="button" onClick={() => setRequesting(false)}
            style={{ background: 'none', border: 'none', color: 'var(--brand)', fontSize: 12.5, fontWeight: 600, cursor: 'pointer', padding: 0, textAlign: 'left' }}>
            ← Back to skill picker
          </button>
        </div>
      </EditModal>
    );
  }

  return (
    <EditModal title={existing ? 'Edit Skill' : 'Add Skill'} onClose={onClose} onSave={handleSave} saving={saving} saveDisabled={hasErrors || loading}>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
        <SearchableSelectField
          label="Skill"
          value={skillId}
          onChange={setSkillId}
          options={skills.map(s => ({ id: s.id, label: s.name, sublabel: s.category ?? undefined }))}
          placeholder={loading ? 'Loading…' : 'Search skills…'}
          disabled={loading}
          noMatchFooter={() => (
            <button type="button" onClick={() => { setRequestName(''); setRequesting(true); }} style={{
              display: 'block', width: '100%', textAlign: 'left', background: 'none', border: 'none',
              padding: '9px 12px', cursor: 'pointer', fontSize: 12.5, fontWeight: 600, color: 'var(--brand)',
            }}>
              + Can't find your skill? Request it
            </button>
          )}
        />
        <SelectField label="Proficiency Level" value={levelName} onChange={setLevelName} options={levels.map(l => l.name)} />
      </div>
    </EditModal>
  );
}
