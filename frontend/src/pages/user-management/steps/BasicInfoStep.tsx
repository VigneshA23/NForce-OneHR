import { Field, inputStyle } from '../formPrimitives';
import { GENDERS, MARITAL_STATUSES, digitsOnly } from '../../profile/shared';
import { composeFullName } from '../wizardLogic';
import type { CreateUserPayload } from '../../../api/employees';

interface BasicInfoStepProps {
  form: CreateUserPayload;
  setForm: React.Dispatch<React.SetStateAction<CreateUserPayload>>;
  nameTouched: boolean;
  setNameTouched: (v: boolean) => void;
}

export function BasicInfoStep({ form, setForm, nameTouched, setNameTouched }: BasicInfoStepProps) {
  // While the admin hasn't directly edited Full Name (nameTouched false), it auto-composes from
  // the name parts on every keystroke — see composeFullName's own doc comment. The moment they
  // type into Full Name directly (onChange below), nameTouched flips true and this stops for good.
  function setNamePart(key: 'firstName' | 'middleName' | 'lastName', value: string) {
    setForm(f => {
      const firstName = key === 'firstName' ? value : (f.firstName ?? '');
      const middleName = key === 'middleName' ? value : (f.middleName ?? '');
      const lastName = key === 'lastName' ? value : (f.lastName ?? '');
      return { ...f, [key]: value || undefined, fullName: composeFullName(firstName, middleName, lastName, f.fullName, nameTouched) };
    });
  }

  return (
    <div className="nf-grid-2col-collapse" style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 14 }}>
      <Field label="First Name"><input style={inputStyle} value={form.firstName ?? ''} onChange={e => setNamePart('firstName', e.target.value)} placeholder="Jane" /></Field>
      <Field label="Middle Name"><input style={inputStyle} value={form.middleName ?? ''} onChange={e => setNamePart('middleName', e.target.value)} placeholder="Optional" /></Field>
      <Field label="Last Name"><input style={inputStyle} value={form.lastName ?? ''} onChange={e => setNamePart('lastName', e.target.value)} placeholder="Smith" /></Field>
      <Field label="Full Name *">
        <input style={inputStyle} value={form.fullName} onChange={e => { setNameTouched(true); setForm(f => ({ ...f, fullName: e.target.value })); }} placeholder="Jane Smith" />
      </Field>
      <Field label="Company Email *" span="full">
        <input type="email" style={inputStyle} value={form.email} onChange={e => setForm(f => ({ ...f, email: e.target.value }))} placeholder="jane@nforceone.com" />
      </Field>
      <Field label="Date of Birth"><input type="date" style={inputStyle} value={form.dateOfBirth ?? ''} onChange={e => setForm(f => ({ ...f, dateOfBirth: e.target.value || undefined }))} /></Field>
      <Field label="Gender">
        <select style={inputStyle} value={form.gender ?? ''} onChange={e => setForm(f => ({ ...f, gender: e.target.value || undefined }))}>
          <option value="">Select Gender</option>
          {GENDERS.map(g => <option key={g} value={g}>{g}</option>)}
        </select>
      </Field>
      <Field label="Marital Status">
        <select style={inputStyle} value={form.maritalStatus ?? ''} onChange={e => setForm(f => ({ ...f, maritalStatus: e.target.value || undefined }))}>
          <option value="">Select Marital Status</option>
          {MARITAL_STATUSES.map(m => <option key={m} value={m}>{m}</option>)}
        </select>
      </Field>
      <Field label="Phone Number"><input style={inputStyle} value={form.phone ?? ''} onChange={e => setForm(f => ({ ...f, phone: digitsOnly(e.target.value) || undefined }))} placeholder="9876543210" /></Field>
      <Field label="Personal Email"><input type="email" style={inputStyle} value={form.personalEmail ?? ''} onChange={e => setForm(f => ({ ...f, personalEmail: e.target.value || undefined }))} placeholder="jane.personal@example.com" /></Field>
      <Field label="Emergency Contact Name"><input style={inputStyle} value={form.emergencyContactName ?? ''} onChange={e => setForm(f => ({ ...f, emergencyContactName: e.target.value || undefined }))} /></Field>
      <Field label="Emergency Contact Phone"><input style={inputStyle} value={form.emergencyContactPhone ?? ''} onChange={e => setForm(f => ({ ...f, emergencyContactPhone: digitsOnly(e.target.value) || undefined }))} /></Field>
      <Field label="Emergency Contact Relationship" span="full"><input style={inputStyle} value={form.emergencyContactRelationship ?? ''} onChange={e => setForm(f => ({ ...f, emergencyContactRelationship: e.target.value || undefined }))} placeholder="e.g. Spouse, Parent" /></Field>
    </div>
  );
}
