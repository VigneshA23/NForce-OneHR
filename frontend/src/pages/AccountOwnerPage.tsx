import { useEffect, useState } from 'react';
import { Lock, Unlock, ShieldAlert } from 'lucide-react';
import { useAuthStore } from '../store/authStore';
import { accountOwnerApi, type ModuleFence } from '../api/accountOwner';
import { useToast } from '../context/ToastContext';
import { AccessDenied } from '../components/AccessDenied';
import { ConfirmDialog } from '../components/ConfirmDialog';

interface FenceTarget {
  key: string;
  label: string;
  description: string;
  pairs: { module: string; action: string }[];
}

// Mirrors the default fence set seeded by V228 — the Account Owner can toggle any of these at
// will, but these are the sensitive module/action pairs the story calls out by name: Payroll in
// full, and the authoring (not viewing) actions on the three policy-governance modules. A target
// is "fenced" only when every one of its pairs is currently fenced; toggling it fences/unfences
// all of them together so the Account Owner reasons about one module at a time, not loose actions.
const FENCE_TARGETS: FenceTarget[] = [
  {
    key: 'payroll',
    label: 'Payroll',
    description: 'Every action on the Payroll module — viewing and configuring pay runs.',
    pairs: [{ module: 'payroll', action: '*' }],
  },
  {
    key: 'leave-policy',
    label: 'Leave Policy Configuration',
    description: 'Authoring leave types and policy rules (viewing leave balances/requests is unaffected).',
    pairs: [{ module: 'leave', action: 'CONFIGURE' }],
  },
  {
    key: 'penalization-policy',
    label: 'Penalization Policy Authoring',
    description: 'Creating, editing, or deleting penalization policies.',
    pairs: [
      { module: 'penalization', action: 'CREATE' },
      { module: 'penalization', action: 'EDIT' },
      { module: 'penalization', action: 'DELETE' },
    ],
  },
  {
    key: 'documents-policies',
    label: 'Documents & Policies Publishing',
    description: 'Creating, editing, or deleting published policies and announcements.',
    pairs: [
      { module: 'policies', action: 'CREATE' },
      { module: 'policies', action: 'EDIT' },
      { module: 'policies', action: 'DELETE' },
    ],
  },
];

function isPairFenced(fences: ModuleFence[], module: string, action: string): boolean {
  return fences.some(f => f.module === module && f.action === action);
}

function isTargetFenced(fences: ModuleFence[], target: FenceTarget): boolean {
  return target.pairs.every(p => isPairFenced(fences, p.module, p.action));
}

export default function AccountOwnerPage() {
  const token = useAuthStore(s => s.token)!;
  const isAccountOwner = useAuthStore(s => s.permissions?.accountOwner) ?? false;
  const { showToast } = useToast();
  const [fences, setFences] = useState<ModuleFence[]>([]);
  const [loading, setLoading] = useState(true);
  const [busyKey, setBusyKey] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [unfencePending, setUnfencePending] = useState<FenceTarget | null>(null);

  useEffect(() => {
    if (!isAccountOwner) { setLoading(false); return; }
    accountOwnerApi.listFences(token).then(setFences).catch(err => {
      setError(err instanceof Error ? err.message : 'Could not load fences');
    }).finally(() => setLoading(false));
  }, [token, isAccountOwner]);

  if (!isAccountOwner) return <AccessDenied />;

  async function doFence(target: FenceTarget) {
    setBusyKey(target.key); setError(null);
    try {
      const created: ModuleFence[] = [];
      for (const p of target.pairs) {
        if (!isPairFenced(fences, p.module, p.action)) {
          created.push(await accountOwnerApi.fence(p.module, p.action, token));
        }
      }
      setFences(prev => [...prev, ...created]);
      showToast('success', `${target.label} fenced`);
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Fence failed';
      setError(msg); showToast('error', msg);
    } finally { setBusyKey(null); }
  }

  async function doUnfence(target: FenceTarget) {
    setBusyKey(target.key); setError(null);
    try {
      for (const p of target.pairs) {
        if (isPairFenced(fences, p.module, p.action)) {
          await accountOwnerApi.unfence(p.module, p.action, token);
        }
      }
      setFences(prev => prev.filter(f => !target.pairs.some(p => p.module === f.module && p.action === f.action)));
      showToast('success', `${target.label} un-fenced`);
      setUnfencePending(null);
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Un-fence failed';
      setError(msg); showToast('error', msg);
    } finally { setBusyKey(null); }
  }

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: 20 }}>
        <div style={{ width: 40, height: 40, borderRadius: 11, background: 'rgba(177,17,22,.12)', color: 'var(--brand-bright)', display: 'grid', placeItems: 'center', flexShrink: 0 }}>
          <ShieldAlert size={19} />
        </div>
        <div>
          <h1 style={{ fontFamily: '"Space Grotesk", sans-serif', fontSize: 21, fontWeight: 700, color: 'var(--txt)', margin: 0 }}>Account Owner Controls</h1>
          <p style={{ fontSize: 13, color: 'var(--txt-mut)', marginTop: 3 }}>
            Set the ceiling on what Super Admin can access. Fenced modules are hidden from every Super Admin and blocked server-side, even if they're granted through a role.
          </p>
        </div>
      </div>

      {error && <div style={{ color: 'var(--risk)', background: 'rgba(228,55,61,.08)', border: '1px solid rgba(228,55,61,.2)', borderRadius: 8, padding: '10px 14px', fontSize: 13, marginBottom: 16 }}>{error}</div>}

      {loading ? (
        <div style={{ color: 'var(--txt-dim)', padding: 20 }}>Loading…</div>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
          {FENCE_TARGETS.map(target => {
            const fenced = isTargetFenced(fences, target);
            const busy = busyKey === target.key;
            return (
              <div key={target.key} style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 14, padding: '18px 20px', display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 16, flexWrap: 'wrap' }}>
                <div style={{ display: 'flex', alignItems: 'flex-start', gap: 12 }}>
                  <div style={{ width: 34, height: 34, borderRadius: 9, background: fenced ? 'rgba(228,55,61,.1)' : 'rgba(47,182,124,.1)', color: fenced ? 'var(--risk)' : '#2FB67C', display: 'grid', placeItems: 'center', flexShrink: 0, marginTop: 2 }}>
                    {fenced ? <Lock size={16} /> : <Unlock size={16} />}
                  </div>
                  <div>
                    <div style={{ fontSize: 14, fontWeight: 600, color: 'var(--txt)' }}>{target.label}</div>
                    <div style={{ fontSize: 12.5, color: 'var(--txt-mut)', marginTop: 2, maxWidth: 480 }}>{target.description}</div>
                    <div style={{ fontSize: 11, fontWeight: 700, color: fenced ? 'var(--risk)' : '#2FB67C', marginTop: 6, textTransform: 'uppercase', letterSpacing: '.04em' }}>
                      {fenced ? 'Fenced — blocked for Super Admin' : 'Open — Super Admin can access'}
                    </div>
                  </div>
                </div>
                <button
                  onClick={() => fenced ? setUnfencePending(target) : doFence(target)}
                  disabled={busy}
                  style={{
                    background: fenced ? 'var(--raised2)' : '#C0392B',
                    color: fenced ? 'var(--txt-mut)' : '#fff',
                    border: fenced ? '1px solid var(--line2)' : 'none',
                    borderRadius: 8, padding: '9px 18px', fontSize: 13, fontWeight: 600,
                    cursor: busy ? 'not-allowed' : 'pointer', opacity: busy ? 0.7 : 1, whiteSpace: 'nowrap',
                  }}
                >
                  {busy ? 'Working…' : fenced ? 'Un-fence' : 'Fence'}
                </button>
              </div>
            );
          })}
        </div>
      )}

      {unfencePending && (
        <ConfirmDialog
          title="Un-fence module"
          body={<>
            Un-fencing <b style={{ color: 'var(--txt)' }}>{unfencePending.label}</b> immediately restores Super Admin's access to it.
            This takes effect right away and is recorded in the audit log.
          </>}
          confirmLabel="Un-fence"
          loading={busyKey === unfencePending.key}
          error={error}
          onConfirm={() => doUnfence(unfencePending)}
          onCancel={() => { setUnfencePending(null); setError(null); }}
        />
      )}
    </div>
  );
}
