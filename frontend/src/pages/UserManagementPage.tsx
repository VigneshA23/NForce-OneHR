import { useEffect, useState } from 'react';
import { PersonCell, PersonAvatar } from '../components/PersonCell';
import PageTitleBlock from '../components/PageTitleBlock';
import DashTile from '../components/DashTile';
import { createPortal } from 'react-dom';
import { Plus, User as UserIcon, Users2, ShieldCheck, UserX, Search as SearchIcon, UserCog, X as XIcon, AlertCircle, Building2, Briefcase, MapPin, Lock, Clock3, UserCheck } from 'lucide-react';
import { KebabMenu } from '../components/KebabMenu';
import { useAuthStore } from '../store/authStore';
import { usersApi, employeesApi, type EmployeeRecord, type UpdateUserPayload, type UpdateJoiningDatePayload, type ResetPasswordResult } from '../api/employees';
import { orgApi } from '../api/org';
import { employeeAssignmentsApi } from '../api/employeeAssignments';
import { useToast } from '../context/ToastContext';
import { InactiveEditBanner, InactiveFieldsConfirm } from '../components/EmployeeStatus';
import { businessTodayIsoDate } from '../utils/businessDate';
import { CreatableLocationSelect, ShiftSelect } from './user-management/SharedControls';
import { ROLES, EMPLOYMENT_TYPES, WORK_MODES, fmtEffectiveDate, getShiftOptions, getDepartmentOptions, getDesignationOptions, getLocationOptions, getManagersForRole, type OrgOptions } from './user-management/orgOptionsHelpers';
import { overlayStyle, modalStyle, inputStyle, labelStyle, ModalHeader, ManagerApprovalWarning } from './user-management/formPrimitives';
import { EMAIL_PATTERN } from './user-management/wizardLogic';
import { CreateEmployeeWizard } from './user-management/CreateEmployeeWizard';
import { hasPermission } from '../lib/permissions';
import { useCan } from '../lib/access';
import { categoriesOf, roleCountsOf, roleOptionsOf } from '../lib/roleCategories';
import ManageUserRolesModal from '../components/ManageUserRolesModal';
import './userManagementPolish.css';
import './employeeEdit.css';

/** Today as "YYYY-MM-DD" in the org's business timezone (see businessTodayIsoDate's own doc
 * comment) — matches what a plain <input type="date"> compares against, and matches the
 * backend's own "cannot be in the past" validation of this exact field. Business rule: today is
 * a valid, final Effective From choice, never auto-advanced. */
function todayIso(): string {
  return businessTodayIsoDate();
}

const ROLE_COLOR: Record<string, string> = {
  EMPLOYEE: '#2FB67C', MANAGER: '#4C8DD6', HR_ADMIN: '#E0A93B', SUPER_ADMIN: '#E4373D',
};

function RoleBadge({ role, name }: { role: string; name?: string }) {
  const c = ROLE_COLOR[role] ?? '#8B5CF6'; // custom roles use the "custom" accent
  return (
    <span style={{ fontSize: 10.5, fontWeight: 700, letterSpacing: '.03em', color: c, background: `${c}1A`, borderRadius: 6, padding: '3px 9px', whiteSpace: 'nowrap' }}>
      {name ?? role.replace(/_/g, ' ')}
    </span>
  );
}

// Page-local, purely presentational restyle of the Active/Inactive pill (dot + rounded pill) —
// same `active` input and same Active/Inactive wording as the shared EmployeeStatus.StatusBadge.
/** The three summary tiles on the Access page: same tile as the Home dashboard (components/DashTile). Display only. */
export function AccessStatTiles({ total, active, inactive, roleSummary }: { total: number; active: number; inactive: number; roleSummary: string }) {
  const pct = (n: number) => (total > 0 ? Math.round((n / total) * 100) : 0);
  return (
    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: 14 }}>
      <DashTile icon={<Users2 size={20} />} accent="var(--info)" label="Total Users" value={String(total)} sub={roleSummary} />
      <DashTile icon={<ShieldCheck size={20} />} accent="var(--ok)" label="Active" value={String(active)} sub={`${pct(active)}% of total`} />
      <DashTile icon={<UserX size={20} />} accent="var(--risk)" label="Inactive" value={String(inactive)} sub={`${pct(inactive)}% of total`} />
    </div>
  );
}

function StatusBadge({ active }: { active: boolean }) {
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5, fontSize: 11, fontWeight: 700, color: active ? '#2FB67C' : '#E4373D', background: active ? 'rgba(47,182,124,.1)' : 'rgba(228,55,61,.1)', borderRadius: 20, padding: '3px 10px', whiteSpace: 'nowrap' }}>
      <span style={{ width: 5, height: 5, borderRadius: '50%', background: active ? '#2FB67C' : '#E4373D' }} />
      {active ? 'Active' : 'Inactive'}
    </span>
  );
}

// ─── Row Actions Menu ──────────────────────────────────────────────────────────
// Page-scoped 3-dot menu for the User Management table (deliberately NOT the
// shared components/KebabMenu.tsx, which is used by 7+ other pages — keeping
// this local to UserManagementPage.tsx means its styling/exclusivity behavior
// can't leak into unrelated modules). `open`/`onToggle`/`onClose` are
// controlled by the parent's single `openMenuId` state, so opening one row's
// menu always closes any other — only one can ever be open at a time. It
// renders exactly the `items` it's handed (same shape as KebabMenu's items), so
// every per-row visibility rule (self / last-active-Super-Admin guards) lives in
// the caller's items array, not here.
interface RowActionItem { label: string; onClick: () => void; danger?: boolean; dividerBefore?: boolean; }

function RowActionsMenu({ items }: {
  open?: boolean;
  onToggle?: () => void;
  onClose?: () => void;
  items: RowActionItem[];
}) {
  return items.length ? <KebabMenu items={items} /> : null;
}

// `opts.managers` (the potential-managers list) is fetched once per page load and shared by
// Add/Edit modals — see UserManagementPage's own comment on that state. Any action here that
// changes a user's active status, role, or name (status toggle, edit, soft delete) needs to
// patch this cached list in place too, or a since-deactivated (or renamed/re-roled) user keeps
// showing up as a selectable manager until the whole page is reloaded.
function syncManagerOption(managers: EmployeeRecord[], updated: EmployeeRecord): EmployeeRecord[] {
  return managers.some(m => m.userId === updated.userId)
    ? managers.map(m => (m.userId === updated.userId ? updated : m))
    : [...managers, updated];
}

// AddModal was replaced by CreateEmployeeWizard — see
// frontend/src/pages/user-management/CreateEmployeeWizard.tsx (rendered at the call site below).

// ─── Edit Modal ───────────────────────────────────────────────────────────────
export function EditModal({ user, onClose, onUpdated, token, opts, setOpts }: {
  user: EmployeeRecord; onClose: () => void; onUpdated: (e: EmployeeRecord) => void; token: string;
  // Shared with AddModal — see AddModal's own comment on these two props for why this isn't
  // fetched fresh here.
  opts: OrgOptions; setOpts: React.Dispatch<React.SetStateAction<OrgOptions>>;
}) {
  const { showToast } = useToast();
  const [form, setForm] = useState<UpdateUserPayload>({
    fullName: user.fullName,
    email: user.email,
    role: user.role,
    businessUnitId: user.businessUnitId ?? undefined,
    departmentId: user.departmentId ?? undefined,
    designationId: user.designationId ?? undefined,
    locationId: user.locationId ?? undefined,
    shiftId: user.shiftId ?? undefined,
    shiftEffectiveFrom: todayIso(),
    employmentType: user.employmentType,
    workMode: user.workMode ?? 'ONSITE',
    managerId: user.currentManager?.userId ?? undefined,
  });
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const isInactive = !user.active;
  const [confirmInactiveEdit, setConfirmInactiveEdit] = useState(false);
  const gatedFieldsLocked = isInactive && !confirmInactiveEdit;

  // Joining date moved in from the old standalone "Update Date of Joining" modal — same
  // usersApi.updateJoiningDate call and audit-trail note, just triggered from this form instead
  // of a separate 3-dot menu entry. Kept as its own request (not part of `form`/usersApi.update)
  // because the backend deliberately keeps it a separate endpoint with its own audit trail —
  // see UserManagementService.updateJoiningDate.
  const [joiningDate, setJoiningDate] = useState(user.joiningDate);
  const [joiningDateNote, setJoiningDateNote] = useState('');

  const emailChanged = (form.email ?? '') !== user.email;
  // Only an actual CHANGE from the employee's current shift requires an Effective From — a no-op
  // resubmission of the same value (e.g. an edit that never touched this field) needs no date,
  // exactly matching UserManagementService#updateUser's own "no-op means no-op" change detection.
  const shiftChanged = form.shiftId !== (user.shiftId ?? undefined);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    const rawEmail = form.email ?? '';
    if (!rawEmail) { setError('Email is required.'); return; }
    // Same checks AddModal runs on create — see EMAIL_PATTERN's comment for why the raw
    // (untrimmed) value is checked rather than silently trimming a leading/trailing space.
    if (rawEmail !== rawEmail.trim()) { setError('Email must not have leading or trailing spaces.'); return; }
    if (!EMAIL_PATTERN.test(rawEmail)) { setError('Enter a valid email address with a proper domain (e.g. name@company.com).'); return; }
    if (form.role !== 'SUPER_ADMIN' && !form.managerId) { setError('Reporting Manager is required for this role.'); return; }
    // Defense-in-depth: the date input's own `required`/`min` already keep this from happening
    // through normal use — the backend independently rejects it too regardless. Only enforced
    // when the shift is an actual CHANGE from the employee's current one (see shiftChanged below)
    // — resubmitting the same value needs no effective date.
    if (shiftChanged && (!form.shiftEffectiveFrom || form.shiftEffectiveFrom < todayIso())) {
      setError('Effective From is required and cannot be in the past.');
      return;
    }
    setSubmitting(true); setError(null);
    try {
      let updated = await usersApi.update(user.userId, { ...form, confirmInactiveEdit }, token);
      if (joiningDate !== user.joiningDate) {
        const payload: UpdateJoiningDatePayload = { newJoiningDate: joiningDate, note: joiningDateNote.trim() || undefined };
        updated = await usersApi.updateJoiningDate(user.userId, payload, token);
      }
      onUpdated(updated);
      showToast('success', `${updated.fullName} updated successfully`);
      onClose();
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Update failed';
      setError(msg);
      showToast('error', msg);
    } finally { setSubmitting(false); }
  }

  const set = (key: keyof UpdateUserPayload, val: any) => setForm(f => ({ ...f, [key]: val || undefined }));

  const canSave = !(submitting || gatedFieldsLocked);
  const isSA = form.role === 'SUPER_ADMIN';
  const mgrList = getManagersForRole(form.role ?? 'EMPLOYEE', opts.managers).filter(m => m.userId !== user.userId);
  const mgrRoleLabel = (form.role ?? '') === 'EMPLOYEE' ? 'Manager or HR Admin'
    : form.role === 'MANAGER' ? 'HR Admin or Super Admin'
    : 'Super Admin';

  return createPortal(
    <div className="nf-ee-overlay" role="presentation">
      <div className="nf-ee-modal" role="dialog" aria-modal="true" aria-label={`Edit ${user.fullName}`}>
        <div className="nf-ee-head">
          <span className="nf-ee-head-ico"><UserCog size={20} /></span>
          <div className="nf-ee-head-txt">
            <div className="nf-ee-title">Edit User</div>
            <div className="nf-ee-sub">Update account, role, organisation and shift details.</div>
          </div>
          <button type="button" className="nf-ee-x" onClick={onClose} aria-label="Close"><XIcon size={16} /></button>
        </div>

        <form onSubmit={handleSubmit} className="nf-ee-form">
          <div className="nf-ee-body">
            <div className="nf-ee-who">
              <PersonAvatar userId={user.userId} name={user.fullName} size={44} />
              <div className="nf-ee-who-txt">
                <div className="nf-ee-who-name">{user.fullName}</div>
                <div className="nf-ee-who-meta">{user.employeeCode} · {user.email}</div>
              </div>
              <span className={`nf-ee-pill ${user.active ? 'ok' : 'off'}`}>{user.active ? 'Active' : 'Inactive'}</span>
            </div>

            {error && <div className="nf-ee-error" role="alert"><AlertCircle size={15} /><span>{error}</span></div>}
            {isInactive && <InactiveEditBanner />}

            <section className="nf-ee-sec">
              <div className="nf-ee-sec-h"><span className="nf-ee-sec-ico"><UserIcon size={14} /></span>Account</div>
              <div className="nf-ee-grid">
                <div className="nf-ee-f full">
                  <label className="nf-ee-l">Full name</label>
                  <input className="nf-ee-in" value={form.fullName ?? ''} onChange={e => setForm(f => ({ ...f, fullName: e.target.value }))} />
                </div>
                <div className="nf-ee-f full">
                  <label className="nf-ee-l">Company email</label>
                  <input type="email" className="nf-ee-in" value={form.email ?? ''} onChange={e => setForm(f => ({ ...f, email: e.target.value }))} placeholder="jane@nforceone.com" />
                  {emailChanged && (
                    <div className="nf-ee-hint warn">Changing the email signs the user out and sends a notice to the new address with a sign-in link.</div>
                  )}
                </div>
              </div>
            </section>

            <section className="nf-ee-sec">
              <div className="nf-ee-sec-h"><span className="nf-ee-sec-ico"><UserCheck size={14} /></span>Role and reporting</div>
              <div className="nf-ee-grid">
                <div className="nf-ee-f full">
                  <label className="nf-ee-l">Role</label>
                  <select className="nf-ee-in" disabled={gatedFieldsLocked} value={form.role ?? 'EMPLOYEE'} onChange={e => {
                    const newRole = e.target.value;
                    const newMgrs = getManagersForRole(newRole, opts.managers).filter(m => m.userId !== user.userId);
                    setForm(f => ({
                      ...f, role: newRole,
                      managerId: f.managerId && newMgrs.some(m => m.userId === f.managerId) ? f.managerId : undefined,
                    }));
                  }}>
                    {ROLES.map(r => <option key={r.value} value={r.value}>{r.label}</option>)}
                  </select>
                </div>
                <div className="nf-ee-f full">
                  <label className="nf-ee-l">{isSA ? 'Reporting manager' : 'Reporting manager *'}</label>
                  <select className="nf-ee-in" disabled={gatedFieldsLocked} value={form.managerId ?? ''} onChange={e => set('managerId', e.target.value)}>
                    <option value="">{isSA ? 'None (optional)' : 'Select a Reporting Manager'}</option>
                    {/* Deactivated managers stay in the list (see getManagersForRole) but as a
                        disabled option, so they can't be picked for a new assignment. They're
                        only shown to explain an EXISTING selection that already points at one. */}
                    {mgrList.map((m: any) => (
                      <option key={m.userId} value={m.userId} disabled={m.active === false}>
                        {m.fullName} ({m.email}){m.active === false ? ' - Inactive' : ''}
                      </option>
                    ))}
                  </select>
                  {!isSA && mgrList.length === 0 && (
                    <div className="nf-ee-hint warn">No {mgrRoleLabel} users found - add one first.</div>
                  )}
                  <ManagerApprovalWarning manager={mgrList.find((m: any) => m.userId === form.managerId)} />
                </div>
              </div>
            </section>

            <section className="nf-ee-sec">
              <div className="nf-ee-sec-h"><span className="nf-ee-sec-ico"><Building2 size={14} /></span>Organisation</div>
              <div className="nf-ee-grid">
                <div className="nf-ee-f">
                  <label className="nf-ee-l">Business unit</label>
                  <select className="nf-ee-in" value={form.businessUnitId ?? ''} onChange={e => set('businessUnitId', e.target.value)}>
                    <option value="">None</option>{opts.businessUnits.map((b: any) => <option key={b.id} value={b.id}>{b.name}</option>)}
                  </select>
                </div>
                <div className="nf-ee-f">
                  <label className="nf-ee-l">Department</label>
                  <select className="nf-ee-in" disabled={gatedFieldsLocked} value={form.departmentId ?? ''} onChange={e => setForm(f => ({ ...f, departmentId: e.target.value || undefined, designationId: undefined }))}>
                    <option value="">None</option>{getDepartmentOptions(opts.departments, form.departmentId).map((d: any) => <option key={d.id} value={d.id}>{d.name}</option>)}
                  </select>
                </div>
                <div className="nf-ee-f full">
                  <label className="nf-ee-l">Designation</label>
                  <select className="nf-ee-in" disabled={gatedFieldsLocked || !form.departmentId} value={form.designationId ?? ''} onChange={e => set('designationId', e.target.value)}>
                    <option value="">{form.departmentId ? 'None' : 'Select a Department first'}</option>
                    {getDesignationOptions(opts.designations, form.designationId, form.departmentId).map((d: any) => <option key={d.id} value={d.id}>{d.title}</option>)}
                  </select>
                </div>
              </div>
            </section>

            <section className="nf-ee-sec">
              <div className="nf-ee-sec-h"><span className="nf-ee-sec-ico"><Briefcase size={14} /></span>Employment</div>
              <div className="nf-ee-grid">
                <div className="nf-ee-f">
                  <label className="nf-ee-l">Employment type</label>
                  <select className="nf-ee-in" disabled={gatedFieldsLocked} value={form.employmentType ?? 'FULL_TIME'} onChange={e => set('employmentType', e.target.value)}>
                    {EMPLOYMENT_TYPES.map(t => <option key={t} value={t}>{t.replace('_', ' ')}</option>)}
                  </select>
                </div>
                <div className="nf-ee-f">
                  <label className="nf-ee-l">Work mode</label>
                  <select className="nf-ee-in" value={form.workMode ?? 'ONSITE'} onChange={e => set('workMode', e.target.value)}>
                    {WORK_MODES.map(m => <option key={m} value={m}>{m.charAt(0) + m.slice(1).toLowerCase()}</option>)}
                  </select>
                </div>
                <div className="nf-ee-f full">
                  <label className="nf-ee-l">Date of joining</label>
                  <input type="date" className="nf-ee-in" value={joiningDate} onChange={e => setJoiningDate(e.target.value)} />
                </div>
                {joiningDate !== user.joiningDate && (
                  <div className="nf-ee-f full">
                    <label className="nf-ee-l">Reason for date change</label>
                    <textarea
                      className="nf-ee-in"
                      value={joiningDateNote}
                      onChange={e => setJoiningDateNote(e.target.value)}
                      placeholder="Reason for changing the date of joining…"
                    />
                  </div>
                )}
              </div>
            </section>

            <section className="nf-ee-sec">
              <div className="nf-ee-sec-h"><span className="nf-ee-sec-ico"><Clock3 size={14} /></span>Shift</div>
              <div className="nf-ee-f">
                <ShiftSelect
                  shifts={getShiftOptions(opts.shifts, form.shiftId)}
                  value={form.shiftId}
                  onChange={id => setForm(f => ({ ...f, shiftId: id }))}
                  onCreated={sh => setOpts(o => ({ ...o, shifts: [...o.shifts, sh] }))}
                  token={token}
                />
              </div>
              {shiftChanged && form.shiftId && (
                <div className="nf-ee-f" style={{ marginTop: 12 }}>
                  <label className="nf-ee-l">Effective from *</label>
                  <input
                    type="date"
                    className="nf-ee-in"
                    value={form.shiftEffectiveFrom ?? todayIso()}
                    min={todayIso()}
                    required
                    onChange={e => setForm(f => ({ ...f, shiftEffectiveFrom: e.target.value }))}
                  />
                  {form.shiftEffectiveFrom && (
                    <div className="nf-ee-hint">This shift will become active for the employee from {fmtEffectiveDate(form.shiftEffectiveFrom)}.</div>
                  )}
                </div>
              )}
            </section>

            <section className="nf-ee-sec">
              <div className="nf-ee-sec-h"><span className="nf-ee-sec-ico"><MapPin size={14} /></span>Location<span className="nf-ee-lock"><Lock size={11} /> Locked</span></div>
              <div className="nf-ee-f">
                <CreatableLocationSelect locations={getLocationOptions(opts.locations, form.locationId)} value={form.locationId} onChange={id => setForm(f => ({ ...f, locationId: id }))} token={token} disabled />
                <div className="nf-ee-hint">
                  Location changes are currently unavailable when updating an employee. Contact an administrator.
                </div>
              </div>
            </section>

            {isInactive && (
              <InactiveFieldsConfirm checked={confirmInactiveEdit} onChange={setConfirmInactiveEdit} fields="Role, Reporting Manager, Department, Designation, or Employment Type" />
            )}
          </div>

          <div className="nf-ee-foot">
            <button type="button" className="nf-ee-cancel" onClick={onClose}>Cancel</button>
            <button type="submit" className="nf-ee-save" disabled={!canSave}>{submitting ? 'Saving…' : 'Save Changes'}</button>
          </div>
        </form>
      </div>
    </div>,
    document.body,
  );
}

// Date-of-joining update now lives inline in EditModal above (see its joiningDate/joiningDateNote
// state) instead of this separate modal — same usersApi.updateJoiningDate call, just reached from
// the "Edit" action instead of its own 3-dot menu entry.

// ─── Reset Password Modal ─────────────────────────────────────────────────────
function ResetPasswordModal({ user, onClose, token }: { user: EmployeeRecord; onClose: () => void; token: string }) {
  const { showToast } = useToast();
  const [state, setState] = useState<'confirm' | 'loading' | 'done' | 'error'>('confirm');
  const [result, setResult] = useState<ResetPasswordResult | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function doReset() {
    setState('loading');
    try {
      const r = await usersApi.resetPassword(user.userId, token);
      setResult(r);
      setState('done');
      showToast('success', `Password reset for ${user.fullName}`);
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Reset failed';
      setError(msg);
      setState('error');
      showToast('error', msg);
    }
  }

  return (
    <div style={overlayStyle}>
      <div className="nf-um-modal" style={{ ...modalStyle, maxWidth: 420 }}>
        <ModalHeader title="Reset Password" onClose={onClose} />
        <div style={{ padding: 24 }}>
          {state === 'confirm' && (
            <>
              <p style={{ fontSize: 13, color: 'var(--txt-mut)', marginBottom: 20 }}>
                Reset password for <b style={{ color: 'var(--txt)' }}>{user.fullName}</b>? A new temporary password will be generated. The user must change it on next login.
              </p>
              <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
                <button onClick={onClose} style={{ background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 18px', fontSize: 13, cursor: 'pointer' }}>Cancel</button>
                <button onClick={doReset} style={{ background: '#C0392B', color: '#fff', border: 'none', borderRadius: 7, padding: '9px 20px', fontSize: 13, fontWeight: 600, cursor: 'pointer' }}>Reset Password</button>
              </div>
            </>
          )}
          {state === 'loading' && <div style={{ textAlign: 'center', color: 'var(--txt-dim)', padding: 20 }}>Generating…</div>}
          {state === 'error' && (
            <>
              <div style={{ color: 'var(--risk)', marginBottom: 16 }}>{error}</div>
              <button onClick={onClose} style={{ background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 18px', fontSize: 13, cursor: 'pointer' }}>Close</button>
            </>
          )}
          {state === 'done' && result && (
            <>
              <div style={{ background: 'rgba(228,55,61,.08)', border: '1px solid rgba(228,55,61,.2)', borderRadius: 8, padding: 14, marginBottom: 20 }}>
                <div style={{ fontSize: 11, fontWeight: 700, color: 'var(--risk)', marginBottom: 6, textTransform: 'uppercase', letterSpacing: '.06em' }}>New temp password - share once, store nowhere</div>
                <code style={{ fontSize: 15, color: 'var(--txt)', fontFamily: 'Inter, sans-serif', userSelect: 'all' }}>{result.tempPassword}</code>
              </div>
              <button onClick={onClose} style={{ width: '100%', background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 7, padding: '10px 16px', fontSize: 14, fontWeight: 600, cursor: 'pointer' }}>Done</button>
            </>
          )}
        </div>
      </div>
    </div>
  );
}

// ─── Status Toggle Confirm ────────────────────────────────────────────────────
// isSelf/isLastActiveSuperAdmin are defense-in-depth only — the row's Deactivate action is
// already hidden for both cases (see the row menu below), so this modal shouldn't normally
// open for them. But the API is the real boundary (UserManagementService.setActiveStatus
// re-checks both server-side), so if it's somehow reached anyway the explicit warning/blocked
// states below still apply, and a stale-list race just surfaces the backend's rejection message.
function StatusModal({ user, isSelf, isLastActiveSuperAdmin, onClose, onUpdated, token }: {
  user: EmployeeRecord; isSelf: boolean; isLastActiveSuperAdmin: boolean;
  onClose: () => void; onUpdated: (e: EmployeeRecord) => void; token: string;
}) {
  const { showToast } = useToast();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const action = user.active ? 'Deactivate' : 'Activate';
  const blocked = user.active && (isSelf || isLastActiveSuperAdmin);

  async function confirm() {
    setLoading(true); setError(null);
    try {
      const updated = await usersApi.setStatus(user.userId, !user.active, token);
      onUpdated(updated);
      showToast('success', `${user.fullName} ${user.active ? 'deactivated' : 'reactivated'}`);
      onClose();
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Status change failed';
      setError(msg);
      showToast('error', msg);
      setLoading(false);
    }
  }

  return (
    <div style={overlayStyle}>
      <div className="nf-um-modal" style={{ ...modalStyle, maxWidth: 400 }}>
        <ModalHeader title={`${action} User`} onClose={onClose} />
        <div style={{ padding: 24 }}>
          {isSelf && user.active ? (
            <div style={{ background: 'rgba(228,55,61,.08)', border: '1px solid rgba(228,55,61,.2)', borderRadius: 8, padding: 14, marginBottom: 20, fontSize: 13, color: 'var(--txt-mut)', lineHeight: 1.7 }}>
              Deactivating your account will end your session. Another Super Admin is required to restore access.
            </div>
          ) : isLastActiveSuperAdmin && user.active ? (
            <div style={{ background: 'rgba(228,55,61,.08)', border: '1px solid rgba(228,55,61,.2)', borderRadius: 8, padding: 14, marginBottom: 20, fontSize: 13, color: 'var(--txt-mut)', lineHeight: 1.7 }}>
              <b style={{ color: 'var(--txt)' }}>{user.fullName}</b> is the last active Super Admin. Deactivating this account would leave nobody able to manage users - assign Super Admin to another account first.
            </div>
          ) : user.active ? (
            <div style={{ background: 'rgba(228,55,61,.08)', border: '1px solid rgba(228,55,61,.2)', borderRadius: 8, padding: 14, marginBottom: 20, fontSize: 13, color: 'var(--txt-mut)', lineHeight: 1.7 }}>
              Deactivating <b style={{ color: 'var(--txt)' }}>{user.fullName}</b> will invalidate their active JWT immediately. They cannot log in until reactivated.
            </div>
          ) : (
            <p style={{ fontSize: 13, color: 'var(--txt-mut)', marginBottom: 20 }}>
              Reactivate <b style={{ color: 'var(--txt)' }}>{user.fullName}</b>? They will be able to log in again.
            </p>
          )}
          {error && <div style={{ color: 'var(--risk)', marginBottom: 12, fontSize: 13 }}>{error}</div>}
          <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
            <button onClick={onClose} style={{ background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 18px', fontSize: 13, cursor: 'pointer' }}>Cancel</button>
            <button onClick={confirm} disabled={loading || blocked} style={{ background: user.active ? '#C0392B' : '#2FB67C', color: '#fff', border: 'none', borderRadius: 7, padding: '9px 20px', fontSize: 13, fontWeight: 600, cursor: (loading || blocked) ? 'not-allowed' : 'pointer', opacity: (loading || blocked) ? 0.5 : 1 }}>
              {loading ? '…' : action}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

// ─── Delete (Soft-Delete) Confirm ─────────────────────────────────────────────
function DeleteModal({ user, onClose, onDeleted, token }: { user: EmployeeRecord; onClose: () => void; onDeleted: (userId: string) => void; token: string }) {
  const { showToast } = useToast();
  const [typed, setTyped] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const match = typed === user.email;

  async function confirm() {
    if (!match) return;
    setLoading(true); setError(null);
    try {
      await usersApi.softDelete(user.userId, token);
      onDeleted(user.userId);
      showToast('success', `${user.fullName} permanently deleted`);
      onClose();
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Delete failed';
      setError(msg);
      showToast('error', msg);
      setLoading(false);
    }
  }

  return (
    <div style={overlayStyle}>
      <div className="nf-um-modal" style={{ ...modalStyle, maxWidth: 440 }}>
        <ModalHeader title="Delete User" onClose={onClose} />
        <div style={{ padding: 24 }}>
          <div style={{ background: 'rgba(228,55,61,.08)', border: '1px solid rgba(228,55,61,.25)', borderRadius: 8, padding: 14, marginBottom: 20 }}>
            <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--risk)', marginBottom: 8 }}>This action is permanent</div>
            <div style={{ fontSize: 13, color: 'var(--txt-mut)', lineHeight: 1.7 }}>
              Deleting <b style={{ color: 'var(--txt)' }}>{user.fullName}</b> ({user.email}) will immediately invalidate their session. This cannot be undone.
            </div>
          </div>
          <label style={{ ...labelStyle, marginBottom: 6 }}>Type the user's email to confirm</label>
          <input
            style={{ ...inputStyle, marginBottom: 4, border: `1px solid ${match ? 'rgba(228,55,61,.5)' : 'var(--line2)'}` }}
            placeholder={user.email}
            value={typed}
            onChange={e => setTyped(e.target.value)}
            autoComplete="off"
            spellCheck={false}
          />
          <p style={{ fontSize: 11, color: 'var(--txt-dim)', marginBottom: 20, marginTop: 4 }}>
            Must match exactly: <code style={{ fontFamily: 'Inter, sans-serif', color: 'var(--txt-mut)' }}>{user.email}</code>
          </p>
          {error && <div style={{ color: 'var(--risk)', marginBottom: 12, fontSize: 13 }}>{error}</div>}
          <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
            <button onClick={onClose} style={{ background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 18px', fontSize: 13, cursor: 'pointer' }}>Cancel</button>
            <button onClick={confirm} disabled={!match || loading} style={{ background: match ? '#C0392B' : 'var(--raised2)', color: match ? '#fff' : 'var(--txt-dim)', border: `1px solid ${match ? 'transparent' : 'var(--line2)'}`, borderRadius: 7, padding: '9px 20px', fontSize: 13, fontWeight: 600, cursor: !match || loading ? 'not-allowed' : 'pointer', opacity: loading ? 0.7 : 1 }}>
              {loading ? 'Deleting…' : 'Delete Permanently'}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}


// ─── Main Page ────────────────────────────────────────────────────────────────
export default function UserManagementPage() {
  const token = useAuthStore(s => s.token)!;
  const currentUserEmail = useAuthStore(s => s.user?.email)?.toLowerCase();
  const permissions = useAuthStore(s => s.permissions);
  const can = useCan();
  // Manage Roles needs the user's role view (user_roles) and the role list (roles) — see RoleController.
  const canManageRoles = hasPermission(permissions, 'user_roles', 'VIEW', 'ALL') && hasPermission(permissions, 'roles', 'VIEW', 'ALL');
  const [managingRoles, setManagingRoles] = useState<EmployeeRecord | null>(null);
  const [users, setUsers] = useState<EmployeeRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [showAdd, setShowAdd] = useState(false);
  const [editing, setEditing] = useState<EmployeeRecord | null>(null);
  const [resetting, setResetting] = useState<EmployeeRecord | null>(null);
  const [toggling, setToggling] = useState<EmployeeRecord | null>(null);
  const [deleting, setDeleting] = useState<EmployeeRecord | null>(null);
  // Which row's RowActionsMenu is open (by userId) — a single value, so only one menu can be
  // open at a time. Purely UI state; has no effect on which actions a row offers.
  const [openMenuId, setOpenMenuId] = useState<string | null>(null);
  // Reference data for the Add/Edit User forms — departments/designations/locations/managers/
  // shifts. Fetched ONCE here (not per-modal-open) and shared by both AddModal and EditModal, so
  // opening either repeatedly doesn't re-run 5 API calls (including the expensive
  // potential-managers lookup) every single time. A newly-created location/shift from inside
  // either modal is appended straight into this shared state, so it's immediately available to
  // the other modal too without a re-fetch.
  const [orgOptions, setOrgOptions] = useState<OrgOptions>({ businessUnits: [], departments: [], designations: [], locations: [], managers: [], shifts: [] });

  // Filters
  const [search, setSearch] = useState('');
  const [statusFilter, setStatusFilter] = useState<'ALL' | 'ACTIVE' | 'INACTIVE'>('ALL');
  const [deptFilter, setDeptFilter] = useState('');
  const [roleFilter, setRoleFilter] = useState('');
  const [page, setPage] = useState(1);
  const PAGE_SIZE = 15;

  useEffect(() => {
    usersApi.list(token).then(setUsers).finally(() => setLoading(false));
  }, [token]);

  useEffect(() => {
    Promise.all([
      orgApi.listBusinessUnits(token),
      orgApi.listDepartments(token),
      orgApi.listDesignations(token),
      orgApi.listLocations(token),
      employeesApi.potentialManagers(token),
      orgApi.listShifts(token),
      // Step 3 (Work & Policy Configuration) dropdown data for the employee creation wizard —
      // same single existing endpoint the My Team bulk-assignment screen already uses, fetched
      // once here alongside everything else above rather than as a separate wizard-only call.
      employeeAssignmentsApi.lookups(token),
    ]).then(([bu, d, des, l, m, sh, lookups]) => setOrgOptions({
      businessUnits: bu, departments: d, designations: des, locations: l, managers: m, shifts: sh,
      weeklyOffPolicies: lookups.weeklyOffPolicies, penalisationPolicies: lookups.penalisationPolicies,
    }));
  }, [token]);

  // Derived filter options from real data
  const departments = Array.from(new Set(users.map(u => u.departmentName).filter(Boolean))) as string[];
  const roleOptions = roleOptionsOf(users);

  // Stats
  const total = users.length;
  const active = users.filter(u => u.active).length;
  const inactive = total - active;
  const roleCounts = roleCountsOf(users);
  const activeSuperAdminCount = users.filter(u => u.role === 'SUPER_ADMIN' && u.active).length;

  // Filter logic
  const filtered = users.filter(u => {
    const q = search.toLowerCase();
    if (q && !u.fullName.toLowerCase().includes(q) && !u.email.toLowerCase().includes(q) && !(u.employeeCode ?? '').toLowerCase().includes(q)) return false;
    if (statusFilter === 'ACTIVE' && !u.active) return false;
    if (statusFilter === 'INACTIVE' && u.active) return false;
    if (deptFilter && u.departmentName !== deptFilter) return false;
    if (roleFilter && !categoriesOf(u).some(c => c.code === roleFilter)) return false;
    return true;
  });

  const totalPages = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE));
  const paginated = filtered.slice((page - 1) * PAGE_SIZE, page * PAGE_SIZE);

  // Reset to page 1 when filters change
  useEffect(() => { setPage(1); }, [search, statusFilter, deptFilter, roleFilter]);

  const thStyle: React.CSSProperties = { padding: '13px 16px', textAlign: 'left', fontSize: 11, fontWeight: 700, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.06em', borderBottom: '1px solid var(--line)', whiteSpace: 'nowrap', background: 'var(--raised)' };
  const tdStyle: React.CSSProperties = { padding: '13px 16px', fontSize: 13, color: 'var(--txt-mut)', borderBottom: '1px solid var(--line)', verticalAlign: 'middle' };
  const filterSelect: React.CSSProperties = { background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 8, padding: '9px 12px', color: 'var(--txt-mut)', fontSize: 12.5, fontWeight: 500, cursor: 'pointer', outline: 'none' };


  // Full-page view switch — replaces the whole table/filters body with the Create Employee
  // wizard, still inside this same /access route (same Shell sidebar/topbar, same Super Admin
  // route protection). No portal, no overlay: CreateEmployeeWizard renders as plain page content.
  if (showAdd) {
    return (
      <CreateEmployeeWizard
        token={token}
        opts={orgOptions}
        setOpts={setOrgOptions}
        onClose={() => setShowAdd(false)}
        onCreated={u => setUsers(prev => [u, ...prev])}
      />
    );
  }

  return (
    <div>
      {/* Header */}
      <div className="nf-policy-header nf-page-hdr" style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 20, flexWrap: 'wrap', gap: 14 }}>
        <PageTitleBlock icon={UserIcon}>
            <h1 style={{ fontFamily: '"Space Grotesk", sans-serif', fontSize: 21, fontWeight: 700, color: 'var(--txt)', margin: 0 }}>User Management</h1>
            <p style={{ fontSize: 13, color: 'var(--txt-mut)', marginTop: 3 }}>Manage access, roles, and account status for all users.</p>
        </PageTitleBlock>
        <div className="nf-policy-actions" style={{ display: 'flex' }}>
          {/* Matches the primary "Add/Create" button used across Organization Masters and Workflow
              Studio (var(--brand) fill, '+' glyph) rather than a one-off gradient + custom icon —
              see hr-ux-audit finding UI-003. */}
          {can('user_management', 'CREATE') && (
          <button onClick={() => setShowAdd(true)} style={{ display: 'flex', alignItems: 'center', gap: 6, background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 7, padding: '8px 16px', fontSize: 13, fontWeight: 600, cursor: 'pointer', justifyContent: 'center' }}>
            <Plus size={14} /> Add User
          </button>
          )}
        </div>
      </div>

      {/* Stats tiles */}
      {!loading && (
        <div style={{ marginBottom: 20 }}>
          <AccessStatTiles total={total} active={active} inactive={inactive} roleSummary={roleOptions.map(r => `${roleCounts[r.code] ?? 0} ${r.name}`).join(' · ')} />
        </div>
      )}

      {/* Search + Filters */}
      <div style={{ display: 'flex', gap: 10, marginBottom: 16, flexWrap: 'wrap', alignItems: 'center', background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, padding: 12 }}>
        <div style={{ position: 'relative', flex: '1 1 240px', minWidth: 200 }}>
          <SearchIcon size={14} style={{ position: 'absolute', left: 12, top: '50%', transform: 'translateY(-50%)', color: 'var(--txt-dim)', pointerEvents: 'none' }} />
          <input
            placeholder="Search by name, email, or employee ID…"
            value={search}
            onChange={e => setSearch(e.target.value)}
            style={{ width: '100%', boxSizing: 'border-box', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 8, padding: '9px 12px 9px 34px', color: 'var(--txt)', fontSize: 13, outline: 'none' }}
          />
        </div>
        <select value={statusFilter} onChange={e => setStatusFilter(e.target.value as any)} style={filterSelect}>
          <option value="ALL">All Status</option>
          <option value="ACTIVE">Active</option>
          <option value="INACTIVE">Inactive</option>
        </select>
        <select value={deptFilter} onChange={e => setDeptFilter(e.target.value)} style={filterSelect}>
          <option value="">All Departments</option>
          {departments.map(d => <option key={d} value={d}>{d}</option>)}
        </select>
        <select value={roleFilter} onChange={e => setRoleFilter(e.target.value)} style={filterSelect}>
          <option value="">All Roles</option>
          {roleOptions.map(r => <option key={r.code} value={r.code}>{r.name}</option>)}
        </select>
        {(search || statusFilter !== 'ALL' || deptFilter || roleFilter) && (
          <button onClick={() => { setSearch(''); setStatusFilter('ALL'); setDeptFilter(''); setRoleFilter(''); }}
            style={{ background: 'none', border: '1px solid var(--line2)', borderRadius: 8, padding: '9px 14px', fontSize: 12.5, fontWeight: 600, color: 'var(--txt-mut)', cursor: 'pointer' }}>
            Clear
          </button>
        )}
      </div>

      {/* Table */}
      <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 14, overflow: 'hidden', boxShadow: '0 1px 2px rgba(0,0,0,.04)' }}>
        {loading ? (
          <div style={{ padding: 40, textAlign: 'center', color: 'var(--txt-dim)' }}>Loading…</div>
        ) : filtered.length === 0 ? (
          <div style={{ padding: 48, textAlign: 'center' }}>
            <div style={{ fontSize: 15, color: 'var(--txt-mut)', marginBottom: 8 }}>{users.length === 0 ? 'No users yet' : 'No results'}</div>
            <div style={{ fontSize: 13, color: 'var(--txt-dim)' }}>
              {users.length === 0 ? 'Click "Add User" to create the first account.' : 'Try adjusting the search or filters.'}
            </div>
          </div>
        ) : (
          <>
            <div style={{ overflowX: 'auto' }}>
              <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                <thead>
                  <tr>
                    {['Name', 'Email', 'Role', 'Department', 'Reporting Manager', 'Status', ''].map(h => (
                      <th key={h} style={thStyle}>{h}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {paginated.map(u => {
                    const isSelf = !!currentUserEmail && u.email.toLowerCase() === currentUserEmail;
                    // Only meaningful while this row is itself an active Super Admin — deactivating
                    // anyone else never touches the Super Admin headcount.
                    const isLastActiveSuperAdmin = u.role === 'SUPER_ADMIN' && u.active && activeSuperAdminCount <= 1;
                    return (
                    <tr key={u.userId} style={{ opacity: u.active ? 1 : 0.6 }}
                      onMouseEnter={e => (e.currentTarget as HTMLTableRowElement).style.background = 'var(--raised)'}
                      onMouseLeave={e => (e.currentTarget as HTMLTableRowElement).style.background = 'transparent'}>
                      <td style={tdStyle}>
                        <PersonCell userId={u.userId} name={u.fullName} code={u.employeeCode} />
                      </td>
                      <td style={{ ...tdStyle, color: 'var(--txt)' }}>{u.email}</td>
                      <td style={tdStyle}>
                        {(() => {
                          // One badge per role list the person is in (never "Employee" alongside an
                          // administrative or custom role); the first two are shown, the rest as +N.
                          const cats = categoriesOf(u);
                          const shown = cats.slice(0, 2);
                          const rest = cats.slice(2);
                          return (
                            <span style={{ display: 'inline-flex', gap: 5, flexWrap: 'wrap', alignItems: 'center' }}>
                              {shown.map(c => <RoleBadge key={c.code} role={c.code} name={c.name} />)}
                              {rest.length > 0 && (
                                <span title={rest.map(c => c.name).join(', ')} style={{ fontSize: 10.5, fontWeight: 700, color: 'var(--txt-mut)', background: 'var(--raised2)', borderRadius: 6, padding: '3px 7px', whiteSpace: 'nowrap' }}>
                                  +{rest.length}
                                </span>
                              )}
                            </span>
                          );
                        })()}
                      </td>
                      <td style={tdStyle}>{u.departmentName ?? <span style={{ color: 'var(--txt-dim)' }}>-</span>}</td>
                      <td style={tdStyle}>{u.currentManager ? u.currentManager.fullName : <span style={{ color: 'var(--txt-dim)' }}>-</span>}</td>
                      <td style={tdStyle}><StatusBadge active={u.active} /></td>
                      <td style={{ ...tdStyle, padding: '8px 12px', width: 44 }}>
                        <RowActionsMenu
                          open={openMenuId === u.userId}
                          onToggle={() => setOpenMenuId(id => id === u.userId ? null : u.userId)}
                          onClose={() => setOpenMenuId(null)}
                          items={[
                          ...(can('user_management', 'EDIT') ? [{ label: 'Edit', onClick: () => setEditing(u) }] : []),
                          ...(canManageRoles ? [{ label: 'Manage Roles', onClick: () => setManagingRoles(u) }] : []),
                          ...(can('user_management', 'EDIT') ? [{ label: 'Reset Password', onClick: () => setResetting(u) }] : []),
                          // Deactivate is hidden outright for the logged-in Super Admin's own row
                          // and for the last remaining active Super Admin — both are unrecoverable
                          // in-app once a session ends, so there's no "disabled with tooltip"
                          // middle ground here (see setActiveStatus on the backend for the
                          // matching, authoritative check).
                          ...(!can('user_management', 'EDIT') || (u.active && (isSelf || isLastActiveSuperAdmin)) ? [] : [
                            { label: u.active ? 'Deactivate' : 'Reactivate', onClick: () => setToggling(u) },
                          ]),
                          // Self-delete carries the exact same lockout risk as self-deactivation.
                          ...(isSelf || !can('user_management', 'DELETE') ? [] : [
                            { label: 'Delete', onClick: () => setDeleting(u), danger: true, dividerBefore: true },
                          ]),
                          ]}
                        />
                      </td>
                    </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>

            {/* Pagination */}
            {totalPages > 1 && (
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '12px 18px', borderTop: '1px solid var(--line)', flexWrap: 'wrap', gap: 8 }}>
                <span style={{ fontSize: 12, color: 'var(--txt-dim)' }}>
                  {filtered.length} result{filtered.length !== 1 ? 's' : ''} · page {page} of {totalPages}
                </span>
                <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                  <button onClick={() => setPage(p => Math.max(1, p - 1))} disabled={page === 1}
                    style={{ background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 7, padding: '6px 13px', fontSize: 12, color: page === 1 ? 'var(--txt-dim)' : 'var(--txt-mut)', cursor: page === 1 ? 'not-allowed' : 'pointer' }}>
                    ← Prev
                  </button>
                  {Array.from({ length: Math.min(5, totalPages) }, (_, i) => {
                    const start = Math.max(1, Math.min(page - 2, totalPages - 4));
                    const p = start + i;
                    return p <= totalPages ? (
                      <button key={p} onClick={() => setPage(p)}
                        style={{ background: p === page ? 'var(--brand)' : 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 7, padding: '6px 10px', fontSize: 12, color: p === page ? '#fff' : 'var(--txt-mut)', cursor: 'pointer', minWidth: 32 }}>
                        {p}
                      </button>
                    ) : null;
                  })}
                  <button onClick={() => setPage(p => Math.min(totalPages, p + 1))} disabled={page === totalPages}
                    style={{ background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 7, padding: '6px 13px', fontSize: 12, color: page === totalPages ? 'var(--txt-dim)' : 'var(--txt-mut)', cursor: page === totalPages ? 'not-allowed' : 'pointer' }}>
                    Next →
                  </button>
                </div>
              </div>
            )}
          </>
        )}
      </div>

      {/* Rendered at the top level of the document (not inside the animated page content): a fixed overlay nested in a
          transformed ancestor is positioned against that ancestor, not the window, which pushed these dialogs under
          the sticky top bar. Same approach DetailDrawer uses on the Attendance page. */}
      {createPortal(<>
      {editing && <EditModal user={editing} token={token} opts={orgOptions} setOpts={setOrgOptions} onClose={() => setEditing(null)} onUpdated={updated => {
        setUsers(prev => prev.map(u => u.userId === updated.userId ? updated : u));
        setOrgOptions(o => ({ ...o, managers: syncManagerOption(o.managers, updated) }));
      }} />}
      {managingRoles && (
        <ManageUserRolesModal token={token} userId={managingRoles.userId}
          isSelf={!!currentUserEmail && managingRoles.email.toLowerCase() === currentUserEmail}
          onClose={() => setManagingRoles(null)}
          onSaved={() => { usersApi.list(token).then(setUsers).catch(() => {}); }} />
      )}
      {resetting && <ResetPasswordModal user={resetting} token={token} onClose={() => setResetting(null)} />}
      {toggling && (
        <StatusModal
          user={toggling}
          isSelf={!!currentUserEmail && toggling.email.toLowerCase() === currentUserEmail}
          isLastActiveSuperAdmin={toggling.role === 'SUPER_ADMIN' && toggling.active && activeSuperAdminCount <= 1}
          token={token}
          onClose={() => setToggling(null)}
          onUpdated={updated => {
            setUsers(prev => prev.map(u => u.userId === updated.userId ? updated : u));
            setOrgOptions(o => ({ ...o, managers: syncManagerOption(o.managers, updated) }));
          }}
        />
      )}
      {deleting && <DeleteModal user={deleting} token={token} onClose={() => setDeleting(null)} onDeleted={userId => {
        setUsers(prev => prev.filter(u => u.userId !== userId));
        setOrgOptions(o => ({ ...o, managers: o.managers.filter(m => m.userId !== userId) }));
      }} />}
      </>, document.body)}
    </div>
  );
}
