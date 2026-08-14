import { useEffect, useState } from 'react';
import { Copy, Pencil, Plus, Trash2, X } from 'lucide-react';
import { useToast } from '../../context/ToastContext';
import { penalisationPoliciesApi, type PenalisationPolicySummary } from '../../api/penalisationPolicies';
import PenalizationPolicySection from './PenalizationPolicySection';

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

// ── Name/description prompt — shared shell for Create/Rename/Clone ──
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
          <h2 style={{ margin: 0, fontSize: 15, fontFamily: '"Space Grotesk", sans-serif', fontWeight: 700, color: 'var(--txt)' }}>{title}</h2>
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
        <h2 style={{ margin: '0 0 10px', fontSize: 15, fontFamily: '"Space Grotesk", sans-serif', fontWeight: 700, color: 'var(--txt)' }}>Delete "{policy.name}"?</h2>
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

// ── Edit — full policy configuration for one specific policy, in a large modal ──
function EditPolicyModal({ policy, token, onClose }: { policy: PenalisationPolicySummary; token: string; onClose: () => void }) {
  return (
    <div role="dialog" aria-modal="true" aria-label={`Edit ${policy.name}`} style={{
      position: 'fixed', inset: 0, zIndex: 205, display: 'flex', alignItems: 'flex-start', justifyContent: 'center',
      background: 'rgba(0,0,0,.55)', backdropFilter: 'blur(4px)', overflowY: 'auto', padding: '40px 16px',
    }} onClick={e => { if (e.target === e.currentTarget) onClose(); }}>
      <div style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 12, padding: '22px 26px', width: 720, maxWidth: '95vw', boxShadow: '0 24px 48px rgba(0,0,0,.4)' }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 16 }}>
          <h2 style={{ margin: 0, fontSize: 15, fontFamily: '"Space Grotesk", sans-serif', fontWeight: 700, color: 'var(--txt)' }}>{policy.name}</h2>
          <button onClick={onClose} aria-label="Close" style={{ background: 'transparent', border: 'none', cursor: 'pointer', color: 'var(--txt-dim)', padding: 4 }}><X size={16} /></button>
        </div>
        <PenalizationPolicySection token={token} policyId={policy.id} />
      </div>
    </div>
  );
}

export default function PolicyListSection({ token }: { token: string }) {
  const { showToast } = useToast();
  const [policies, setPolicies] = useState<PenalisationPolicySummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const [creating, setCreating] = useState(false);
  const [renaming, setRenaming] = useState<PenalisationPolicySummary | null>(null);
  const [cloning, setCloning] = useState<PenalisationPolicySummary | null>(null);
  const [deleting, setDeleting] = useState<PenalisationPolicySummary | null>(null);
  const [editing, setEditing] = useState<PenalisationPolicySummary | null>(null);

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
              {['Policy Name', 'Status', 'Employee Count', 'Version', 'Effective Date', ''].map(h => (
                <th key={h} style={{ padding: '10px 14px', fontSize: 11.5, fontWeight: 700, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: 0.3, borderBottom: '1px solid var(--line)' }}>{h}</th>
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
                </td>
                <td style={{ padding: '10px 14px', color: 'var(--txt-mut)' }}>{p.employeeCount}</td>
                <td style={{ padding: '10px 14px', color: 'var(--txt-mut)' }}>{p.currentVersion != null ? `V${p.currentVersion}` : 'Not configured'}</td>
                <td style={{ padding: '10px 14px', color: 'var(--txt-mut)' }}>{fmtDate(p.effectiveFrom)}</td>
                <td style={{ padding: '10px 14px' }}>
                  <div style={{ display: 'flex', gap: 6, justifyContent: 'flex-end' }}>
                    <button aria-label={`Edit ${p.name}`} onClick={() => setEditing(p)} title="Edit" style={{ background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, width: 30, height: 30, color: 'var(--txt-mut)', cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center' }}><Pencil size={13} /></button>
                    <button aria-label={`Rename ${p.name}`} onClick={() => setRenaming(p)} title="Rename" style={{ background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, padding: '0 10px', height: 30, color: 'var(--txt-mut)', cursor: 'pointer', fontSize: 12 }}>Rename</button>
                    <button aria-label={`Clone ${p.name}`} onClick={() => setCloning(p)} title="Clone" style={{ background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, width: 30, height: 30, color: 'var(--txt-mut)', cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center' }}><Copy size={13} /></button>
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
      {renaming && (
        <NamePromptModal title={`Rename "${renaming.name}"`} initialName={renaming.name} initialDescription={renaming.description ?? ''} confirmLabel="Save"
          onClose={() => setRenaming(null)}
          onConfirm={async (name, description) => {
            await penalisationPoliciesApi.rename(token, renaming.id, name, description);
            setRenaming(null);
            showToast('success', 'Policy renamed');
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
