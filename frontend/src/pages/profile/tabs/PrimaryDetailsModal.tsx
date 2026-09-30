import { useState } from 'react';
import { useToast } from '../../../context/ToastContext';
import { profileApi, type ProfileData } from '../../../api/profile';
import { invalidateHeroVariantCache } from '../../../components/HeroIllustration';
import {
  EditModal, EditField, PhoneField, SelectField, GENDERS, MARITAL_STATUSES,
  validateName, validatePhone, nameCharsOnly, digitsOnly,
} from '../shared';

const NAME_FILTER_HINT = 'Only letters, spaces, hyphens, apostrophes, and periods are allowed.';

export function PrimaryDetailsModal({ profile, token, onClose, onSaved }: {
  profile: ProfileData;
  token: string;
  onClose: () => void;
  onSaved: (p: ProfileData) => void;
}) {
  const { showToast } = useToast();
  const [firstName, setFirstName] = useState(profile.firstName ?? '');
  const [middleName, setMiddleName] = useState(profile.middleName ?? '');
  const [lastName, setLastName] = useState(profile.lastName ?? '');
  const [dateOfBirth, setDateOfBirth] = useState(profile.dateOfBirth ?? '');
  const [gender, setGender] = useState(profile.gender ?? '');
  const [maritalStatus, setMaritalStatus] = useState(profile.maritalStatus ?? '');
  const [emName, setEmName] = useState(profile.emergencyContactName ?? '');
  const [emRelation, setEmRelation] = useState(profile.emergencyContactRelationship ?? '');
  const [emPhone, setEmPhone] = useState(profile.emergencyContactPhone ?? '');
  const [saving, setSaving] = useState(false);

  const errors = {
    firstName: validateName(firstName, 'First name'),
    // Middle Name is validated by the exact same backend @Pattern as First/Last Name, but had no
    // matching frontend check — an invalid value (e.g. a trailing hyphen) would silently pass
    // client-side, then fail server-side with a raw error instead of this same inline feedback.
    middleName: validateName(middleName, 'Middle name'),
    lastName: validateName(lastName, 'Last name'),
    emName: validateName(emName, 'Contact name'),
    emPhone: validatePhone(emPhone, 'Contact phone'),
  };
  const hasErrors = Object.values(errors).some(Boolean);

  async function handleSave() {
    if (hasErrors) {
      showToast('error', Object.values(errors).find(Boolean)!);
      return;
    }
    setSaving(true);
    try {
      const updated = await profileApi.update(token, {
        firstName, middleName, lastName, gender, maritalStatus,
        // A cleared field sends no dateOfBirth at all, plus the explicit clear flag — see
        // UpdateProfilePayload's doc comment for why "" / omitted alone isn't enough.
        ...(dateOfBirth ? { dateOfBirth } : { clearDateOfBirth: true }),
        emergencyContactName: emName,
        emergencyContactRelationship: emRelation,
        emergencyContactPhone: digitsOnly(emPhone),
      });
      onSaved(updated);
      // Dashboard Hero caches its gender→image variant per session token (see
      // HeroIllustration.tsx) — a Gender change here wouldn't otherwise show up on the
      // dashboard until a full page reload. Drop that cache so navigating back picks up
      // the new value immediately.
      invalidateHeroVariantCache();
      showToast('success', 'Primary details updated');
      onClose();
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Save failed');
    } finally {
      setSaving(false);
    }
  }

  return (
    <EditModal title="Edit Primary Details" onClose={onClose} onSave={handleSave} saving={saving} saveDisabled={hasErrors} width={640}>
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 16 }} className="nf-grid-2col-collapse">
        <EditField label="First Name" value={firstName} onChange={setFirstName} filter={nameCharsOnly} filterHint={NAME_FILTER_HINT} error={errors.firstName} />
        <EditField label="Middle Name" value={middleName} onChange={setMiddleName} filter={nameCharsOnly} filterHint={NAME_FILTER_HINT} error={errors.middleName} />
        <EditField label="Last Name" value={lastName} onChange={setLastName} filter={nameCharsOnly} filterHint={NAME_FILTER_HINT} error={errors.lastName} />
        <EditField label="Date of Birth" value={dateOfBirth} onChange={setDateOfBirth} type="date" />
        <SelectField label="Gender" value={gender} onChange={setGender} options={GENDERS} />
        <SelectField label="Marital Status" value={maritalStatus} onChange={setMaritalStatus} options={MARITAL_STATUSES} />
        <EditField label="Emergency Contact Name" value={emName} onChange={setEmName} filter={nameCharsOnly} filterHint={NAME_FILTER_HINT} error={errors.emName} />
        <EditField label="Emergency Contact Relationship" value={emRelation} onChange={setEmRelation} placeholder="e.g. Brother" />
        <PhoneField label="Emergency Contact Phone" value={emPhone} onChange={setEmPhone} error={errors.emPhone} />
      </div>
    </EditModal>
  );
}
