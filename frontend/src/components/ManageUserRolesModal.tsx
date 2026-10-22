import { useCallback, useEffect, useMemo, useState } from 'react';
import { X, Search as SearchIcon, Lock } from 'lucide-react';
import { ChipGroup } from './TabBar';
import { rolesApi, type RoleSummary, type UserRoles } from '../api/roles';
import { useToast } from '../context/ToastContext';
import { ACTION_LABEL, SCOPE_LABEL } from '../lib/rolePermissions';
import { useBackToClose } from '../lib/backToClose';

/*
 * User Management → "Manage Roles": assign several roles to one user and see the resulting
 * effective permissions (and which role grants each). Server-side, PUT /api/users/{id}/roles runs
 * the same guards as every other assignment path (no self-change, privilege ceiling, last Super
 * Admin, inactive roles) — this dialog only mirrors the obvious ones and shows the server's error
 * for the rest. Employee is always kept; an auto-granted Manager follows reporting lines.
 */

const overlayStyle: React.CSSProperties = { position: 'fixed', inset: 0, background: 'rgba(15,17,23,.6)', backdropFilter: 'blur(2px)', display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 500, padding: 16 };
const btn: React.CSSProperties = { borderRadius: 8, padding: '9px 16px', fontSize: 13, fontWeight: 600, cursor: 'pointer' };
const primaryBtn: React.CSSProperties = { ...btn, background: 'var(--brand)', color: '#fff', border: 'none' };
const secondaryBtn: React.CSSProperties = { ...btn, background: 'var(--raised2)', color: 'var(--txt-mut)', border: '1px solid var(--line2)' };

function Note({ tone, children }: { tone: 'warn' | 'error' | 'info'; children: React.ReactNode }) {
  const c = tone === 'error' ? 'var(--risk)' : tone === 'warn' ? '#E0A93B' : 'var(--info, #4C8DD6)';
  return <div role={tone === 'error' ? 'alert' : undefined} style={{ fontSize: 12.5, color: 'var(--txt)', background: `color-mix(in srgb, ${c} 10%, transparent)`, border: `1px solid color-mix(in srgb, ${c} 35%, transparent)`, borderRadius: 9, padding: '9px 12px', lineHeight: 1.5 }}>{children}</div>;
}

export default function ManageUserRolesModal({ token, userId, isSelf, onClose, onSaved }: {
  token: string;
  userId: string;
  isSelf: boolean;
  onClose: () => void;
  onSaved: () => void;
}) {
  const { showToast } = useToast();
  const [data, setData] = useState<UserRoles | null>(null);
  const [roles, setRoles] = useState<RoleSummary[]>([]);
  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [tab, setTab] = useState<'roles' | 'effective'>('roles');
  const [query, setQuery] = useState('');
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);
  const [confirmRevoke, setConfirmRevoke] = useState(false);

  useEffect(() => {
    Promise.all([rolesApi.userRoles(userId, token), rolesApi.list(token)])
      .then(([u, r]) => { setData(u); setRoles(r); setSelected(new Set(u.roles.map(x => x.id))); })
      .catch(e => setError(e.message));
  }, [userId, token]);

  const held = useMemo(() => new Map((data?.roles ?? []).map(r => [r.id, r])), [data]);
  const initial = useMemo(() => new Set(data?.roles.map(r => r.id)), [data]);
  const dirty = data != null && (selected.size !== initial.size || [...selected].some(id => !initial.has(id)));
  const removed = [...initial].filter(id => !selected.has(id));

  const requestClose = useCallback(() => { if (!saving) onClose(); }, [saving, onClose]);
  useBackToClose(requestClose);
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') requestClose(); };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [requestClose]);

  // Listed: every active role, plus any inactive role the user still holds (so it can be removed).
  const options = roles
    .filter(r => r.active || held.has(r.id))
    .filter(r => !query.trim() || r.name.toLowerCase().includes(query.trim().toLowerCase()));

  function lockReason(r: RoleSummary): string | null {
    if (isSelf) return 'You cannot change your own roles.';
    if (r.code === 'EMPLOYEE') return 'Every user holds the Employee role.';
    if (held.get(r.id)?.autoGranted) return 'Granted automatically while this user has direct reports.';
    if (!r.active && !held.has(r.id)) return 'Inactive roles cannot be assigned.';
    return null;
  }

  function toggle(id: number) {
    setConfirmRevoke(false);
    setSelected(prev => { const n = new Set(prev); if (n.has(id)) n.delete(id); else n.add(id); return n; });
  }

  async function save() {
    if (removed.length && !confirmRevoke) { setConfirmRevoke(true); return; }
    setSaving(true); setError('');
    try {
      const updated = await rolesApi.setUserRoles(userId, [...selected], token);
      setData(updated);
      setSelected(new Set(updated.roles.map(r => r.id)));
      setConfirmRevoke(false);
      showToast('success', `Roles updated for ${updated.fullName}. They'll be asked to sign in again.`);
      onSaved();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not update roles.');
    } finally {
      setSaving(false);
    }
  }

  // Effective permissions grouped by module.
  const effectiveByModule = useMemo(() => {
    const m = new Map<string, NonNullable<typeof data>['effective']>();
    for (const g of data?.effective ?? []) m.set(g.moduleName, [...(m.get(g.moduleName) ?? []), g]);
    return [...m.entries()];
  }, [data]);

  return (
    <div style={overlayStyle} onMouseDown={e => { if (e.target === e.currentTarget) requestClose(); }}>
      <div role="dialog" aria-modal="true" aria-label="Manage roles"
        style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 16, width: '94vw', maxWidth: 620, maxHeight: '90vh', display: 'flex', flexDirection: 'column', boxShadow: '0 24px 64px rgba(0,0,0,.4)' }}>
        <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 12, padding: '18px 22px', borderBottom: '1px solid var(--line)' }}>
          <div style={{ minWidth: 0 }}>
            <div style={{ fontFamily: '"Space Grotesk", sans-serif', fontWeight: 700, fontSize: 16, color: 'var(--txt)' }}>Manage Roles</div>
            <div style={{ fontSize: 12, color: 'var(--txt-mut)', marginTop: 2 }}>{data ? `${data.fullName} · ${data.email}` : 'Loading…'}</div>
          </div>
          <button onClick={requestClose} aria-label="Close" style={{ background: 'var(--raised2)', border: 'none', cursor: 'pointer', color: 'var(--txt-mut)', padding: 6, borderRadius: 8, display: 'flex' }}><X size={16} /></button>
        </div>
        <div style={{ padding: '14px 22px 0' }}>
          <ChipGroup ariaLabel="Section" value={tab} onChange={setTab}
            options={[{ key: 'roles', label: 'Assigned roles' }, { key: 'effective', label: `Effective permissions${data ? ` (${data.effective.length})` : ''}` }]} />
        </div>
        <div style={{ padding: '14px 22px', overflowY: 'auto', display: 'flex', flexDirection: 'column', gap: 12, flex: 1 }}>
          {!data && !error && <div style={{ padding: 30, textAlign: 'center', color: 'var(--txt-dim)' }}>Loading…</div>}
          {data && tab === 'roles' && (
            <>
              {isSelf && <Note tone="info">You cannot change your own roles. Ask another administrator.</Note>}
              <div style={{ position: 'relative' }}>
                <SearchIcon size={14} style={{ position: 'absolute', left: 11, top: '50%', transform: 'translateY(-50%)', color: 'var(--txt-dim)', pointerEvents: 'none' }} />
                <input value={query} onChange={e => setQuery(e.target.value)} placeholder="Search roles…" aria-label="Search roles"
                  style={{ width: '100%', boxSizing: 'border-box', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 8, padding: '8px 12px 8px 32px', color: 'var(--txt)', fontSize: 13, outline: 'none' }} />
              </div>
              <div style={{ border: '1px solid var(--line)', borderRadius: 10, overflow: 'hidden' }}>
                {options.length === 0 && <div style={{ padding: 16, fontSize: 13, color: 'var(--txt-dim)' }}>No roles match.</div>}
                {options.map((r, i) => {
                  const reason = lockReason(r);
                  const checked = selected.has(r.id);
                  return (
                    <label key={r.id} title={reason ?? undefined}
                      style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '10px 14px', borderTop: i ? '1px solid var(--line)' : 'none', cursor: reason ? 'default' : 'pointer' }}>
                      <input type="checkbox" checked={checked} disabled={!!reason} onChange={() => toggle(r.id)}
                        style={{ width: 15, height: 15, accentColor: 'var(--brand)' }} />
                      <div style={{ flex: 1, minWidth: 0 }}>
                        <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--txt)', display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
                          {r.name}
                          <span style={{ fontSize: 10, fontWeight: 700, color: r.system ? '#4C8DD6' : '#8B5CF6' }}>{r.system ? 'SYSTEM' : 'CUSTOM'}</span>
                          {!r.active && <span style={{ fontSize: 10, fontWeight: 700, color: 'var(--txt-dim)' }}>INACTIVE · GRANTS NOTHING</span>}
                          {held.get(r.id)?.autoGranted && <span style={{ fontSize: 10, fontWeight: 700, color: 'var(--txt-dim)' }}>AUTO</span>}
                        </div>
                        {(reason && !isSelf) ? <div style={{ fontSize: 11.5, color: 'var(--txt-dim)' }}><Lock size={10} style={{ verticalAlign: -1 }} /> {reason}</div>
                          : r.description && <div style={{ fontSize: 11.5, color: 'var(--txt-dim)' }}>{r.description}</div>}
                      </div>
                    </label>
                  );
                })}
              </div>
              {confirmRevoke && (
                <Note tone="warn">
                  Removing {removed.map(id => held.get(id)?.name).filter(Boolean).join(', ')} takes that access away immediately.
                  {' '}{data.fullName} will be signed out and asked to sign in again. Save again to confirm.
                </Note>
              )}
            </>
          )}
          {data && tab === 'effective' && (
            effectiveByModule.length === 0
              ? <Note tone="info">No permissions beyond Employee self-service (available to every signed-in user).</Note>
              : (
                <>
                  <div style={{ fontSize: 12, color: 'var(--txt-dim)' }}>The union of every active role this user holds, as currently saved. Unsaved changes on the other tab aren't reflected here.</div>
                  <div style={{ border: '1px solid var(--line)', borderRadius: 10, overflow: 'hidden' }}>
                    {effectiveByModule.map(([moduleName, grants], i) => (
                      <div key={moduleName} style={{ padding: '10px 14px', borderTop: i ? '1px solid var(--line)' : 'none' }}>
                        <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--txt)', marginBottom: 6 }}>{moduleName}</div>
                        <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
                          {grants.map(g => (
                            <div key={`${g.action}:${g.scope}`} style={{ display: 'flex', gap: 8, fontSize: 12.5, color: 'var(--txt-mut)', flexWrap: 'wrap' }}>
                              <span style={{ minWidth: 80, color: 'var(--txt)' }}>{ACTION_LABEL[g.action] ?? g.action}</span>
                              <span style={{ minWidth: 90 }}>{SCOPE_LABEL[g.scope] ?? g.scope}</span>
                              <span style={{ color: 'var(--txt-dim)' }}>via {g.sources.join(', ')}</span>
                            </div>
                          ))}
                        </div>
                      </div>
                    ))}
                  </div>
                </>
              )
          )}
          {error && <Note tone="error">{error}</Note>}
        </div>
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10, padding: '14px 22px', borderTop: '1px solid var(--line)' }}>
          <button style={secondaryBtn} onClick={requestClose}>{dirty ? 'Cancel' : 'Close'}</button>
          {!isSelf && (
            <button style={{ ...primaryBtn, opacity: !dirty || saving ? 0.6 : 1 }} disabled={!dirty || saving} onClick={save}>
              {saving ? 'Saving…' : confirmRevoke ? 'Confirm & save' : 'Save roles'}
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
