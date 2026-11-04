import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { KeyRound, Plus, Search as SearchIcon, X, ChevronDown, ChevronRight, Lock, ShieldCheck, Users2, Layers } from 'lucide-react';
import PageTitleBlock from '../components/PageTitleBlock';
import DashTile from '../components/DashTile';
import { ChipGroup } from '../components/TabBar';
import { useAuthStore } from '../store/authStore';
import { useToast } from '../context/ToastContext';
import { hasPermission } from '../lib/permissions';
import { rolesApi, type CatalogueModule, type RoleDetail, type RoleSummary } from '../api/roles';
import { usersApi, type EmployeeRecord } from '../api/employees';
import { useBackToClose } from '../lib/backToClose';
import {
  ACTION_LABEL, SCOPE_LABEL, categoryState, filterCatalogue, fromGrantMap, grantKey, grantableScopes, sameGrants,
  setCategory, setGrant, summarizeGrants, toGrantMap, toGrants, type GrantMap,
} from '../lib/rolePermissions';

/*
 * Roles & Permissions (Super Admin → Administration). Every rule shown here — system-role
 * protection, the Super Admin lock, "only grant what you hold", last Super Admin, no self-change —
 * is enforced by the server (RoleAdminService / UserManagementService); this page mirrors those
 * rules so the admin sees them up front, and always reloads from the server after a save.
 */

// ─── Shared styles (same tokens/look as User Management) ────────────────────────
const overlayStyle: React.CSSProperties = { position: 'fixed', inset: 0, background: 'rgba(15,17,23,.6)', backdropFilter: 'blur(2px)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 500, padding: 16 };
const inputStyle: React.CSSProperties = { width: '100%', background: 'var(--shell)', border: '1px solid var(--line2)', borderRadius: 9, padding: '10px 12px', color: 'var(--txt)', fontSize: 13, boxSizing: 'border-box', outline: 'none' };
const labelStyle: React.CSSProperties = { display: 'block', fontSize: 11, fontWeight: 700, color: 'var(--txt-mut)', marginBottom: 6, textTransform: 'uppercase', letterSpacing: '.05em' };
const primaryBtn: React.CSSProperties = { display: 'inline-flex', alignItems: 'center', gap: 6, background: 'var(--brand)', color: '#fff', border: 'none', borderRadius: 8, padding: '9px 16px', fontSize: 13, fontWeight: 600, cursor: 'pointer' };
const secondaryBtn: React.CSSProperties = { background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px solid var(--line2)', borderRadius: 8, padding: '9px 16px', fontSize: 13, fontWeight: 600, cursor: 'pointer' };
const dangerBtn: React.CSSProperties = { ...secondaryBtn, color: 'var(--risk)', borderColor: 'color-mix(in srgb, var(--risk) 40%, var(--line2))' };
const thStyle: React.CSSProperties = { padding: '12px 16px', textAlign: 'left', fontSize: 11, fontWeight: 700, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.06em', borderBottom: '1px solid var(--line)', whiteSpace: 'nowrap', background: 'var(--raised)' };
const tdStyle: React.CSSProperties = { padding: '12px 16px', fontSize: 13, color: 'var(--txt-mut)', borderBottom: '1px solid var(--line)', verticalAlign: 'middle' };

function isSuperAdmin(mine: { roles?: string[] } | null | undefined): boolean {
  return !!mine?.roles?.includes('SUPER_ADMIN');
}

function fmtDate(iso: string | null | undefined): string {
  return iso ? new Date(iso).toLocaleDateString('en-US', { month: 'short', day: 'numeric', year: 'numeric' }) : '–';
}

export function RoleTypeBadge({ system }: { system: boolean }) {
  const c = system ? '#4C8DD6' : '#8B5CF6';
  return <span style={{ fontSize: 10.5, fontWeight: 700, letterSpacing: '.03em', color: c, background: `${c}1A`, borderRadius: 6, padding: '3px 8px', whiteSpace: 'nowrap' }}>{system ? 'SYSTEM' : 'CUSTOM'}</span>;
}

function StatusPill({ active }: { active: boolean }) {
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5, fontSize: 11, fontWeight: 700, color: active ? '#2FB67C' : '#9BA1AC', background: active ? 'rgba(47,182,124,.1)' : 'rgba(155,161,172,.14)', borderRadius: 20, padding: '3px 10px', whiteSpace: 'nowrap' }}>
      <span style={{ width: 5, height: 5, borderRadius: '50%', background: active ? '#2FB67C' : '#9BA1AC' }} />
      {active ? 'Active' : 'Inactive'}
    </span>
  );
}

/** Native checkbox with a real `indeterminate` state (React has no prop for it). */
function TriCheckbox({ state, disabled, onChange, label }: { state: 'none' | 'some' | 'all'; disabled?: boolean; onChange: (on: boolean) => void; label: string }) {
  const ref = useRef<HTMLInputElement>(null);
  useEffect(() => { if (ref.current) ref.current.indeterminate = state === 'some'; }, [state]);
  return (
    <input ref={ref} type="checkbox" aria-label={label} checked={state === 'all'} disabled={disabled}
      onChange={() => onChange(state !== 'all')} onClick={e => e.stopPropagation()}
      style={{ width: 15, height: 15, accentColor: 'var(--brand)', cursor: disabled ? 'not-allowed' : 'pointer', flexShrink: 0 }} />
  );
}

function ModalHeader({ title, subtitle, onClose }: { title: string; subtitle?: string; onClose: () => void }) {
  return (
    <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 12, padding: '18px 22px', borderBottom: '1px solid var(--line)' }}>
      <div style={{ minWidth: 0 }}>
        <div style={{ fontFamily: '"Space Grotesk", sans-serif', fontWeight: 700, fontSize: 16, color: 'var(--txt)' }}>{title}</div>
        {subtitle && <div style={{ fontSize: 12, color: 'var(--txt-mut)', marginTop: 2 }}>{subtitle}</div>}
      </div>
      <button onClick={onClose} aria-label="Close" style={{ background: 'var(--raised2)', border: 'none', cursor: 'pointer', color: 'var(--txt-mut)', padding: 6, borderRadius: 8, display: 'flex' }}><X size={16} /></button>
    </div>
  );
}

function Banner({ tone, children }: { tone: 'info' | 'warn' | 'error'; children: React.ReactNode }) {
  const c = tone === 'error' ? 'var(--risk)' : tone === 'warn' ? '#E0A93B' : 'var(--info, #4C8DD6)';
  return <div role={tone === 'error' ? 'alert' : undefined} style={{ fontSize: 12.5, color: 'var(--txt)', background: `color-mix(in srgb, ${c} 10%, transparent)`, border: `1px solid color-mix(in srgb, ${c} 35%, transparent)`, borderRadius: 9, padding: '9px 12px', lineHeight: 1.5 }}>{children}</div>;
}

/** Escape + backdrop close for a modal, routed through `requestClose` so dirty state can intercept it. */
function useDismiss(requestClose: () => void) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') requestClose(); };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [requestClose]);
}

// ─── Permission matrix (shared by the editor and the read-only details view) ───
function PermissionMatrix({ catalogue, map, onChange, readOnly, lockedReason }: {
  catalogue: CatalogueModule[];
  map: GrantMap;
  onChange?: (next: GrantMap) => void;
  readOnly: boolean;
  lockedReason?: string;
}) {
  const mine = useAuthStore(s => s.permissions);
  const [query, setQuery] = useState('');
  const [open, setOpen] = useState<Set<string>>(() => new Set());
  const visible = filterCatalogue(catalogue, query);
  // While searching, every match is expanded so the hit is actually visible.
  const isOpen = (code: string) => !!query.trim() || open.has(code);
  const toggleOpen = (code: string) => setOpen(prev => { const n = new Set(prev); if (n.has(code)) n.delete(code); else n.add(code); return n; });
  const widest = (module: string, action: string) => grantableScopes(mine, module, action).at(-1);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center' }}>
        <div style={{ position: 'relative', flex: '1 1 220px' }}>
          <SearchIcon size={14} style={{ position: 'absolute', left: 11, top: '50%', transform: 'translateY(-50%)', color: 'var(--txt-dim)', pointerEvents: 'none' }} />
          <input value={query} onChange={e => setQuery(e.target.value)} placeholder="Search permissions…" aria-label="Search permissions"
            style={{ ...inputStyle, padding: '8px 12px 8px 32px', background: 'var(--raised)' }} />
        </div>
        <button type="button" style={{ ...secondaryBtn, padding: '7px 12px', fontSize: 12 }} onClick={() => setOpen(new Set(catalogue.map(m => m.code)))}>Expand all</button>
        <button type="button" style={{ ...secondaryBtn, padding: '7px 12px', fontSize: 12 }} onClick={() => setOpen(new Set())}>Collapse all</button>
      </div>
      {lockedReason && <Banner tone="info"><Lock size={12} style={{ verticalAlign: -1, marginRight: 6 }} />{lockedReason}</Banner>}
      <div style={{ border: '1px solid var(--line)', borderRadius: 10, overflow: 'hidden' }}>
        {visible.length === 0 && <div style={{ padding: 20, textAlign: 'center', fontSize: 13, color: 'var(--txt-dim)' }}>No permissions match “{query}”.</div>}
        {visible.map((m, i) => {
          const editable = !readOnly && m.configurable;
          // Bulk controls only ever touch actions this admin can actually grant (see setCategory).
          const grantable = editable ? m.actions.filter(a => widest(m.code, a)) : [];
          const granted = m.actions.filter(a => map[grantKey(m.code, a)]);
          const state = categoryState(map, m.code, grantable.length ? grantable : m.actions);
          return (
            <div key={m.code} style={{ borderTop: i ? '1px solid var(--line)' : 'none' }}>
              <div role="button" tabIndex={0} aria-expanded={isOpen(m.code)}
                onClick={() => toggleOpen(m.code)} onKeyDown={e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggleOpen(m.code); } }}
                style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '11px 14px', cursor: 'pointer', background: 'var(--raised)' }}>
                {isOpen(m.code) ? <ChevronDown size={15} color="var(--txt-dim)" /> : <ChevronRight size={15} color="var(--txt-dim)" />}
                {editable && (
                  <TriCheckbox state={state} disabled={grantable.length === 0} label={`Select all ${m.name}`}
                    onChange={on => onChange?.(setCategory(map, m.code, grantable, on, a => widest(m.code, a)))} />
                )}
                <span style={{ fontSize: 13, fontWeight: 600, color: 'var(--txt)', flex: 1, minWidth: 0 }}>{m.name}</span>
                {!m.configurable && <span title="Access here is still decided by built-in role logic, so it can't be configured yet." style={{ fontSize: 10.5, fontWeight: 700, color: 'var(--txt-dim)', display: 'inline-flex', alignItems: 'center', gap: 4 }}><Lock size={11} /> SYSTEM-MANAGED</span>}
                <span style={{ fontSize: 11.5, color: 'var(--txt-dim)', whiteSpace: 'nowrap' }}>{granted.length}/{m.actions.length}</span>
              </div>
              {isOpen(m.code) && (
                <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(210px, 1fr))', gap: 8, padding: '10px 14px 14px 40px' }}>
                  {m.actions.map(a => {
                    const k = grantKey(m.code, a);
                    const scope = map[k];
                    const scopes = grantableScopes(mine, m.code, a);
                    const canToggle = editable && scopes.length > 0;
                    // A scope the admin can't grant (or a legacy DEPARTMENT/LOCATION row) is still shown as-is.
                    const options = scope && !scopes.includes(scope) ? [...scopes, scope] : scopes;
                    return (
                      <div key={a} style={{ display: 'flex', alignItems: 'center', gap: 8, minHeight: 30 }}>
                        <label style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 13, color: scope ? 'var(--txt)' : 'var(--txt-mut)', cursor: canToggle ? 'pointer' : 'default', flex: 1, minWidth: 0 }}
                          title={editable && !scopes.length ? "You can't grant a permission you don't hold yourself." : undefined}>
                          <input type="checkbox" checked={!!scope} disabled={!canToggle}
                            onChange={e => onChange?.(setGrant(map, m.code, a, e.target.checked ? scopes.at(-1)! : null))}
                            style={{ width: 15, height: 15, accentColor: 'var(--brand)', cursor: canToggle ? 'pointer' : 'not-allowed' }} />
                          {ACTION_LABEL[a] ?? a}
                        </label>
                        {scope && (canToggle && options.length > 1 ? (
                          <select aria-label={`${m.name} ${ACTION_LABEL[a] ?? a} scope`} value={scope}
                            onChange={e => onChange?.(setGrant(map, m.code, a, e.target.value))}
                            style={{ background: 'var(--shell)', border: '1px solid var(--line2)', borderRadius: 6, padding: '3px 6px', fontSize: 11.5, color: 'var(--txt)' }}>
                            {options.map(s => <option key={s} value={s} disabled={!scopes.includes(s)}>{SCOPE_LABEL[s] ?? s}</option>)}
                          </select>
                        ) : <span style={{ fontSize: 11.5, color: 'var(--txt-dim)' }}>{SCOPE_LABEL[scope] ?? scope}</span>)}
                      </div>
                    );
                  })}
                </div>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}

// ─── Create / edit role ─────────────────────────────────────────────────────────
function RoleEditorModal({ token, catalogue, existing, onClose, onSaved }: {
  token: string;
  catalogue: CatalogueModule[];
  /** null = create. */
  existing: RoleDetail | null;
  onClose: () => void;
  onSaved: (saved: RoleDetail) => void;
}) {
  const { showToast } = useToast();
  const mine = useAuthStore(s => s.permissions);
  const role = existing?.role;
  const initialMap = useMemo(() => toGrantMap(existing?.grants ?? []), [existing]);
  const [name, setName] = useState(role?.name ?? '');
  const [description, setDescription] = useState(role?.description ?? '');
  const [map, setMap] = useState<GrantMap>(initialMap);
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);
  const [confirmDiscard, setConfirmDiscard] = useState(false);
  const [warnings, setWarnings] = useState<string[] | null>(null);

  const metadataEditable = !role || role.metadataEditable;
  const canConfigure = hasPermission(mine, 'roles', 'CONFIGURE', 'ALL');
  // Only a Super Admin may change a system role's permissions (enforced in RoleAdminService).
  const systemLocked = !!role?.system && !isSuperAdmin(mine);
  const permissionsEditable = (!role || role.permissionsEditable) && canConfigure && !systemLocked;
  const metaDirty = name.trim() !== (role?.name ?? '') || (description.trim() || '') !== (role?.description ?? '');
  const grantsDirty = !sameGrants(map, initialMap);
  const dirty = (metadataEditable && metaDirty) || (permissionsEditable && grantsDirty);

  // Closing the tab/window with unsaved changes — the overlay already blocks in-app navigation.
  useEffect(() => {
    if (!dirty) return;
    const h = (e: BeforeUnloadEvent) => { e.preventDefault(); };
    window.addEventListener('beforeunload', h);
    return () => window.removeEventListener('beforeunload', h);
  }, [dirty]);

  const requestClose = useCallback(() => { if (dirty && !saving) setConfirmDiscard(true); else onClose(); }, [dirty, saving, onClose]);
  useDismiss(requestClose);
  // Browser Back goes through the same unsaved-changes prompt instead of leaving the page.
  useBackToClose(requestClose);

  /** Changes worth an explicit second look before they hit every holder of the role. */
  function sensitiveChanges(): string[] {
    const out: string[] = [];
    const added = Object.keys(map).filter(k => !initialMap[k]);
    const removed = Object.keys(initialMap).filter(k => !map[k]);
    if (role?.code === 'EMPLOYEE' && added.length)
      out.push('Every user holds the Employee role — these permissions will apply to everyone in the organization.');
    const adminModules = ['roles', 'user_roles', 'user_management'];
    if (removed.some(k => adminModules.includes(k.split(':')[0])))
      out.push(`Removing administration permissions takes access away from the ${role?.userCount ?? 0} user(s) holding this role on their next action.`);
    if (added.some(k => adminModules.includes(k.split(':')[0])))
      out.push('This grants administration permissions (users, roles, or role assignment).');
    return out;
  }

  async function save(confirmed: boolean) {
    setError('');
    const trimmed = name.trim();
    if (metadataEditable && (trimmed.length < 2 || trimmed.length > 50)) { setError('Role name must be 2–50 characters.'); return; }
    if (description.length > 255) { setError('Description must be 255 characters or fewer.'); return; }
    if (!confirmed && permissionsEditable && grantsDirty) {
      const w = sensitiveChanges();
      if (w.length) { setWarnings(w); return; }
    }
    setWarnings(null);
    setSaving(true);
    try {
      let saved: RoleDetail;
      if (!role) {
        saved = await rolesApi.create({ name: trimmed, description: description.trim() || undefined, grants: permissionsEditable ? toGrants(map, catalogue) : [] }, token);
      } else {
        saved = existing!;
        // Each save bumps the role's version, so the second call sends the version the first returned.
        if (metadataEditable && metaDirty) saved = await rolesApi.update(role.id, { name: trimmed, description: description.trim() || undefined, version: saved.role.version }, token);
        if (permissionsEditable && grantsDirty) saved = await rolesApi.setPermissions(role.id, toGrants(map, catalogue), saved.role.version, token);
      }
      showToast('success', role ? `${saved.role.name} updated` : `${saved.role.name} created`);
      onSaved(saved);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not save the role.');
    } finally {
      setSaving(false);
    }
  }

  const lockedReason = role?.code === 'SUPER_ADMIN'
    ? 'Super Admin is the protected all-access role. Its permissions cannot be changed.'
    : !canConfigure ? "You don't have permission to change role permissions."
    : systemLocked ? 'Only a Super Admin can change the permissions of a system role.' : undefined;

  return (
    <div style={overlayStyle} onMouseDown={e => { if (e.target === e.currentTarget) requestClose(); }}>
      <div role="dialog" aria-modal="true" aria-label={role ? `Edit ${role.name}` : 'Create role'}
        style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 16, width: '96vw', maxWidth: 880, maxHeight: '92vh', display: 'flex', flexDirection: 'column', boxShadow: '0 24px 64px rgba(0,0,0,.4)' }}>
        <ModalHeader title={role ? `Edit role · ${role.name}` : 'Create role'}
          subtitle={role?.system ? 'System role — name and status are fixed.' : 'Choose what holders of this role can do.'} onClose={requestClose} />
        <div style={{ padding: '18px 22px', overflowY: 'auto', display: 'flex', flexDirection: 'column', gap: 16 }}>
          <div className="nf-grid-2col-collapse" style={{ display: 'grid', gridTemplateColumns: '1fr 1.4fr', gap: 14 }}>
            <div>
              <label style={labelStyle} htmlFor="role-name">Role name *</label>
              <input id="role-name" style={{ ...inputStyle, opacity: metadataEditable ? 1 : 0.6 }} value={name} maxLength={50}
                disabled={!metadataEditable} onChange={e => setName(e.target.value)} placeholder="e.g. Payroll Viewer" autoFocus={!role} />
            </div>
            <div>
              <label style={labelStyle} htmlFor="role-desc">Description</label>
              <input id="role-desc" style={{ ...inputStyle, opacity: metadataEditable ? 1 : 0.6 }} value={description} maxLength={255}
                disabled={!metadataEditable} onChange={e => setDescription(e.target.value)} placeholder="What this role is for" />
            </div>
          </div>
          <div>
            <div style={{ ...labelStyle, display: 'flex', justifyContent: 'space-between' }}>
              <span>Permissions</span>
              <span style={{ textTransform: 'none', letterSpacing: 0, fontWeight: 500 }}>{summarizeGrants(fromGrantMap(map))}</span>
            </div>
            <PermissionMatrix catalogue={catalogue} map={map} onChange={setMap} readOnly={!permissionsEditable} lockedReason={lockedReason} />
            <div style={{ fontSize: 11.5, color: 'var(--txt-dim)', marginTop: 8, lineHeight: 1.5 }}>
              You can only grant permissions you hold yourself. <b>Organization</b> scope covers everyone; <b>Team</b> covers the holder's direct reports; <b>Own</b> covers only their own records.
            </div>
          </div>
          {warnings && (
            <Banner tone="warn">
              <div style={{ fontWeight: 700, marginBottom: 4 }}>Please confirm these changes</div>
              <ul style={{ margin: 0, paddingLeft: 18 }}>{warnings.map(w => <li key={w}>{w}</li>)}</ul>
            </Banner>
          )}
          {confirmDiscard && <Banner tone="warn">You have unsaved changes. Discard them?</Banner>}
          {error && <Banner tone="error">{error}</Banner>}
        </div>
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10, padding: '14px 22px', borderTop: '1px solid var(--line)', flexWrap: 'wrap' }}>
          {confirmDiscard ? (
            <>
              <button style={secondaryBtn} onClick={() => setConfirmDiscard(false)}>Keep editing</button>
              <button style={dangerBtn} onClick={onClose}>Discard changes</button>
            </>
          ) : warnings ? (
            <>
              <button style={secondaryBtn} onClick={() => setWarnings(null)}>Back</button>
              <button style={primaryBtn} disabled={saving} onClick={() => save(true)}>{saving ? 'Saving…' : 'Confirm & save'}</button>
            </>
          ) : (
            <>
              <button style={secondaryBtn} onClick={requestClose}>Cancel</button>
              <button style={{ ...primaryBtn, opacity: saving || (role && !dirty) ? 0.6 : 1 }} disabled={saving || (!!role && !dirty)} onClick={() => save(false)}>
                {saving ? 'Saving…' : role ? 'Save changes' : 'Create role'}
              </button>
            </>
          )}
        </div>
      </div>
    </div>
  );
}

// ─── Delete custom role ─────────────────────────────────────────────────────────
/** Confirm-and-delete for a custom role. A role still held by users can only be deleted by
 *  explicitly opting into removing it from them too (server: DELETE ?removeFromUsers=true — each
 *  holder goes through the normal role-assignment guards and is signed out). */
function DeleteRoleDialog({ token, role, onClose, onDeleted }: {
  token: string;
  role: RoleSummary;
  onClose: () => void;
  onDeleted: () => void;
}) {
  const { showToast } = useToast();
  const [removeFromUsers, setRemoveFromUsers] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const assigned = role.userCount > 0;
  const requestClose = useCallback(() => { if (!busy) onClose(); }, [busy, onClose]);
  useDismiss(requestClose);

  async function confirm() {
    setBusy(true); setError('');
    try {
      await rolesApi.remove(role.id, token, assigned && removeFromUsers);
      showToast('success', `${role.name} deleted`);
      onDeleted();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not delete the role.');
      setBusy(false);
    }
  }

  return createPortal(
    <div style={overlayStyle} onMouseDown={e => { if (e.target === e.currentTarget) requestClose(); }}>
      <div role="alertdialog" aria-modal="true" aria-label={`Delete ${role.name}`}
        style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 16, width: '94vw', maxWidth: 460, boxShadow: '0 24px 64px rgba(0,0,0,.4)' }}>
        <ModalHeader title={`Delete ${role.name}?`} subtitle="This permanently deletes the role and its permissions. It can't be undone." onClose={requestClose} />
        <div style={{ padding: '16px 22px', display: 'flex', flexDirection: 'column', gap: 12 }}>
          {assigned ? (
            <>
              <Banner tone="warn">
                This role is assigned to <b>{role.userCount}</b> user{role.userCount === 1 ? '' : 's'}. If you only want to stop it granting access, deactivate it instead.
              </Banner>
              <label style={{ display: 'flex', alignItems: 'flex-start', gap: 9, fontSize: 13, color: 'var(--txt)', cursor: 'pointer', lineHeight: 1.45 }}>
                <input type="checkbox" checked={removeFromUsers} onChange={e => setRemoveFromUsers(e.target.checked)}
                  style={{ width: 15, height: 15, marginTop: 2, accentColor: 'var(--brand)' }} />
                <span>Remove it from {role.userCount === 1 ? 'that user' : `all ${role.userCount} users`} and delete it. They lose its permissions immediately and will be asked to sign in again.</span>
              </label>
            </>
          ) : (
            <div style={{ fontSize: 13, color: 'var(--txt-mut)' }}>No users hold this role.</div>
          )}
          {error && <Banner tone="error">{error}</Banner>}
        </div>
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10, padding: '14px 22px', borderTop: '1px solid var(--line)' }}>
          <button style={secondaryBtn} onClick={requestClose} disabled={busy}>Cancel</button>
          <button style={{ ...dangerBtn, opacity: busy || (assigned && !removeFromUsers) ? 0.6 : 1 }}
            disabled={busy || (assigned && !removeFromUsers)} onClick={confirm}>
            {busy ? 'Deleting…' : 'Delete role'}
          </button>
        </div>
      </div>
    </div>,
    document.body,
  );
}

// ─── Role details drawer ────────────────────────────────────────────────────────
function RoleDetailsDrawer({ token, roleId, catalogue, onClose, onEdit, onChanged }: {
  token: string;
  roleId: number;
  catalogue: CatalogueModule[];
  onClose: () => void;
  onEdit: (detail: RoleDetail) => void;
  onChanged: () => void;
}) {
  const { showToast } = useToast();
  const mine = useAuthStore(s => s.permissions);
  const myEmail = useAuthStore(s => s.user?.email)?.toLowerCase();
  const [detail, setDetail] = useState<RoleDetail | null>(null);
  const [loadError, setLoadError] = useState('');
  const [tab, setTab] = useState<'permissions' | 'members'>('permissions');
  const [users, setUsers] = useState<EmployeeRecord[] | null>(null);
  const [pick, setPick] = useState('');
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState('');
  const [confirm, setConfirm] = useState<null | { label: string; run: () => Promise<void> }>(null);
  const [deleting, setDeleting] = useState(false);

  const canAssign = hasPermission(mine, 'user_roles', 'EDIT', 'ALL');
  const canListUsers = hasPermission(mine, 'user_management', 'VIEW', 'ALL');
  const canEditMeta = hasPermission(mine, 'roles', 'EDIT', 'ALL');
  const canDelete = hasPermission(mine, 'roles', 'DELETE', 'ALL');

  const load = useCallback(() => {
    setLoadError('');
    rolesApi.get(roleId, token).then(setDetail).catch(e => setLoadError(e.message));
  }, [roleId, token]);
  useEffect(load, [load]);
  useBackToClose(onClose);
  // While the delete dialog is open, Escape/backdrop belong to it, not to this drawer.
  const dismissDrawer = useCallback(() => { if (!deleting) onClose(); }, [deleting, onClose]);
  useEffect(() => {
    if (tab === 'members' && canAssign && canListUsers && users === null) usersApi.list(token).then(setUsers).catch(() => setUsers([]));
  }, [tab, canAssign, canListUsers, users, token]);
  useDismiss(dismissDrawer);

  async function run(label: string, fn: () => Promise<RoleDetail | void>, success: string) {
    setBusy(true); setActionError('');
    try {
      const result = await fn();
      if (result) setDetail(result);
      showToast('success', success);
      onChanged();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : `Could not ${label}.`);
    } finally {
      setBusy(false); setConfirm(null);
    }
  }

  const role = detail?.role;
  const memberIds = new Set(detail?.members.map(m => m.userId));
  const candidates = (users ?? []).filter(u => !memberIds.has(u.userId) && u.active && u.email.toLowerCase() !== myEmail);
  const grantedModules = catalogue.filter(m => detail?.grants.some(g => g.module === m.code));

  return createPortal(
    <div style={{ ...overlayStyle, justifyContent: 'flex-end', padding: 0 }} onMouseDown={e => { if (e.target === e.currentTarget) onClose(); }}>
      <aside role="dialog" aria-modal="true" aria-label={role ? `${role.name} details` : 'Role details'}
        style={{ background: 'var(--panel)', borderLeft: '1px solid var(--line)', width: 'min(600px, 100vw)', height: '100%', display: 'flex', flexDirection: 'column', boxShadow: '-16px 0 48px rgba(0,0,0,.3)' }}>
        <ModalHeader title={role?.name ?? 'Role'} subtitle={role?.description ?? undefined} onClose={onClose} />
        {loadError && <div style={{ padding: 22 }}><Banner tone="error">{loadError}</Banner></div>}
        {!detail && !loadError && <div style={{ padding: 40, textAlign: 'center', color: 'var(--txt-dim)' }}>Loading…</div>}
        {detail && role && (
          <>
            <div style={{ padding: '14px 22px', display: 'flex', gap: 10, flexWrap: 'wrap', alignItems: 'center', borderBottom: '1px solid var(--line)' }}>
              <RoleTypeBadge system={role.system} />
              <StatusPill active={role.active} />
              <span style={{ fontSize: 12, color: 'var(--txt-dim)' }}>Code <code>{role.code}</code> · Created {fmtDate(role.createdAt)} · Updated {fmtDate(role.updatedAt)}</span>
              <div style={{ flex: 1 }} />
              <button style={{ ...secondaryBtn, padding: '7px 12px', fontSize: 12 }} onClick={() => onEdit(detail)}>{role.permissionsEditable || role.metadataEditable ? 'Edit' : 'View permissions'}</button>
              {role.metadataEditable && canEditMeta && (
                <button style={{ ...secondaryBtn, padding: '7px 12px', fontSize: 12 }} disabled={busy}
                  onClick={() => role.active
                    ? setConfirm({ label: `Deactivate ${role.name}? Its ${role.userCount} holder(s) keep the assignment but lose its permissions immediately.`, run: () => run('deactivate', () => rolesApi.setStatus(role.id, false, role.version, token), `${role.name} deactivated`) })
                    : run('activate', () => rolesApi.setStatus(role.id, true, role.version, token), `${role.name} activated`)}>
                  {role.active ? 'Deactivate' : 'Activate'}
                </button>
              )}
              {role.metadataEditable && canDelete && (
                <button style={{ ...dangerBtn, padding: '7px 12px', fontSize: 12 }} disabled={busy} onClick={() => setDeleting(true)}>
                  Delete
                </button>
              )}
            </div>
            {deleting && (
              <DeleteRoleDialog token={token} role={role} onClose={() => setDeleting(false)}
                onDeleted={() => { setDeleting(false); onChanged(); onClose(); }} />
            )}
            {confirm && (
              <div style={{ padding: '12px 22px 0' }}>
                <Banner tone="warn">
                  <div style={{ marginBottom: 8 }}>{confirm.label}</div>
                  <div style={{ display: 'flex', gap: 8 }}>
                    <button style={{ ...secondaryBtn, padding: '6px 12px', fontSize: 12 }} onClick={() => setConfirm(null)}>Cancel</button>
                    <button style={{ ...dangerBtn, padding: '6px 12px', fontSize: 12 }} disabled={busy} onClick={confirm.run}>Confirm</button>
                  </div>
                </Banner>
              </div>
            )}
            {actionError && <div style={{ padding: '12px 22px 0' }}><Banner tone="error">{actionError}</Banner></div>}
            <div style={{ display: 'flex', gap: 4, padding: '12px 22px 0' }}>
              <ChipGroup ariaLabel="Role details section" value={tab} onChange={setTab}
                options={[{ key: 'permissions', label: `Permissions (${detail.grants.length})` }, { key: 'members', label: `Users (${detail.members.length})` }]} />
            </div>
            <div style={{ padding: '14px 22px 22px', overflowY: 'auto', flex: 1 }}>
              {tab === 'permissions' && (
                grantedModules.length === 0
                  ? <div style={{ fontSize: 13, color: 'var(--txt-dim)' }}>{role.code === 'EMPLOYEE' ? 'No extra permissions — Employee self-service (attendance, leave, requests…) is available to every signed-in user.' : 'This role grants no permissions yet.'}</div>
                  : <PermissionMatrix catalogue={grantedModules} map={toGrantMap(detail.grants)} readOnly />
              )}
              {tab === 'members' && (
                <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
                  {canAssign && canListUsers && role.active && (
                    <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                      <select aria-label="Add a user to this role" value={pick} onChange={e => setPick(e.target.value)} style={{ ...inputStyle, flex: '1 1 240px', width: 'auto' }}>
                        <option value="">{users === null ? 'Loading users…' : 'Add a user…'}</option>
                        {candidates.map(u => <option key={u.userId} value={u.userId}>{u.fullName} · {u.email}</option>)}
                      </select>
                      <button style={{ ...primaryBtn, opacity: !pick || busy ? 0.6 : 1 }} disabled={!pick || busy}
                        onClick={() => run('add user', () => rolesApi.addMember(role.id, pick, token), 'User added — they will be asked to sign in again').then(() => setPick(''))}>
                        <Plus size={14} /> Add
                      </button>
                    </div>
                  )}
                  {!role.active && <Banner tone="info">Inactive roles can't be assigned. Activate the role to add users.</Banner>}
                  {detail.members.length === 0
                    ? <div style={{ fontSize: 13, color: 'var(--txt-dim)' }}>No users hold this role.</div>
                    : (
                      <div style={{ border: '1px solid var(--line)', borderRadius: 10, overflow: 'hidden' }}>
                        {detail.members.map((m, i) => {
                          const self = m.email.toLowerCase() === myEmail;
                          const removable = canAssign && role.code !== 'EMPLOYEE' && !self;
                          return (
                            <div key={m.userId} style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '10px 14px', borderTop: i ? '1px solid var(--line)' : 'none', opacity: m.active ? 1 : 0.6 }}>
                              <div style={{ flex: 1, minWidth: 0 }}>
                                <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--txt)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{m.fullName}{self && <span style={{ color: 'var(--txt-dim)', fontWeight: 500 }}> (you)</span>}</div>
                                <div style={{ fontSize: 11.5, color: 'var(--txt-dim)' }}>{m.employeeCode ? `${m.employeeCode} · ` : ''}{m.email}{!m.active && ' · Inactive'}</div>
                              </div>
                              {removable && (
                                <button style={{ ...dangerBtn, padding: '5px 10px', fontSize: 11.5 }} disabled={busy}
                                  onClick={() => setConfirm({ label: `Remove ${role.name} from ${m.fullName}? They lose its permissions and will be asked to sign in again.`, run: () => run('remove user', () => rolesApi.removeMember(role.id, m.userId, token), `${m.fullName} removed from ${role.name}`) })}>
                                  Remove
                                </button>
                              )}
                            </div>
                          );
                        })}
                      </div>
                    )}
                </div>
              )}
            </div>
          </>
        )}
      </aside>
    </div>,
    document.body,
  );
}

// ─── Page ───────────────────────────────────────────────────────────────────────
export default function RolesPermissionsPage() {
  const token = useAuthStore(s => s.token) ?? '';
  const mine = useAuthStore(s => s.permissions);
  const [roles, setRoles] = useState<RoleSummary[]>([]);
  const [catalogue, setCatalogue] = useState<CatalogueModule[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const [search, setSearch] = useState('');
  const [type, setType] = useState<'ALL' | 'SYSTEM' | 'CUSTOM'>('ALL');
  const [status, setStatus] = useState<'ALL' | 'ACTIVE' | 'INACTIVE'>('ALL');
  const [editing, setEditing] = useState<RoleDetail | null | undefined>(undefined); // undefined = closed, null = create
  const [viewing, setViewing] = useState<number | null>(null);
  const [deletingRole, setDeletingRole] = useState<RoleSummary | null>(null);

  const canCreate = hasPermission(mine, 'roles', 'CREATE', 'ALL');
  const canDelete = hasPermission(mine, 'roles', 'DELETE', 'ALL');

  const reload = useCallback(() => {
    setLoadError('');
    return Promise.all([rolesApi.list(token), rolesApi.catalogue(token)])
      .then(([r, c]) => { setRoles(r); setCatalogue(c); })
      .catch(e => setLoadError(e.message))
      .finally(() => setLoading(false));
  }, [token]);
  useEffect(() => { reload(); }, [reload]);

  const filtered = roles.filter(r => {
    const q = search.trim().toLowerCase();
    if (q && !r.name.toLowerCase().includes(q) && !(r.description ?? '').toLowerCase().includes(q)) return false;
    if (type !== 'ALL' && r.system !== (type === 'SYSTEM')) return false;
    if (status !== 'ALL' && r.active !== (status === 'ACTIVE')) return false;
    return true;
  });
  const customCount = roles.filter(r => !r.system).length;

  async function openEditor(roleId: number) {
    try { setEditing(await rolesApi.get(roleId, token)); } catch (e) { setLoadError(e instanceof Error ? e.message : 'Could not load the role.'); }
  }

  return (
    <div>
      <div className="nf-policy-header nf-page-hdr" style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 20, flexWrap: 'wrap', gap: 14 }}>
        <PageTitleBlock icon={KeyRound}>
          <h1 style={{ fontFamily: '"Space Grotesk", sans-serif', fontSize: 21, fontWeight: 700, color: 'var(--txt)', margin: 0 }}>Roles & Permissions</h1>
          <p style={{ fontSize: 13, color: 'var(--txt-mut)', marginTop: 3 }}>Define what each role can do, create custom roles, and see who holds them.</p>
        </PageTitleBlock>
        {canCreate && (
          <button style={primaryBtn} onClick={() => setEditing(null)}><Plus size={14} /> Create Role</button>
        )}
      </div>

      {!loading && !loadError && (
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: 14, marginBottom: 20 }}>
          <DashTile icon={<Layers size={20} />} accent="var(--info)" label="Roles" value={String(roles.length)} sub={`${roles.length - customCount} system · ${customCount} custom`} />
          <DashTile icon={<ShieldCheck size={20} />} accent="var(--ok)" label="Active Roles" value={String(roles.filter(r => r.active).length)} sub="Inactive roles grant nothing" />
          <DashTile icon={<Users2 size={20} />} accent="var(--brand)" label="Custom Role Assignments" value={String(roles.filter(r => !r.system).reduce((n, r) => n + r.userCount, 0))} sub="Across all custom roles" />
        </div>
      )}

      <div style={{ display: 'flex', gap: 10, marginBottom: 16, flexWrap: 'wrap', alignItems: 'center', background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, padding: 12 }}>
        <div style={{ position: 'relative', flex: '1 1 240px', minWidth: 200 }}>
          <SearchIcon size={14} style={{ position: 'absolute', left: 12, top: '50%', transform: 'translateY(-50%)', color: 'var(--txt-dim)', pointerEvents: 'none' }} />
          <input placeholder="Search roles…" aria-label="Search roles" value={search} onChange={e => setSearch(e.target.value)}
            style={{ ...inputStyle, background: 'var(--raised)', padding: '9px 12px 9px 34px' }} />
        </div>
        <ChipGroup ariaLabel="Role type" value={type} onChange={setType}
          options={[{ key: 'ALL', label: 'All' }, { key: 'SYSTEM', label: 'System' }, { key: 'CUSTOM', label: 'Custom' }]} />
        <select aria-label="Status" value={status} onChange={e => setStatus(e.target.value as typeof status)}
          style={{ background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 8, padding: '9px 12px', color: 'var(--txt-mut)', fontSize: 12.5, cursor: 'pointer' }}>
          <option value="ALL">All Status</option>
          <option value="ACTIVE">Active</option>
          <option value="INACTIVE">Inactive</option>
        </select>
      </div>

      <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 14, overflow: 'hidden' }}>
        {loading ? (
          <div style={{ padding: 40, textAlign: 'center', color: 'var(--txt-dim)' }}>Loading…</div>
        ) : loadError ? (
          <div style={{ padding: 24 }}>
            <Banner tone="error">{loadError}</Banner>
            <button style={{ ...secondaryBtn, marginTop: 12 }} onClick={() => { setLoading(true); reload(); }}>Try again</button>
          </div>
        ) : filtered.length === 0 ? (
          <div style={{ padding: 48, textAlign: 'center' }}>
            <div style={{ fontSize: 15, color: 'var(--txt-mut)', marginBottom: 8 }}>{roles.length === 0 ? 'No roles yet' : 'No matching roles'}</div>
            <div style={{ fontSize: 13, color: 'var(--txt-dim)' }}>{roles.length === 0 ? 'Create the first custom role to get started.' : 'Try adjusting the search or filters.'}</div>
          </div>
        ) : (
          <div style={{ overflowX: 'auto' }}>
            <table style={{ width: '100%', borderCollapse: 'collapse', minWidth: 720 }}>
              <thead>
                <tr>{['Role', 'Type', 'Users', 'Permissions', 'Status', 'Updated', ''].map(h => <th key={h} style={thStyle}>{h}</th>)}</tr>
              </thead>
              <tbody>
                {filtered.map(r => (
                  <tr key={r.id} style={{ cursor: 'pointer', opacity: r.active ? 1 : 0.65 }} onClick={() => setViewing(r.id)}
                    onMouseEnter={e => (e.currentTarget.style.background = 'var(--raised)')} onMouseLeave={e => (e.currentTarget.style.background = 'transparent')}>
                    <td style={tdStyle}>
                      <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--txt)', display: 'flex', alignItems: 'center', gap: 6 }}>
                        {r.name}{!r.permissionsEditable && <Lock size={12} aria-label="Protected" color="var(--txt-dim)" />}
                      </div>
                      {r.description && <div style={{ fontSize: 12, color: 'var(--txt-dim)', marginTop: 2, maxWidth: 420, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{r.description}</div>}
                    </td>
                    <td style={tdStyle}><RoleTypeBadge system={r.system} /></td>
                    <td style={tdStyle}>{r.userCount}</td>
                    <td style={tdStyle}>{r.code === 'EMPLOYEE' && r.permissionCount === 0 ? 'Self-service' : r.permissionCount}</td>
                    <td style={tdStyle}><StatusPill active={r.active} /></td>
                    <td style={{ ...tdStyle, whiteSpace: 'nowrap' }}>{fmtDate(r.updatedAt)}</td>
                    <td style={{ ...tdStyle, textAlign: 'right', whiteSpace: 'nowrap' }}>
                      <button style={{ ...secondaryBtn, padding: '5px 10px', fontSize: 12 }} onClick={e => { e.stopPropagation(); openEditor(r.id); }}>
                        {r.permissionsEditable || r.metadataEditable ? 'Edit' : 'View'}
                      </button>
                      {!r.system && canDelete && (
                        <button style={{ ...dangerBtn, padding: '5px 10px', fontSize: 12, marginLeft: 6 }} aria-label={`Delete ${r.name}`}
                          onClick={e => { e.stopPropagation(); setDeletingRole(r); }}>
                          Delete
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {editing !== undefined && createPortal(
        <RoleEditorModal token={token} catalogue={catalogue} existing={editing} onClose={() => setEditing(undefined)}
          onSaved={saved => { setEditing(undefined); reload(); setViewing(saved.role.id); }} />,
        document.body,
      )}
      {deletingRole && (
        <DeleteRoleDialog token={token} role={deletingRole} onClose={() => setDeletingRole(null)}
          onDeleted={() => { setDeletingRole(null); reload(); }} />
      )}
      {viewing !== null && editing === undefined && (
        <RoleDetailsDrawer token={token} roleId={viewing} catalogue={catalogue} onClose={() => setViewing(null)}
          onEdit={d => setEditing(d)} onChanged={reload} />
      )}
    </div>
  );
}
