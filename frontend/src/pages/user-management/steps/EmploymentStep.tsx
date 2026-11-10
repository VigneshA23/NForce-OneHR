import { Field, inputStyle } from '../formPrimitives';
import { CreatableLocationSelect } from '../SharedControls';
import { ROLES, EMPLOYMENT_TYPES, WORK_MODES, getDepartmentOptions, getDesignationOptions, getLocationOptions, getManagersForRole, type OrgOptions } from '../orgOptionsHelpers';
import type { CreateUserPayload } from '../../../api/employees';

interface EmploymentStepProps {
  form: CreateUserPayload;
  setForm: React.Dispatch<React.SetStateAction<CreateUserPayload>>;
  opts: OrgOptions;
  token: string;
  employeeCodeConflict: boolean;
  fetchingNewId: boolean;
  onGetNewId: () => void;
  previewLoading: boolean;
}

// Role lives in this step (not Step 3) rather than next to Shift/Weekly-Off/Attendance Policy:
// the Reporting Manager dropdown's eligible-role filter (getManagersForRole) and its very
// requiredness both depend on Role being chosen first — the exact same order today's single
// AddModal form already enforces (Role, then Reporting Manager, in its "Role & Employment" /
// "Location & Reporting" sections). Splitting them across steps would mean Reporting Manager
// can't be filtered/validated until a later step picks Role — so both stay together here.
export function EmploymentStep({ form, setForm, opts, token, employeeCodeConflict, fetchingNewId, onGetNewId, previewLoading }: EmploymentStepProps) {
  const set = (key: keyof CreateUserPayload, val: any) => setForm(f => ({ ...f, [key]: val || undefined }));

  return (
    <div className="nf-grid-2col-collapse" style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 14 }}>
      <Field label="Employee ID">
        <input
          style={inputStyle}
          value={form.employeeCode ?? ''}
          onChange={e => setForm(f => ({ ...f, employeeCode: e.target.value }))}
          placeholder={previewLoading ? 'Loading…' : 'e.g. NF-20260007'}
        />
        {employeeCodeConflict && (
          <div style={{ marginTop: 6, color: 'var(--risk)', background: 'rgba(228,55,61,.08)', border: '1px solid rgba(228,55,61,.2)', borderRadius: 6, padding: '10px 14px', fontSize: 13 }}>
            Employee ID already exists.{' '}
            <button
              type="button"
              onClick={onGetNewId}
              disabled={fetchingNewId}
              style={{ background: 'none', border: 'none', padding: 0, margin: 0, font: 'inherit', color: 'inherit', textDecoration: 'underline', cursor: fetchingNewId ? 'wait' : 'pointer' }}
            >
              {fetchingNewId ? 'Fetching…' : 'Click here'}
            </button>
            {' '}to get a new ID.
          </div>
        )}
      </Field>
      <Field label="Joining Date *"><input type="date" style={inputStyle} value={form.joiningDate} onChange={e => setForm(f => ({ ...f, joiningDate: e.target.value }))} /></Field>
      <Field label="Role *">
        <select style={inputStyle} value={form.role} onChange={e => {
          const newRole = e.target.value;
          const newMgrs = getManagersForRole(newRole, opts.managers);
          setForm(f => ({
            ...f, role: newRole,
            managerId: f.managerId && newMgrs.some(m => m.userId === f.managerId) ? f.managerId : undefined,
          }));
        }}>
          <option value="" disabled>Select Role</option>
          {ROLES.map(r => <option key={r.value} value={r.value}>{r.label}</option>)}
        </select>
      </Field>
      <Field label="Employment Type">
        <select style={inputStyle} value={form.employmentType ?? ''} onChange={e => set('employmentType', e.target.value)}>
          <option value="">Select Employment Type</option>
          {EMPLOYMENT_TYPES.map(t => <option key={t} value={t}>{t.replace('_', ' ')}</option>)}
        </select>
      </Field>
      <Field label="Work Mode">
        <select style={inputStyle} value={form.workMode ?? ''} onChange={e => set('workMode', e.target.value)}>
          <option value="">Select Work Mode</option>
          {WORK_MODES.map(m => <option key={m} value={m}>{m.charAt(0) + m.slice(1).toLowerCase()}</option>)}
        </select>
      </Field>
      <Field label="Business Unit">
        <select style={inputStyle} value={form.businessUnitId ?? ''} onChange={e => set('businessUnitId', e.target.value)}>
          <option value="">Select Business Unit</option>{opts.businessUnits.map((b: any) => <option key={b.id} value={b.id}>{b.name}</option>)}
        </select>
      </Field>
      <Field label="Department *">
        <select style={inputStyle} value={form.departmentId ?? ''} onChange={e => setForm(f => ({ ...f, departmentId: e.target.value || undefined, designationId: undefined }))}>
          <option value="">Select Department</option>{getDepartmentOptions(opts.departments, form.departmentId).map((d: any) => <option key={d.id} value={d.id}>{d.name}</option>)}
        </select>
      </Field>
      <Field label="Designation *">
        <select style={inputStyle} value={form.designationId ?? ''} disabled={!form.departmentId} onChange={e => set('designationId', e.target.value)}>
          <option value="">{form.departmentId ? 'Select Designation' : 'Select a Department first'}</option>
          {getDesignationOptions(opts.designations, form.designationId, form.departmentId).map((d: any) => <option key={d.id} value={d.id}>{d.title}</option>)}
        </select>
      </Field>
      <Field label="Location *">
        <CreatableLocationSelect locations={getLocationOptions(opts.locations, form.locationId)} value={form.locationId} onChange={id => setForm(f => ({ ...f, locationId: id }))} token={token} />
      </Field>
      {(() => {
        const noRoleYet = !form.role;
        const isSA = form.role === 'SUPER_ADMIN';
        const mgrList = noRoleYet ? [] : getManagersForRole(form.role, opts.managers);
        const mgrRoleLabel = form.role === 'EMPLOYEE' ? 'Manager or HR Admin'
          : form.role === 'MANAGER' ? 'HR Admin or Super Admin'
          : 'Super Admin';
        return (
          <Field label={isSA ? 'Reporting Manager' : 'Reporting Manager *'}>
            <select style={inputStyle} value={form.managerId ?? ''} disabled={noRoleYet} onChange={e => set('managerId', e.target.value)}>
              <option value="">
                {noRoleYet ? '– Select a Role first –' : isSA ? '– None (optional) –' : '– Select a Reporting Manager –'}
              </option>
              {mgrList.map((m: any) => (
                <option key={m.userId} value={m.userId} disabled={m.active === false}>
                  {m.fullName} ({m.email}){m.active === false ? ' - Inactive' : ''}
                </option>
              ))}
            </select>
            {!noRoleYet && !isSA && mgrList.length === 0 && (
              <div style={{ fontSize: 11, color: '#E0A93B', marginTop: 4 }}>
                No {mgrRoleLabel} users found - add one first.
              </div>
            )}
          </Field>
        );
      })()}
    </div>
  );
}
