import { useEffect, useState } from 'react';
import { Copy, Pencil, Power, Trash2, Plus, X, Star } from 'lucide-react';
import { useToast } from '../../context/ToastContext';
import { penalisationPoliciesApi, type PenalisationPolicySummary, type PenalizationFallbackStrategy } from '../../api/penalisationPolicies';
import PenalizationPolicySection, { ConfirmDiscardModal } from './PenalizationPolicySection';

const inputStyle: React.CSSProperties = {
  background: 'var(--raised)', border: '1px solid var(--line2)',
  borderRadius: 6, padding: '7px 9px', fontSize: 13, color: 'var(--txt)',
  outline: 'none', width: '100%', boxSizing: 'border-box',
};
const labelText: React.CSSProperties = { fontSize: 12, fontWeight: 600, color: 'var(--txt-mut)', display: 'block', marginBottom: 5 };

function fmtDate(iso: string | null | undefined): string {
  if (!iso) return '—';
  return new Date(iso).toLocaleDateString(undefined, { day: '2-digit', month: 'short', year: 'numeric' });
}

// ── Name/description prompt — shared shell for Create/Clone (Rename lives in EditPolicyModal) ──
function NamePromptModal({ title, initialName, initialDescription, confirmLabel, onClose, onConfirm }: {
  title: string; initialName: string; initialDescription: string; confirmLabel: string;
  onClose: () => void; onConfirm: (name: string, description: string) => Promise<void>;
}) {
  const [name, setName] = useState(initialName);
  const [description, setDescription] = useState(initialDescription);
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);

  async function submit() {
    if (!name.trim()) { setError('Name is required'); return; }
    setError('');
    setSaving(true);
    try {
      await onConfirm(name.trim(), description.trim());
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed');
      setSaving(false);
    }
  }

  return (
    <div role="dialog" aria-modal="true" aria-label={title} style={{
      position: 'fixed', inset: 0, zIndex: 210, display: 'flex', alignItems: 'center', justifyContent: 'center',
      background: 'rgba(0,0,0,.55)', backdropFilter: 'blur(4px)',
    }} onClick={e => { if (e.target === e.currentTarget) onClose(); }}>
      <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, padding: '22px 26px', width: 420, maxWidth: '95vw', boxShadow: '0 24px 48px rgba(0,0,0,.4)' }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 16 }}>
          <h2 style={{ margin: 0, fontSize: 15, fontFamily: 'Inter, sans-serif', fontWeight: 700, color: 'var(--txt)' }}>{title}</h2>
          <button onClick={onClose} aria-label="Close" style={{ background: 'transparent', border: 'none', cursor: 'pointer', color: 'var(--txt-dim)', padding: 4 }}><X size={16} /></button>
        </div>
        {error && (
          <div role="alert" style={{ background: 'rgba(228,55,61,.1)', border: '1px solid rgba(228,55,61,.3)', borderRadius: 6, padding: '8px 12px', marginBottom: 14, color: 'var(--risk)', fontSize: 12.5 }}>
            {error}
          </div>
        )}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
          <div>
            <span style={labelText}>Policy name</span>
            <input style={inputStyle} value={name} onChange={e => setName(e.target.value)} autoFocus />
          </div>
          <div>
            <span style={labelText}>Description</span>
            <input style={inputStyle} value={description} onChange={e => setDescription(e.target.value)} />
          </div>
        </div>
        <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end', marginTop: 20 }}>
          <button type="button" onClick={onClose} style={{ padding: '7px 14px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, fontSize: 12.5, color: 'var(--txt-mut)', cursor: 'pointer' }}>Cancel</button>
          <button type="button" onClick={submit} disabled={saving} style={{ padding: '7px 16px', background: 'var(--brand)', border: 'none', borderRadius: 6, fontSize: 12.5, fontWeight: 600, color: '#fff', cursor: saving ? 'not-allowed' : 'pointer', opacity: saving ? 0.7 : 1 }}>
            {saving ? 'Saving…' : confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}

function ConfirmDeleteModal({ policy, onClose, onConfirm }: { policy: PenalisationPolicySummary; onClose: () => void; onConfirm: () => Promise<void> }) {
  const [error, setError] = useState('');
  const [deleting, setDeleting] = useState(false);

  async function confirm() {
    setError('');
    setDeleting(true);
    try {
      await onConfirm();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to delete');
      setDeleting(false);
    }
  }

  return (
    <div role="dialog" aria-modal="true" aria-label="Delete policy" style={{
      position: 'fixed', inset: 0, zIndex: 210, display: 'flex', alignItems: 'center', justifyContent: 'center',
      background: 'rgba(0,0,0,.55)', backdropFilter: 'blur(4px)',
    }} onClick={e => { if (e.target === e.currentTarget) onClose(); }}>
      <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, padding: '22px 26px', width: 420, maxWidth: '95vw' }}>
        <h2 style={{ margin: '0 0 10px', fontSize: 15, fontFamily: 'Inter, sans-serif', fontWeight: 700, color: 'var(--txt)' }}>Delete "{policy.name}"?</h2>
        <p style={{ margin: '0 0 16px', fontSize: 13, color: 'var(--txt-mut)' }}>
          {policy.employeeCount > 0
            ? `${policy.employeeCount} employee(s) are still assigned to this policy — reassign them first.`
            : 'This removes the policy and all of its saved rule versions. This cannot be undone.'}
        </p>
        {error && <div role="alert" style={{ background: 'rgba(228,55,61,.1)', border: '1px solid rgba(228,55,61,.3)', borderRadius: 6, padding: '8px 12px', marginBottom: 14, color: 'var(--risk)', fontSize: 12.5 }}>{error}</div>}
        <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
          <button type="button" onClick={onClose} style={{ padding: '7px 14px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, fontSize: 12.5, color: 'var(--txt-mut)', cursor: 'pointer' }}>Cancel</button>
          <button type="button" onClick={confirm} disabled={deleting || policy.employeeCount > 0}
            style={{ padding: '7px 16px', background: 'var(--risk)', border: 'none', borderRadius: 6, fontSize: 12.5, fontWeight: 600, color: '#fff', cursor: (deleting || policy.employeeCount > 0) ? 'not-allowed' : 'pointer', opacity: (deleting || policy.employeeCount > 0) ? 0.6 : 1 }}>
            {deleting ? 'Deleting…' : 'Delete'}
          </button>
        </div>
      </div>
    </div>
  );
}

// ── Edit — full policy configuration for one specific policy, in a large modal.
// Also owns Rename (name/description) — folded in here instead of its own Actions button, so
// Actions only ever holds Edit/Clone/Deactivate-Activate/Delete. ──
function EditPolicyModal({ policy: initialPolicy, token, onClose }: { policy: PenalisationPolicySummary; token: string; onClose: () => void }) {
  const { showToast } = useToast();
  const [policy, setPolicy] = useState(initialPolicy);
  const [dirty, setDirty] = useState(false);
  const [confirmingClose, setConfirmingClose] = useState(false);

  const [name, setName] = useState(policy.name);
  const [description, setDescription] = useState(policy.description ?? '');
  const [savingDetails, setSavingDetails] = useState(false);
  const [detailsError, setDetailsError] = useState('');
  const detailsDirty = name.trim() !== policy.name || description.trim() !== (policy.description ?? '');

  function requestClose() {
    if (dirty) setConfirmingClose(true); else onClose();
  }

  async function saveDetails() {
    if (!name.trim()) { setDetailsError('Name is required'); return; }
    setDetailsError('');
    setSavingDetails(true);
    try {
      const updated = await penalisationPoliciesApi.rename(token, policy.id, name.trim(), description.trim());
      setPolicy(p => ({ ...p, name: updated.name, description: updated.description }));
      showToast('success', 'Policy renamed');
    } catch (e) {
      setDetailsError(e instanceof Error ? e.message : 'Failed to save');
    } finally {
      setSavingDetails(false);
    }
  }

  return (
    <div role="dialog" aria-modal="true" aria-label={`Edit ${policy.name}`} style={{
      position: 'fixed', inset: 0, zIndex: 205, display: 'flex', alignItems: 'flex-start', justifyContent: 'center',
      background: 'rgba(0,0,0,.55)', backdropFilter: 'blur(4px)', overflowY: 'auto', padding: '40px 16px',
    }} onClick={e => { if (e.target === e.currentTarget) requestClose(); }}>
      <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, padding: '22px 26px', width: 900, maxWidth: '95vw', boxShadow: '0 24px 48px rgba(0,0,0,.4)' }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 16 }}>
          <h2 style={{ margin: 0, fontSize: 15, fontFamily: 'Inter, sans-serif', fontWeight: 700, color: 'var(--txt)' }}>{policy.name}</h2>
          <button onClick={requestClose} aria-label="Close" style={{ background: 'transparent', border: 'none', cursor: 'pointer', color: 'var(--txt-dim)', padding: 4 }}><X size={16} /></button>
        </div>

        {/* Rename — name/description */}
        <div style={{ background: 'var(--raised)', border: '1px solid var(--line)', borderRadius: 8, padding: 14, marginBottom: 18 }}>
          <div style={{ fontSize: 11, fontWeight: 700, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.04em', marginBottom: 10 }}>
            Rename
          </div>
          {detailsError && (
            <div role="alert" style={{ background: 'rgba(228,55,61,.1)', border: '1px solid rgba(228,55,61,.3)', borderRadius: 6, padding: '8px 12px', marginBottom: 10, color: 'var(--risk)', fontSize: 12.5 }}>
              {detailsError}
            </div>
          )}
          <div className="nf-grid-2col-collapse" style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10, marginBottom: 10 }}>
            <div>
              <span style={labelText}>Policy name</span>
              <input style={inputStyle} value={name} onChange={e => setName(e.target.value)} />
            </div>
            <div>
              <span style={labelText}>Description</span>
              <input style={inputStyle} value={description} onChange={e => setDescription(e.target.value)} />
            </div>
          </div>
          <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
            <button type="button" onClick={saveDetails} disabled={!detailsDirty || savingDetails}
              style={{ padding: '6px 14px', background: 'var(--brand)', border: 'none', borderRadius: 6, fontSize: 12.5, fontWeight: 600, color: '#fff', cursor: (!detailsDirty || savingDetails) ? 'not-allowed' : 'pointer', opacity: (!detailsDirty || savingDetails) ? 0.6 : 1 }}>
              {savingDetails ? 'Saving…' : 'Save Name'}
            </button>
          </div>
        </div>

        <PenalizationPolicySection token={token} policyId={policy.id} onDirtyChange={setDirty} />
      </div>
      {confirmingClose && (
        <ConfirmDiscardModal onKeepEditing={() => setConfirmingClose(false)} onDiscard={onClose} />
      )}
    </div>
  );
}

export default function PolicyListSection({ token, onViewAllocations }: {
  token: string;
  /** Navigates to the Penalization Policy Allocation sub-tab with this policy pre-selected as the filter. */
  onViewAllocations?: (policyId: string) => void;
}) {
  const { showToast } = useToast();
  const [policies, setPolicies] = useState<PenalisationPolicySummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const [creating, setCreating] = useState(false);
  const [cloning, setCloning] = useState<PenalisationPolicySummary | null>(null);
  const [deleting, setDeleting] = useState<PenalisationPolicySummary | null>(null);
  const [editing, setEditing] = useState<PenalisationPolicySummary | null>(null);
  const [togglingId, setTogglingId] = useState<string | null>(null);
  const [settingDefaultId, setSettingDefaultId] = useState<string | null>(null);
  const [fallbackStrategy, setFallbackStrategy] = useState<PenalizationFallbackStrategy | null>(null);

  async function handleSetOrgDefault(policy: PenalisationPolicySummary) {
    setSettingDefaultId(policy.id);
    try {
      await penalisationPoliciesApi.setOrgDefault(token, policy.id);
      showToast('success', `"${policy.name}" is now the organization default`);
      await load();
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Failed to set organization default');
    } finally {
      setSettingDefaultId(null);
    }
  }

  async function handleToggleActive(policy: PenalisationPolicySummary) {
    setTogglingId(policy.id);
    try {
      await penalisationPoliciesApi.toggleActive(token, policy.id);
      showToast('success', policy.status === 'ACTIVE'
        ? `"${policy.name}" deactivated — it no longer appears in the active allocation dropdown`
        : `"${policy.name}" activated`);
      await load();
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Failed to update status');
    } finally {
      setTogglingId(null);
    }
  }

  async function load() {
    setLoadError('');
    setLoading(true);
    try {
      setPolicies(await penalisationPoliciesApi.list(token));
    } catch (e) {
      setLoadError(e instanceof Error ? e.message : 'Failed to load policies');
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { if (token) load(); }, [token]);
  // Section 7: read-only, deploy-time config — see penalisationPoliciesApi.getFallbackStrategy.
  useEffect(() => {
    if (!token) return;
    penalisationPoliciesApi.getFallbackStrategy(token).then(r => setFallbackStrategy(r.strategy)).catch(() => { /* informational only */ });
  }, [token]);

  if (loading) {
    return <div style={{ padding: 24, color: 'var(--txt-mut)', fontSize: 13 }}>Loading…</div>;
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
      {loadError && (
        <div role="alert" style={{ background: 'rgba(228,55,61,.1)', border: '1px solid rgba(228,55,61,.3)', borderRadius: 8, padding: '10px 14px', color: 'var(--risk)', fontSize: 13 }}>
          {loadError}
        </div>
      )}

      {fallbackStrategy && (
        <div style={{ fontSize: 12, color: 'var(--txt-mut)', background: 'var(--raised)', border: '1px solid var(--line)', borderRadius: 8, padding: '8px 12px', whiteSpace: 'nowrap', overflowX: 'auto' }}>
          {fallbackStrategy === 'DEFAULT_POLICY'
            ? <>An employee with no allocation and no legacy assignment resolves to the <strong>Org Default</strong> policy below (marked with <Star size={11} style={{ display: 'inline', verticalAlign: -1 }} />).</>
            : <>REQUIRE_ALLOCATION is active — an employee with no allocation and no legacy assignment resolves to <strong>no policy at all</strong> until one is explicitly allocated (see "Needs Allocation" on the Allocation screen).</>}
        </div>
      )}

      <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
        <button type="button" onClick={() => setCreating(true)} style={{
          display: 'flex', alignItems: 'center', gap: 6, padding: '7px 14px', background: 'var(--brand)',
          border: 'none', borderRadius: 6, fontSize: 12.5, fontWeight: 600, color: '#fff', cursor: 'pointer',
        }}>
          <Plus size={14} /> New Policy
        </button>
      </div>

      <div style={{ overflowX: 'auto', border: '1px solid var(--line)', borderRadius: 10 }}>
        <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
          <thead>
            <tr style={{ background: 'var(--panel)', textAlign: 'left' }}>
              {['Policy Name', 'Status', 'Employee Count', 'Version', 'Effective Date', 'Actions'].map(h => (
                <th key={h} style={{ padding: '10px 14px', fontSize: 11.5, fontWeight: 700, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: 0.3, borderBottom: '1px solid var(--line)', textAlign: 'left' }}>{h}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {policies.map(p => (
              <tr key={p.id} style={{ borderBottom: '1px solid var(--line)' }}>
                <td style={{ padding: '10px 14px', color: 'var(--txt)', fontWeight: 500 }}>{p.name}</td>
                <td style={{ padding: '10px 14px' }}>
                  <span style={{
                    display: 'inline-flex', padding: '2px 8px', borderRadius: 20, fontSize: 11, fontWeight: 600,
                    background: p.status === 'ACTIVE' ? 'rgba(47,182,124,.15)' : 'rgba(107,114,128,.15)',
                    color: p.status === 'ACTIVE' ? 'var(--ok)' : 'var(--txt-dim)',
                  }}>{p.status}</span>
                  {p.orgDefault && (
                    <span title="Organization default — governs any employee with no allocation and no legacy assignment"
                      style={{ display: 'inline-flex', alignItems: 'center', gap: 3, marginLeft: 6, padding: '2px 8px', borderRadius: 20, fontSize: 11, fontWeight: 600, background: 'rgba(224,169,59,.15)', color: '#E0A93B' }}>
                      <Star size={10} fill="currentColor" /> Org Default
                    </span>
                  )}
                </td>
                <td style={{ padding: '10px 14px' }}>
                  {onViewAllocations ? (
                    <button
                      onClick={() => onViewAllocations(p.id)}
                      title="View this policy's employees in Penalization Policy Allocation"
                      style={{ background: 'none', border: 'none', padding: 0, color: 'var(--brand-bright)', fontWeight: 600, cursor: 'pointer', textDecoration: 'underline', fontSize: 13 }}>
                      {p.employeeCount}
                    </button>
                  ) : p.employeeCount}
                </td>
                <td style={{ padding: '10px 14px', color: 'var(--txt-mut)' }}>{p.currentVersion != null ? `V${p.currentVersion}` : 'Not configured'}</td>
                <td style={{ padding: '10px 14px', color: 'var(--txt-mut)' }}>{fmtDate(p.effectiveFrom)}</td>
                <td style={{ padding: '10px 14px' }}>
                  <div style={{ display: 'flex', gap: 6, justifyContent: 'flex-start' }}>
                    {(() => {
                      const isBusy = settingDefaultId === p.id;
                      const canSetDefault = p.status === 'ACTIVE' && !p.orgDefault && !isBusy;
                      const label = p.orgDefault ? `${p.name} is already the organization default`
                        : p.status !== 'ACTIVE' ? 'Only an active policy can be set as the organization default'
                        : `Set ${p.name} as organization default`;
                      return (
                        <button aria-label={label} title={label}
                          onClick={() => canSetDefault && handleSetOrgDefault(p)}
                          disabled={!canSetDefault}
                          aria-pressed={p.orgDefault}
                          style={{
                            background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, width: 30, height: 30,
                            color: p.orgDefault ? '#E0A93B' : 'var(--txt-mut)',
                            cursor: canSetDefault ? 'pointer' : 'not-allowed',
                            opacity: p.orgDefault ? 1 : (p.status !== 'ACTIVE' || isBusy) ? 0.5 : 1,
                            display: 'flex', alignItems: 'center', justifyContent: 'center',
                          }}>
                          <Star size={13} fill={p.orgDefault ? 'currentColor' : 'none'} />
                        </button>
                      );
                    })()}
                    <button aria-label={`Edit ${p.name}`} onClick={() => setEditing(p)} title="Edit" style={{ background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, width: 30, height: 30, color: 'var(--txt-mut)', cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center' }}><Pencil size={13} /></button>
                    <button aria-label={`Clone ${p.name}`} onClick={() => setCloning(p)} title="Clone" style={{ background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, width: 30, height: 30, color: 'var(--txt-mut)', cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center' }}><Copy size={13} /></button>
                    <button aria-label={p.status === 'ACTIVE' ? `Deactivate ${p.name}` : `Activate ${p.name}`}
                      onClick={() => handleToggleActive(p)} disabled={togglingId === p.id}
                      title={p.status === 'ACTIVE' ? 'Deactivate — hides it from new allocations, keeps existing history' : 'Activate'}
                      style={{ background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, width: 30, height: 30, color: p.status === 'ACTIVE' ? 'var(--txt-mut)' : 'var(--ok)', cursor: togglingId === p.id ? 'not-allowed' : 'pointer', opacity: togglingId === p.id ? 0.6 : 1, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
                      <Power size={13} />
                    </button>
                    <button aria-label={`Delete ${p.name}`} onClick={() => setDeleting(p)} title="Delete" style={{ background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, width: 30, height: 30, color: 'var(--risk)', cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center' }}><Trash2 size={13} /></button>
                  </div>
                </td>
              </tr>
            ))}
            {policies.length === 0 && (
              <tr><td colSpan={6} style={{ padding: '20px 14px', textAlign: 'center', color: 'var(--txt-mut)' }}>No Penalization Policies yet.</td></tr>
            )}
          </tbody>
        </table>
      </div>

      {creating && (
        <NamePromptModal title="New Penalization Policy" initialName="" initialDescription="" confirmLabel="Create"
          onClose={() => setCreating(false)}
          onConfirm={async (name, description) => {
            await penalisationPoliciesApi.create(token, name, description);
            setCreating(false);
            showToast('success', 'Policy created');
            load();
          }} />
      )}
      {cloning && (
        <NamePromptModal title={`Clone "${cloning.name}"`} initialName={`${cloning.name} (Copy)`} initialDescription={cloning.description ?? ''} confirmLabel="Clone"
          onClose={() => setCloning(null)}
          onConfirm={async (name, description) => {
            await penalisationPoliciesApi.clone(token, cloning.id, name, description);
            setCloning(null);
            showToast('success', 'Policy cloned');
            load();
          }} />
      )}
      {deleting && (
        <ConfirmDeleteModal policy={deleting}
          onClose={() => setDeleting(null)}
          onConfirm={async () => {
            await penalisationPoliciesApi.remove(token, deleting.id);
            setDeleting(null);
            showToast('success', 'Policy deleted');
            load();
          }} />
      )}
      {editing && (
        <EditPolicyModal policy={editing} token={token} onClose={() => { setEditing(null); load(); }} />
      )}
    </div>
  );
}
