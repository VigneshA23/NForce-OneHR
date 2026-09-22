import { useState } from 'react';
import { useToast } from '../../../context/ToastContext';
import { profileApi, type ProfileData } from '../../../api/profile';
import {
  EditModal, EditField, PhoneField, SelectField, GENDERS, MARITAL_STATUSES,
  validateName, validatePhone, nameCharsOnly, digitsOnly,
} from '../shared';

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
        firstName, middleName, lastName, dateOfBirth, gender, maritalStatus,
        emergencyContactName: emName,
        emergencyContactRelationship: emRelation,
        emergencyContactPhone: digitsOnly(emPhone),
      });
      onSaved(updated);
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
        <EditField label="First Name" value={firstName} onChange={v => setFirstName(nameCharsOnly(v))} error={errors.firstName} />
        <EditField label="Middle Name" value={middleName} onChange={v => setMiddleName(nameCharsOnly(v))} />
        <EditField label="Last Name" value={lastName} onChange={v => setLastName(nameCharsOnly(v))} error={errors.lastName} />
        <EditField label="Date of Birth" value={dateOfBirth} onChange={setDateOfBirth} type="date" />
        <SelectField label="Gender" value={gender} onChange={setGender} options={GENDERS} />
        <SelectField label="Marital Status" value={maritalStatus} onChange={setMaritalStatus} options={MARITAL_STATUSES} />
        <EditField label="Emergency Contact Name" value={emName} onChange={v => setEmName(nameCharsOnly(v))} error={errors.emName} />
        <EditField label="Emergency Contact Relationship" value={emRelation} onChange={setEmRelation} placeholder="e.g. Brother" />
        <PhoneField label="Emergency Contact Phone" value={emPhone} onChange={setEmPhone} error={errors.emPhone} />
      </div>
    </EditModal>
  );
}
