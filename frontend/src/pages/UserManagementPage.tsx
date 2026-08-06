import { useEffect, useRef, useState } from 'react';
import { UserPlus, X, ChevronDown, MoreVertical } from 'lucide-react';
import { useAuthStore } from '../store/authStore';
import { usersApi, employeesApi, type EmployeeRecord, type CreateUserPayload, type UpdateUserPayload, type ResetPasswordResult } from '../api/employees';
import { orgApi } from '../api/org';
import { useToast } from '../context/ToastContext';

const ROLES = [
  { value: 'EMPLOYEE',    label: 'Employee' },
  { value: 'MANAGER',     label: 'Manager' },
  { value: 'HR_ADMIN',    label: 'HR Admin' },
  { value: 'SUPER_ADMIN', label: 'Super Admin' },
];

const EMPLOYMENT_TYPES = ['FULL_TIME', 'PART_TIME', 'CONTRACT', 'INTERN'];
const WORK_MODES = ['ONSITE', 'HYBRID', 'REMOTE'];

const ROLE_COLOR: Record<string, string> = {
  EMPLOYEE: '#2FB67C', MANAGER: '#4C8DD6', HR_ADMIN: '#E0A93B', SUPER_ADMIN: '#E4373D',
};

function RoleBadge({ role }: { role: string }) {
  return (
    <span style={{ fontSize: 11, fontWeight: 600, color: ROLE_COLOR[role] ?? '#9BA1AC', background: 'var(--raised)', border: '1px solid var(--line)', borderRadius: 4, padding: '2px 7px' }}>
      {role.replace(/_/g, ' ')}
    </span>
  );
}

function StatusBadge({ active }: { active: boolean }) {
  return (
    <span style={{ fontSize: 11, fontWeight: 600, color: active ? '#2FB67C' : '#E4373D', background: active ? 'rgba(47,182,124,.1)' : 'rgba(228,55,61,.1)', borderRadius: 4, padding: '2px 7px' }}>
      {active ? 'Active' : 'Inactive'}
    </span>
  );
}

interface OrgOptions { departments: any[]; designations: any[]; locations: any[]; managers: EmployeeRecord[]; }

const overlayStyle: React.CSSProperties = { position: 'fixed', inset: 0, background: 'rgba(0,0,0,.65)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 500 };
const modalStyle: React.CSSProperties = { background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, width: '94vw', maxWidth: 520, maxHeight: '92vh', overflowY: 'auto', boxShadow: '0 24px 64px rgba(0,0,0,.55)' };
const inputStyle: React.CSSProperties = { width: '100%', background: 'var(--shell)', border: '1px solid var(--line2)', borderRadius: 6, padding: '9px 11px', color: 'var(--txt)', fontSize: 13, boxSizing: 'border-box', outline: 'none' };
const labelStyle: React.CSSProperties = { display: 'block', fontSize: 11, fontWeight: 600, color: 'var(--txt-mut)', marginBottom: 5, textTransform: 'uppercase', letterSpacing: '.06em' };

function ModalHeader({ title, onClose }: { title: string; onClose: () => void }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '16px 20px', borderBottom: '1px solid var(--line)' }}>
      <span style={{ fontFamily: '"Space Grotesk", sans-serif', fontWeight: 700, fontSize: 15, color: 'var(--txt)' }}>{title}</span>
      <button onClick={onClose} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt-dim)', padding: 4, borderRadius: 4, display: 'flex', alignItems: 'center' }}><X size={16} /></button>
    </div>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return <div><label style={labelStyle}>{label}</label>{children}</div>;
}

// ─── Creatable Location Select ────────────────────────────────────────────────
interface Location { id: string; name: string; }

function CreatableLocationSelect({ locations, value, onChange, token }: { locations: Location[]; value: string | undefined; onChange: (id: string | undefined) => void; token: string }) {
  const [query, setQuery] = useState('');
  const [open, setOpen] = useState(false);
  const [creating, setCreating] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const currentName = value ? (locations.find(l => l.id === value)?.name ?? '') : '';
  useEffect(() => { if (!open) setQuery(currentName); }, [open, currentName]);
  useEffect(() => {
    function out(e: MouseEvent) { if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false); }
    document.addEventListener('mousedown', out);
    return () => document.removeEventListener('mousedown', out);
  }, []);
  const filtered = query.trim() ? locations.filter(l => l.name.toLowerCase().includes(query.toLowerCase())) : locations;
  const exactMatch = locations.some(l => l.name.toLowerCase() === query.trim().toLowerCase());
  const showCreate = query.trim().length > 0 && !exactMatch;
  async function handleCreate() {
    setCreating(true);
    try {
      const newLoc = await orgApi.createLocation(token, { name: query.trim() });
      locations.push(newLoc); onChange(newLoc.id); setOpen(false);
    } finally { setCreating(false); }
  }
  return (
    <div ref={ref} style={{ position: 'relative' }}>
      <div style={{ position: 'relative' }}>
        <input style={{ ...inputStyle, paddingRight: 32 }} placeholder="Select or type a new location…"
          value={open ? query : currentName}
          onFocus={() => { setOpen(true); setQuery(currentName); }}
          onChange={e => { setQuery(e.target.value); setOpen(true); if (!e.target.value) onChange(undefined); }} />
        <ChevronDown size={14} style={{ position: 'absolute', right: 10, top: '50%', transform: 'translateY(-50%)', color: 'var(--txt-dim)', pointerEvents: 'none' }} />
      </div>
      {open && (
        <div style={{ position: 'absolute', top: '100%', left: 0, right: 0, marginTop: 4, background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 7, boxShadow: '0 8px 24px rgba(0,0,0,.3)', zIndex: 100, maxHeight: 200, overflowY: 'auto' }}>
          {filtered.length === 0 && !showCreate && <div style={{ padding: '10px 14px', fontSize: 13, color: 'var(--txt-dim)' }}>No locations found</div>}
          {filtered.map(l => (
            <div key={l.id} onMouseDown={() => { onChange(l.id); setOpen(false); }}
              style={{ padding: '9px 14px', fontSize: 13, color: value === l.id ? 'var(--brand-bright)' : 'var(--txt)', background: value === l.id ? 'rgba(176,17,22,.12)' : 'transparent', cursor: 'pointer' }}
              onMouseEnter={e => (e.currentTarget.style.background = 'var(--raised)')}
              onMouseLeave={e => (e.currentTarget.style.background = value === l.id ? 'rgba(176,17,22,.12)' : 'transparent')}>
              {l.name}
            </div>
          ))}
          {showCreate && (
            <div onMouseDown={creating ? undefined : handleCreate}
              style={{ padding: '9px 14px', fontSize: 13, color: '#4C8DD6', borderTop: filtered.length > 0 ? '1px solid var(--line)' : 'none', cursor: creating ? 'not-allowed' : 'pointer', display: 'flex', alignItems: 'center', gap: 6 }}>
              <span style={{ fontWeight: 700 }}>+</span> {creating ? 'Creating…' : `Create "${query.trim()}"`}
            </div>
          )}
          <div onMouseDown={() => { onChange(undefined); setQuery(''); setOpen(false); }}
            style={{ padding: '9px 14px', fontSize: 12, color: 'var(--txt-dim)', borderTop: '1px solid var(--line)', cursor: 'pointer' }}>
            — Clear —
          </div>
        </div>
      )}
    </div>
  );
}

// ─── Add User Modal ───────────────────────────────────────────────────────────
function AddModal({ onClose, onCreated, token }: { onClose: () => void; onCreated: (e: EmployeeRecord) => void; token: string }) {
  const { showToast } = useToast();
  const [form, setForm] = useState<CreateUserPayload>({ fullName: '', email: '', role: 'EMPLOYEE', joiningDate: new Date().toISOString().slice(0, 10), workMode: 'ONSITE' });
  const [opts, setOpts] = useState<OrgOptions>({ departments: [], designations: [], locations: [], managers: [] });
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [created, setCreated] = useState<EmployeeRecord | null>(null);

  useEffect(() => {
    Promise.all([
      orgApi.listDepartments(token),
      orgApi.listDesignations(token),
      orgApi.listLocations(token),
      employeesApi.potentialManagers(token),
    ]).then(([d, des, l, m]) => setOpts({ departments: d, designations: des, locations: l, managers: m }));
  }, [token]);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!form.fullName.trim() || !form.email.trim()) { setError('Name and email required.'); return; }
    setSubmitting(true); setError(null);
    try {
      const emp = await usersApi.create(form, token);
      setCreated(emp);
      onCreated(emp);
      showToast('success', `${emp.fullName} created successfully`);
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Create failed';
      setError(msg);
      showToast('error', msg);
    } finally { setSubmitting(false); }
  }

  if (created) {
    return (
      <div style={overlayStyle}>
        <div style={modalStyle}>
          <ModalHeader title="User Created" onClose={onClose} />
          <div style={{ padding: 24 }}>
            <div style={{ background: 'rgba(47,182,124,.1)', border: '1px solid rgba(47,182,124,.25)', borderRadius: 8, padding: 16, marginBottom: 16 }}>
              <div style={{ color: '#2FB67C', fontWeight: 600, marginBottom: 8 }}>Account created successfully</div>
              <div style={{ fontSize: 13, color: 'var(--txt-mut)', lineHeight: 1.8 }}>
                <b style={{ color: 'var(--txt)' }}>Name:</b> {created.fullName}<br />
                <b style={{ color: 'var(--txt)' }}>Email:</b> {created.email}<br />
                <b style={{ color: 'var(--txt)' }}>Employee ID:</b> {created.employeeCode}<br />
                <b style={{ color: 'var(--txt)' }}>Role:</b> {created.role}
              </div>
            </div>
            {created.tempPassword && (
              <div style={{ background: 'rgba(228,55,61,.08)', border: '1px solid rgba(228,55,61,.2)', borderRadius: 8, padding: 14 }}>
                <div style={{ fontSize: 11, fontWeight: 700, color: 'var(--risk)', marginBottom: 6, textTransform: 'uppercase', letterSpacing: '.06em' }}>Temp password — share once, store nowhere</div>
                <code style={{ fontSize: 14, color: 'var(--txt)', fontFamily: 'monospace', userSelect: 'all' }}>{created.tempPassword}</code>
              </div>
            )}
            <button onClick={onClose} style={{ marginTop: 20, width: '100%', background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 7, padding: '10px 16px', fontSize: 14, fontWeight: 600, cursor: 'pointer' }}>Done</button>
          </div>
        </div>
      </div>
    );
  }

  const set = (key: keyof CreateUserPayload, val: any) => setForm(f => ({ ...f, [key]: val || undefined }));

  return (
    <div style={overlayStyle}>
      <div style={{ ...modalStyle, maxWidth: 580 }}>
        <ModalHeader title="Add User" onClose={onClose} />
        <form onSubmit={handleSubmit} style={{ padding: 24, display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 14 }}>
          {error && <div style={{ gridColumn: '1/-1', color: 'var(--risk)', background: 'rgba(228,55,61,.08)', border: '1px solid rgba(228,55,61,.2)', borderRadius: 6, padding: '10px 14px', fontSize: 13 }}>{error}</div>}
          <div style={{ gridColumn: '1/-1' }}><Field label="Full Name *"><input style={inputStyle} value={form.fullName} onChange={e => setForm(f => ({ ...f, fullName: e.target.value }))} placeholder="Jane Smith" /></Field></div>
          <div style={{ gridColumn: '1/-1' }}><Field label="Company Email *"><input type="email" style={inputStyle} value={form.email} onChange={e => setForm(f => ({ ...f, email: e.target.value }))} placeholder="jane@nforceone.com" /></Field></div>
          <Field label="Role *">
            <select style={inputStyle} value={form.role} onChange={e => setForm(f => ({ ...f, role: e.target.value }))}>
              {ROLES.map(r => <option key={r.value} value={r.value}>{r.label}</option>)}
            </select>
          </Field>
          <Field label="Employee ID (auto if blank)"><input style={inputStyle} value={form.employeeCode ?? ''} onChange={e => set('employeeCode', e.target.value)} placeholder="NF-00001" /></Field>
          <Field label="Joining Date *"><input type="date" style={inputStyle} value={form.joiningDate} onChange={e => setForm(f => ({ ...f, joiningDate: e.target.value }))} /></Field>
          <Field label="Employment Type">
            <select style={inputStyle} value={form.employmentType ?? 'FULL_TIME'} onChange={e => set('employmentType', e.target.value)}>
              {EMPLOYMENT_TYPES.map(t => <option key={t} value={t}>{t.replace('_', ' ')}</option>)}
            </select>
          </Field>
          <Field label="Work Mode">
            <select style={inputStyle} value={form.workMode ?? 'ONSITE'} onChange={e => set('workMode', e.target.value)}>
              {WORK_MODES.map(m => <option key={m} value={m}>{m.charAt(0) + m.slice(1).toLowerCase()}</option>)}
            </select>
          </Field>
          <Field label="Department">
            <select style={inputStyle} value={form.departmentId ?? ''} onChange={e => set('departmentId', e.target.value)}>
              <option value="">— None —</option>{opts.departments.map((d: any) => <option key={d.id} value={d.id}>{d.name}</option>)}
            </select>
          </Field>
          <Field label="Designation">
            <select style={inputStyle} value={form.designationId ?? ''} onChange={e => set('designationId', e.target.value)}>
              <option value="">— None —</option>{opts.designations.map((d: any) => <option key={d.id} value={d.id}>{d.title}</option>)}
            </select>
          </Field>
          <div style={{ gridColumn: '1/-1' }}>
            <Field label="Location">
              <CreatableLocationSelect locations={opts.locations} value={form.locationId} onChange={id => setForm(f => ({ ...f, locationId: id }))} token={token} />
            </Field>
          </div>
          <div style={{ gridColumn: '1/-1' }}>
            <Field label="Manager">
              <select style={inputStyle} value={form.managerId ?? ''} onChange={e => set('managerId', e.target.value)}>
                <option value="">— None —</option>{opts.managers.map((m: any) => <option key={m.userId} value={m.userId}>{m.fullName} ({m.email})</option>)}
              </select>
            </Field>
          </div>
          <div style={{ gridColumn: '1/-1', display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
            <button type="button" onClick={onClose} style={{ background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 18px', fontSize: 13, cursor: 'pointer' }}>Cancel</button>
            <button type="submit" disabled={submitting} style={{ background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 7, padding: '9px 20px', fontSize: 13, fontWeight: 600, cursor: submitting ? 'not-allowed' : 'pointer', opacity: submitting ? 0.7 : 1 }}>{submitting ? 'Creating…' : 'Create User'}</button>
          </div>
        </form>
      </div>
    </div>
  );
}

// ─── Edit Modal ───────────────────────────────────────────────────────────────
function EditModal({ user, onClose, onUpdated, token }: { user: EmployeeRecord; onClose: () => void; onUpdated: (e: EmployeeRecord) => void; token: string }) {
  const { showToast } = useToast();
  const [form, setForm] = useState<UpdateUserPayload>({
    fullName: user.fullName,
    role: user.role,
    departmentId: user.departmentId ?? undefined,
    designationId: user.designationId ?? undefined,
    locationId: user.locationId ?? undefined,
    employmentType: user.employmentType,
    workMode: user.workMode ?? 'ONSITE',
    managerId: user.currentManager?.userId ?? undefined,
  });
  const [opts, setOpts] = useState<OrgOptions>({ departments: [], designations: [], locations: [], managers: [] });
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    Promise.all([
      orgApi.listDepartments(token),
      orgApi.listDesignations(token),
      orgApi.listLocations(token),
      employeesApi.potentialManagers(token),
    ]).then(([d, des, l, m]) => setOpts({ departments: d, designations: des, locations: l, managers: m }));
  }, [token]);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setSubmitting(true); setError(null);
    try {
      const updated = await usersApi.update(user.userId, form, token);
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

  return (
    <div style={overlayStyle}>
      <div style={{ ...modalStyle, maxWidth: 580 }}>
        <ModalHeader title={`Edit — ${user.fullName}`} onClose={onClose} />
        <form onSubmit={handleSubmit} style={{ padding: 24, display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 14 }}>
          {error && <div style={{ gridColumn: '1/-1', color: 'var(--risk)', background: 'rgba(228,55,61,.08)', border: '1px solid rgba(228,55,61,.2)', borderRadius: 6, padding: '10px 14px', fontSize: 13 }}>{error}</div>}
          <div style={{ gridColumn: '1/-1' }}><Field label="Full Name"><input style={inputStyle} value={form.fullName ?? ''} onChange={e => setForm(f => ({ ...f, fullName: e.target.value }))} /></Field></div>
          <Field label="Role">
            <select style={inputStyle} value={form.role ?? 'EMPLOYEE'} onChange={e => setForm(f => ({ ...f, role: e.target.value }))}>
              {ROLES.map(r => <option key={r.value} value={r.value}>{r.label}</option>)}
            </select>
          </Field>
          <Field label="Employment Type">
            <select style={inputStyle} value={form.employmentType ?? 'FULL_TIME'} onChange={e => set('employmentType', e.target.value)}>
              {EMPLOYMENT_TYPES.map(t => <option key={t} value={t}>{t.replace('_', ' ')}</option>)}
            </select>
          </Field>
          <Field label="Work Mode">
            <select style={inputStyle} value={form.workMode ?? 'ONSITE'} onChange={e => set('workMode', e.target.value)}>
              {WORK_MODES.map(m => <option key={m} value={m}>{m.charAt(0) + m.slice(1).toLowerCase()}</option>)}
            </select>
          </Field>
          <Field label="Department">
            <select style={inputStyle} value={form.departmentId ?? ''} onChange={e => set('departmentId', e.target.value)}>
              <option value="">— None —</option>{opts.departments.map((d: any) => <option key={d.id} value={d.id}>{d.name}</option>)}
            </select>
          </Field>
          <Field label="Designation">
            <select style={inputStyle} value={form.designationId ?? ''} onChange={e => set('designationId', e.target.value)}>
              <option value="">— None —</option>{opts.designations.map((d: any) => <option key={d.id} value={d.id}>{d.title}</option>)}
            </select>
          </Field>
          <div style={{ gridColumn: '1/-1' }}>
            <Field label="Location">
              <CreatableLocationSelect locations={opts.locations} value={form.locationId} onChange={id => setForm(f => ({ ...f, locationId: id }))} token={token} />
            </Field>
          </div>
          <div style={{ gridColumn: '1/-1' }}>
            <Field label="Manager">
              <select style={inputStyle} value={form.managerId ?? ''} onChange={e => set('managerId', e.target.value)}>
                <option value="">— None —</option>
                {opts.managers.filter(m => m.userId !== user.userId).map((m: any) => (
                  <option key={m.userId} value={m.userId}>{m.fullName} ({m.email})</option>
                ))}
              </select>
            </Field>
          </div>
          <div style={{ gridColumn: '1/-1', display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
            <button type="button" onClick={onClose} style={{ background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 7, padding: '9px 18px', fontSize: 13, cursor: 'pointer' }}>Cancel</button>
            <button type="submit" disabled={submitting} style={{ background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 7, padding: '9px 20px', fontSize: 13, fontWeight: 600, cursor: submitting ? 'not-allowed' : 'pointer', opacity: submitting ? 0.7 : 1 }}>{submitting ? 'Saving…' : 'Save Changes'}</button>
          </div>
        </form>
      </div>
    </div>
  );
}

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
      <div style={{ ...modalStyle, maxWidth: 420 }}>
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
                <div style={{ fontSize: 11, fontWeight: 700, color: 'var(--risk)', marginBottom: 6, textTransform: 'uppercase', letterSpacing: '.06em' }}>New temp password — share once, store nowhere</div>
                <code style={{ fontSize: 15, color: 'var(--txt)', fontFamily: 'monospace', userSelect: 'all' }}>{result.tempPassword}</code>
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
function StatusModal({ user, onClose, onUpdated, token }: { user: EmployeeRecord; onClose: () => void; onUpdated: (e: EmployeeRecord) => void; token: string }) {
  const { showToast } = useToast();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const action = user.active ? 'Deactivate' : 'Activate';

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
      <div style={{ ...modalStyle, maxWidth: 400 }}>
        <ModalHeader title={`${action} User`} onClose={onClose} />
        <div style={{ padding: 24 }}>
          {user.active ? (
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
            <button onClick={confirm} disabled={loading} style={{ background: user.active ? '#C0392B' : '#2FB67C', color: '#fff', border: 'none', borderRadius: 7, padding: '9px 20px', fontSize: 13, fontWeight: 600, cursor: loading ? 'not-allowed' : 'pointer', opacity: loading ? 0.7 : 1 }}>
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
  const match = typed.trim().toLowerCase() === user.email.toLowerCase();

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
      <div style={{ ...modalStyle, maxWidth: 440 }}>
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
            Must match exactly: <code style={{ fontFamily: 'monospace', color: 'var(--txt-mut)' }}>{user.email}</code>
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

// ─── Kebab Menu ───────────────────────────────────────────────────────────────
function KebabMenu({ items }: { items: { label: string; onClick: () => void; danger?: boolean; dividerBefore?: boolean }[] }) {
  const [open, setOpen] = useState(false);
  if (items.length === 0) return null;
  return (
    <div style={{ position: 'relative', display: 'inline-block' }}>
      <button
        onClick={e => { e.stopPropagation(); setOpen(o => !o); }}
        title="Actions"
        aria-label="Actions"
        style={{
          background: 'transparent', border: '1px solid transparent', borderRadius: 6,
          width: 30, height: 30, cursor: 'pointer', color: 'var(--txt-mut)',
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          transition: 'background .15s, border-color .15s, color .15s',
        }}
        onMouseEnter={e => { (e.currentTarget as HTMLButtonElement).style.background = 'var(--raised2)'; (e.currentTarget as HTMLButtonElement).style.borderColor = 'var(--line2)'; (e.currentTarget as HTMLButtonElement).style.color = 'var(--txt)'; }}
        onMouseLeave={e => { if (!open) { (e.currentTarget as HTMLButtonElement).style.background = 'transparent'; (e.currentTarget as HTMLButtonElement).style.borderColor = 'transparent'; (e.currentTarget as HTMLButtonElement).style.color = 'var(--txt-mut)'; } }}
      >
        <MoreVertical size={14} />
      </button>
      {open && (
        <>
          <div onClick={() => setOpen(false)} style={{ position: 'fixed', inset: 0, zIndex: 98 }} />
          <div style={{ position: 'absolute', right: 0, top: '110%', background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 8, boxShadow: '0 12px 32px rgba(0,0,0,.45)', zIndex: 99, minWidth: 168, overflow: 'hidden' }}>
            {items.map((item, i) => (
              <button
                key={i}
                onClick={e => { e.stopPropagation(); item.onClick(); setOpen(false); }}
                style={{
                  display: 'block', width: '100%', textAlign: 'left',
                  padding: '9px 14px', fontSize: 12.5, fontWeight: 500,
                  background: 'none', border: 'none', cursor: 'pointer',
                  color: item.danger ? '#E4373D' : 'var(--txt)',
                  borderTop: item.dividerBefore ? '1px solid var(--line)' : 'none',
                  transition: 'background .1s',
                }}
                onMouseEnter={e => { (e.currentTarget as HTMLButtonElement).style.background = item.danger ? 'rgba(228,55,61,.08)' : 'var(--raised)'; }}
                onMouseLeave={e => { (e.currentTarget as HTMLButtonElement).style.background = 'none'; }}
              >
                {item.label}
              </button>
            ))}
          </div>
        </>
      )}
    </div>
  );
}

// ─── Main Page ────────────────────────────────────────────────────────────────
export default function UserManagementPage() {
  const token = useAuthStore(s => s.token)!;
  const [users, setUsers] = useState<EmployeeRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [showAdd, setShowAdd] = useState(false);
  const [editing, setEditing] = useState<EmployeeRecord | null>(null);
  const [resetting, setResetting] = useState<EmployeeRecord | null>(null);
  const [toggling, setToggling] = useState<EmployeeRecord | null>(null);
  const [deleting, setDeleting] = useState<EmployeeRecord | null>(null);

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

  // Derived filter options from real data
  const departments = Array.from(new Set(users.map(u => u.departmentName).filter(Boolean))) as string[];
  const roles = Array.from(new Set(users.map(u => u.role).filter(Boolean))) as string[];

  // Stats
  const total = users.length;
  const active = users.filter(u => u.active).length;
  const inactive = total - active;
  const roleCounts: Record<string, number> = {};
  users.forEach(u => { if (u.role) roleCounts[u.role] = (roleCounts[u.role] ?? 0) + 1; });

  // Filter logic
  const filtered = users.filter(u => {
    const q = search.toLowerCase();
    if (q && !u.fullName.toLowerCase().includes(q) && !u.email.toLowerCase().includes(q) && !(u.employeeCode ?? '').toLowerCase().includes(q)) return false;
    if (statusFilter === 'ACTIVE' && !u.active) return false;
    if (statusFilter === 'INACTIVE' && u.active) return false;
    if (deptFilter && u.departmentName !== deptFilter) return false;
    if (roleFilter && u.role !== roleFilter) return false;
    return true;
  });

  const totalPages = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE));
  const paginated = filtered.slice((page - 1) * PAGE_SIZE, page * PAGE_SIZE);

  // Reset to page 1 when filters change
  useEffect(() => { setPage(1); }, [search, statusFilter, deptFilter, roleFilter]);

  const thStyle: React.CSSProperties = { padding: '10px 14px', textAlign: 'left', fontSize: 11, fontWeight: 700, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.07em', borderBottom: '1px solid var(--line)', whiteSpace: 'nowrap' };
  const tdStyle: React.CSSProperties = { padding: '12px 14px', fontSize: 13, color: 'var(--txt-mut)', borderBottom: '1px solid var(--line)', verticalAlign: 'middle' };
  const filterSelect: React.CSSProperties = { background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, padding: '7px 10px', color: 'var(--txt-mut)', fontSize: 12, cursor: 'pointer', outline: 'none' };

  const ROLE_LABEL: Record<string, string> = { EMPLOYEE: 'Employee', MANAGER: 'Manager', HR_ADMIN: 'HR Admin', SUPER_ADMIN: 'Super Admin' };

  const tileStyle = (accent: string): React.CSSProperties => ({
    background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10,
    padding: '14px 18px', flex: '1 1 140px',
    borderLeft: `3px solid ${accent}`,
  });

  return (
    <div>
      {/* Header */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 18 }}>
        <div>
          <h1 style={{ fontFamily: '"Space Grotesk", sans-serif', fontSize: 20, fontWeight: 700, color: 'var(--txt)', margin: 0 }}>User Management</h1>
          <p style={{ fontSize: 13, color: 'var(--txt-mut)', marginTop: 4 }}>Manage access, roles, and account status for all users.</p>
        </div>
        <button onClick={() => setShowAdd(true)} style={{ display: 'flex', alignItems: 'center', gap: 7, background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 8, padding: '9px 16px', fontSize: 13, fontWeight: 600, cursor: 'pointer' }}>
          <UserPlus size={14} /> Add User
        </button>
      </div>

      {/* Stats tiles */}
      {!loading && (
        <div style={{ display: 'flex', gap: 10, marginBottom: 18, flexWrap: 'wrap' }}>
          <div style={tileStyle('#9BA1AC')}>
            <div style={{ fontSize: 22, fontWeight: 700, color: 'var(--txt)', fontFamily: '"Space Grotesk", sans-serif' }}>{total}</div>
            <div style={{ fontSize: 11, color: 'var(--txt-mut)', fontWeight: 600, textTransform: 'uppercase', letterSpacing: '.06em', marginTop: 2 }}>Total Users</div>
            <div style={{ fontSize: 10.5, color: 'var(--txt-dim)', marginTop: 6 }}>
              {Object.entries(roleCounts).map(([r, c]) => `${c} ${ROLE_LABEL[r] ?? r}`).join(' · ')}
            </div>
          </div>
          <div style={tileStyle('#2FB67C')}>
            <div style={{ fontSize: 22, fontWeight: 700, color: '#2FB67C', fontFamily: '"Space Grotesk", sans-serif' }}>{active}</div>
            <div style={{ fontSize: 11, color: 'var(--txt-mut)', fontWeight: 600, textTransform: 'uppercase', letterSpacing: '.06em', marginTop: 2 }}>Active</div>
            <div style={{ fontSize: 10.5, color: 'var(--txt-dim)', marginTop: 6 }}>{total > 0 ? Math.round((active / total) * 100) : 0}% of total</div>
          </div>
          <div style={tileStyle('#E4373D')}>
            <div style={{ fontSize: 22, fontWeight: 700, color: '#E4373D', fontFamily: '"Space Grotesk", sans-serif' }}>{inactive}</div>
            <div style={{ fontSize: 11, color: 'var(--txt-mut)', fontWeight: 600, textTransform: 'uppercase', letterSpacing: '.06em', marginTop: 2 }}>Inactive</div>
            <div style={{ fontSize: 10.5, color: 'var(--txt-dim)', marginTop: 6 }}>{total > 0 ? Math.round((inactive / total) * 100) : 0}% of total</div>
          </div>
        </div>
      )}

      {/* Search + Filters */}
      <div style={{ display: 'flex', gap: 8, marginBottom: 14, flexWrap: 'wrap', alignItems: 'center' }}>
        <input
          placeholder="Search name, email, or employee ID…"
          value={search}
          onChange={e => setSearch(e.target.value)}
          style={{ flex: '1 1 240px', minWidth: 200, background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, padding: '7px 12px', color: 'var(--txt)', fontSize: 13, outline: 'none' }}
        />
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
          {roles.map(r => <option key={r} value={r}>{ROLE_LABEL[r] ?? r}</option>)}
        </select>
        {(search || statusFilter !== 'ALL' || deptFilter || roleFilter) && (
          <button onClick={() => { setSearch(''); setStatusFilter('ALL'); setDeptFilter(''); setRoleFilter(''); }}
            style={{ background: 'none', border: '1px solid var(--line2)', borderRadius: 6, padding: '6px 10px', fontSize: 12, color: 'var(--txt-mut)', cursor: 'pointer' }}>
            Clear
          </button>
        )}
      </div>

      {/* Table */}
      <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, overflow: 'hidden' }}>
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
                    {['Employee ID', 'Name', 'Email', 'Role', 'Department', 'Manager', 'Status', ''].map(h => (
                      <th key={h} style={thStyle}>{h}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {paginated.map(u => (
                    <tr key={u.userId} style={{ opacity: u.active ? 1 : 0.6 }}
                      onMouseEnter={e => (e.currentTarget as HTMLTableRowElement).style.background = 'var(--raised)'}
                      onMouseLeave={e => (e.currentTarget as HTMLTableRowElement).style.background = 'transparent'}>
                      <td style={{ ...tdStyle, fontFamily: 'monospace', fontSize: 12 }}>{u.employeeCode}</td>
                      <td style={{ ...tdStyle, color: 'var(--txt)', fontWeight: 600 }}>{u.fullName}</td>
                      <td style={{ ...tdStyle, color: 'var(--txt)' }}>{u.email}</td>
                      <td style={tdStyle}><RoleBadge role={u.role} /></td>
                      <td style={tdStyle}>{u.departmentName ?? <span style={{ color: 'var(--txt-dim)' }}>—</span>}</td>
                      <td style={tdStyle}>{u.currentManager ? u.currentManager.fullName : <span style={{ color: 'var(--txt-dim)' }}>—</span>}</td>
                      <td style={tdStyle}><StatusBadge active={u.active} /></td>
                      <td style={{ ...tdStyle, padding: '8px 12px', width: 44 }}>
                        <KebabMenu items={[
                          { label: 'Edit', onClick: () => setEditing(u) },
                          { label: 'Reset Password', onClick: () => setResetting(u) },
                          { label: u.active ? 'Deactivate' : 'Reactivate', onClick: () => setToggling(u) },
                          { label: 'Delete', onClick: () => setDeleting(u), danger: true, dividerBefore: true },
                        ]} />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            {/* Pagination */}
            {totalPages > 1 && (
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '10px 16px', borderTop: '1px solid var(--line)' }}>
                <span style={{ fontSize: 12, color: 'var(--txt-dim)' }}>
                  {filtered.length} result{filtered.length !== 1 ? 's' : ''} · page {page} of {totalPages}
                </span>
                <div style={{ display: 'flex', gap: 6 }}>
                  <button onClick={() => setPage(p => Math.max(1, p - 1))} disabled={page === 1}
                    style={{ background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 5, padding: '5px 12px', fontSize: 12, color: page === 1 ? 'var(--txt-dim)' : 'var(--txt-mut)', cursor: page === 1 ? 'not-allowed' : 'pointer' }}>
                    ← Prev
                  </button>
                  {Array.from({ length: Math.min(5, totalPages) }, (_, i) => {
                    const start = Math.max(1, Math.min(page - 2, totalPages - 4));
                    const p = start + i;
                    return p <= totalPages ? (
                      <button key={p} onClick={() => setPage(p)}
                        style={{ background: p === page ? 'var(--brand)' : 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 5, padding: '5px 10px', fontSize: 12, color: p === page ? '#fff' : 'var(--txt-mut)', cursor: 'pointer', minWidth: 32 }}>
                        {p}
                      </button>
                    ) : null;
                  })}
                  <button onClick={() => setPage(p => Math.min(totalPages, p + 1))} disabled={page === totalPages}
                    style={{ background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 5, padding: '5px 12px', fontSize: 12, color: page === totalPages ? 'var(--txt-dim)' : 'var(--txt-mut)', cursor: page === totalPages ? 'not-allowed' : 'pointer' }}>
                    Next →
                  </button>
                </div>
              </div>
            )}
          </>
        )}
      </div>

      {showAdd && <AddModal token={token} onClose={() => setShowAdd(false)} onCreated={u => setUsers(prev => [u, ...prev])} />}
      {editing && <EditModal user={editing} token={token} onClose={() => setEditing(null)} onUpdated={updated => setUsers(prev => prev.map(u => u.userId === updated.userId ? updated : u))} />}
      {resetting && <ResetPasswordModal user={resetting} token={token} onClose={() => setResetting(null)} />}
      {toggling && <StatusModal user={toggling} token={token} onClose={() => setToggling(null)} onUpdated={updated => setUsers(prev => prev.map(u => u.userId === updated.userId ? updated : u))} />}
      {deleting && <DeleteModal user={deleting} token={token} onClose={() => setDeleting(null)} onDeleted={userId => setUsers(prev => prev.filter(u => u.userId !== userId))} />}
    </div>
  );
}
