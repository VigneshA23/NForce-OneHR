import { useEffect, useMemo, useState } from 'react';
import { createPortal } from 'react-dom';
import { rolesApi, type CatalogueModule, type PermissionHolder, type RoleDetail, type RoleHistoryEntry } from '../../api/roles';
import type { EmployeeRecord } from '../../api/employees';
import { ACTION_LABEL, SCOPE_LABEL } from '../../lib/rolePermissions';
import { compareGrants, describeAuthority, type CompareRow } from '../../lib/roleTools';
import { Banner, ModalHeader, inputStyle, overlayStyle, primaryBtn, secondaryBtn, tdStyle, thStyle, useDismiss } from './roleUi';

/** Catalogue module code -> display name, for rendering "module:ACTION:SCOPE" strings in words. */
function moduleNamesOf(catalogue: CatalogueModule[]): Record<string, string> {
  return Object.fromEntries(catalogue.map((m) => [m.code, m.name]));
}

function Shell({ title, subtitle, onClose, wide, children }: { title: string; subtitle?: string; onClose: () => void; wide?: boolean; children: React.ReactNode }) {
  useDismiss(onClose);
  return createPortal(
    <div style={overlayStyle} onMouseDown={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <div role="dialog" aria-modal="true" aria-label={title}
        style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 14, width: `min(${wide ? 860 : 640}px, 100%)`, maxHeight: '90vh', display: 'flex', flexDirection: 'column', boxShadow: '0 24px 64px rgba(0,0,0,.4)' }}>
        <ModalHeader title={title} subtitle={subtitle} onClose={onClose} />
        <div style={{ padding: '16px 22px 22px', overflowY: 'auto' }}>{children}</div>
      </div>
    </div>,
    document.body,
  );
}

// ─── Compare two roles ──────────────────────────────────────────────────────────
const ROW_TINT: Record<CompareRow['status'], string> = {
  same: 'transparent',
  differs: 'color-mix(in srgb, #E0A93B 12%, transparent)',
  onlyA: 'color-mix(in srgb, var(--info, #4C8DD6) 12%, transparent)',
  onlyB: 'color-mix(in srgb, #8B5CF6 12%, transparent)',
};

export function CompareRolesModal({ token, ids, catalogue, onClose }: { token: string; ids: [number, number]; catalogue: CatalogueModule[]; onClose: () => void }) {
  const [pair, setPair] = useState<[RoleDetail, RoleDetail] | null>(null);
  const [error, setError] = useState('');
  const [diffOnly, setDiffOnly] = useState(true);
  const names = useMemo(() => moduleNamesOf(catalogue), [catalogue]);

  useEffect(() => {
    let live = true;
    Promise.all([rolesApi.get(ids[0], token), rolesApi.get(ids[1], token)])
      .then((p) => { if (live) setPair(p); })
      .catch((e) => { if (live) setError(e instanceof Error ? e.message : 'Could not load the roles.'); });
    return () => { live = false; };
  }, [ids, token]);

  const rows = useMemo(() => (pair ? compareGrants(pair[0].grants, pair[1].grants) : []), [pair]);
  const shown = diffOnly ? rows.filter((r) => r.status !== 'same') : rows;
  const count = (s: CompareRow['status']) => rows.filter((r) => r.status === s).length;
  const scope = (s: string | null) => (s ? (SCOPE_LABEL[s] ?? s) : '—');

  return (
    <Shell title="Compare roles" subtitle={pair ? `${pair[0].role.name} vs ${pair[1].role.name}` : undefined} onClose={onClose} wide>
      {error && <Banner tone="error">{error}</Banner>}
      {!pair && !error && <div style={{ padding: 30, textAlign: 'center', color: 'var(--txt-dim)' }}>Loading…</div>}
      {pair && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
          <div style={{ display: 'flex', gap: 14, flexWrap: 'wrap', alignItems: 'center', fontSize: 12.5, color: 'var(--txt-mut)' }}>
            <span>{count('same')} shared</span>
            <span>{count('differs')} at a different scope</span>
            <span>{count('onlyA')} only in {pair[0].role.name}</span>
            <span>{count('onlyB')} only in {pair[1].role.name}</span>
            <div style={{ flex: 1 }} />
            <label style={{ display: 'inline-flex', alignItems: 'center', gap: 6, cursor: 'pointer' }}>
              <input type="checkbox" checked={diffOnly} onChange={(e) => setDiffOnly(e.target.checked)} style={{ accentColor: 'var(--brand)' }} />
              Differences only
            </label>
          </div>
          {shown.length === 0 ? (
            <div style={{ padding: 24, textAlign: 'center', fontSize: 13, color: 'var(--txt-dim)' }}>
              {rows.length === 0 ? 'Neither role grants any permissions.' : 'These two roles grant exactly the same permissions.'}
            </div>
          ) : (
            <div style={{ border: '1px solid var(--line)', borderRadius: 10, overflow: 'auto' }}>
              <table style={{ width: '100%', borderCollapse: 'collapse', minWidth: 520 }}>
                <thead>
                  <tr>
                    <th style={thStyle}>Permission</th>
                    <th style={thStyle}>{pair[0].role.name}</th>
                    <th style={thStyle}>{pair[1].role.name}</th>
                  </tr>
                </thead>
                <tbody>
                  {shown.map((r) => (
                    <tr key={`${r.module}:${r.action}`} style={{ background: ROW_TINT[r.status] }}>
                      <td style={{ ...tdStyle, color: 'var(--txt)' }}>{names[r.module] ?? r.module} · {ACTION_LABEL[r.action] ?? r.action}</td>
                      <td style={tdStyle}>{scope(r.a)}</td>
                      <td style={tdStyle}>{scope(r.b)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}
    </Shell>
  );
}

// ─── Who has this permission? ───────────────────────────────────────────────────
export function PermissionHoldersModal({ token, catalogue, onClose }: { token: string; catalogue: CatalogueModule[]; onClose: () => void }) {
  const [module, setModule] = useState('');
  const [action, setAction] = useState('');
  const [holders, setHolders] = useState<PermissionHolder[] | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const mod = catalogue.find((m) => m.code === module);

  useEffect(() => {
    if (!module || !action) { setHolders(null); return; }
    let live = true;
    setLoading(true); setError('');
    rolesApi.holders(module, action, token)
      .then((h) => { if (live) setHolders(h); })
      .catch((e) => { if (live) { setHolders(null); setError(e instanceof Error ? e.message : 'Could not look that up.'); } })
      .finally(() => { if (live) setLoading(false); });
    return () => { live = false; };
  }, [module, action, token]);

  return (
    <Shell title="Who has a permission?" subtitle="Everyone who holds it through an active role, and which roles confer it." onClose={onClose} wide>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
        <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap' }}>
          <select aria-label="Module" value={module} onChange={(e) => { setModule(e.target.value); setAction(''); }} style={{ ...inputStyle, flex: '1 1 240px', width: 'auto' }}>
            <option value="">Choose a module…</option>
            {catalogue.map((m) => <option key={m.code} value={m.code}>{m.name}</option>)}
          </select>
          <select aria-label="Action" value={action} disabled={!mod} onChange={(e) => setAction(e.target.value)} style={{ ...inputStyle, flex: '1 1 160px', width: 'auto' }}>
            <option value="">{mod ? 'Choose an action…' : 'Action'}</option>
            {mod?.actions.map((a) => <option key={a} value={a}>{ACTION_LABEL[a] ?? a}</option>)}
          </select>
        </div>
        {error && <Banner tone="error">{error}</Banner>}
        {loading && <div style={{ padding: 20, textAlign: 'center', color: 'var(--txt-dim)' }}>Looking up…</div>}
        {!loading && !error && holders === null && <div style={{ fontSize: 13, color: 'var(--txt-dim)' }}>Pick a module and action to see who holds it.</div>}
        {!loading && holders && (holders.length === 0
          ? <div style={{ padding: 20, textAlign: 'center', fontSize: 13, color: 'var(--txt-dim)' }}>Nobody holds this permission through an active role.</div>
          : (
            <div style={{ border: '1px solid var(--line)', borderRadius: 10, overflow: 'auto' }}>
              <table style={{ width: '100%', borderCollapse: 'collapse', minWidth: 520 }}>
                <thead><tr><th style={thStyle}>Person</th><th style={thStyle}>Scope</th><th style={thStyle}>Through</th></tr></thead>
                <tbody>
                  {holders.map((h) => (
                    <tr key={h.userId} style={{ opacity: h.active ? 1 : 0.6 }}>
                      <td style={tdStyle}>
                        <div style={{ color: 'var(--txt)', fontWeight: 600 }}>{h.fullName}{!h.active && ' · Inactive'}</div>
                        <div style={{ fontSize: 11.5, color: 'var(--txt-dim)' }}>{h.email}</div>
                      </td>
                      <td style={tdStyle}>{SCOPE_LABEL[h.scope] ?? h.scope}</td>
                      <td style={tdStyle}>{h.roles.join(', ')}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ))}
      </div>
    </Shell>
  );
}

// ─── Role change history ────────────────────────────────────────────────────────
export function RoleHistory({ token, roleId, catalogue }: { token: string; roleId: number; catalogue: CatalogueModule[] }) {
  const [entries, setEntries] = useState<RoleHistoryEntry[] | null>(null);
  const [error, setError] = useState('');
  const names = useMemo(() => moduleNamesOf(catalogue), [catalogue]);

  useEffect(() => {
    let live = true;
    rolesApi.history(roleId, token)
      .then((e) => { if (live) setEntries(e); })
      .catch((e) => { if (live) setError(e instanceof Error ? e.message : 'Could not load the history.'); });
    return () => { live = false; };
  }, [roleId, token]);

  if (error) return <Banner tone="error">{error}</Banner>;
  if (!entries) return <div style={{ padding: 20, textAlign: 'center', color: 'var(--txt-dim)' }}>Loading…</div>;
  if (entries.length === 0) return <div style={{ fontSize: 13, color: 'var(--txt-dim)' }}>No recorded changes for this role yet.</div>;

  const chip = (text: string, sign: '+' | '−') => (
    <span key={`${sign}${text}`} style={{ fontSize: 11.5, borderRadius: 6, padding: '2px 8px', whiteSpace: 'nowrap',
      color: sign === '+' ? '#2FB67C' : 'var(--risk)', background: sign === '+' ? 'rgba(47,182,124,.1)' : 'color-mix(in srgb, var(--risk) 10%, transparent)' }}>
      {sign} {describeAuthority(text, names, ACTION_LABEL)}
    </span>
  );

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
      {entries.map((e) => (
        <div key={e.id} style={{ border: '1px solid var(--line)', borderRadius: 10, padding: '10px 14px' }}>
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'baseline' }}>
            <span style={{ fontSize: 13, fontWeight: 600, color: 'var(--txt)' }}>{e.summary}</span>
            <span style={{ fontSize: 11.5, color: 'var(--txt-dim)' }}>
              {new Date(e.occurredAt).toLocaleString('en-US', { month: 'short', day: 'numeric', year: 'numeric', hour: 'numeric', minute: '2-digit' })}
              {e.actorName ? ` · by ${e.actorName}` : ''}
            </span>
          </div>
          {(e.added.length > 0 || e.removed.length > 0) && (
            <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', marginTop: 8 }}>
              {e.added.map((a) => chip(a, '+'))}
              {e.removed.map((r) => chip(r, '−'))}
            </div>
          )}
        </div>
      ))}
    </div>
  );
}

// ─── Add several users at once ──────────────────────────────────────────────────
export function BulkAddPanel({ candidates, busy, onSubmit }: { candidates: EmployeeRecord[]; busy: boolean; onSubmit: (userIds: string[]) => void }) {
  const [query, setQuery] = useState('');
  const [picked, setPicked] = useState<Set<string>>(() => new Set());
  const q = query.trim().toLowerCase();
  const shown = candidates.filter((u) => !q || u.fullName.toLowerCase().includes(q) || u.email.toLowerCase().includes(q) || (u.employeeCode ?? '').toLowerCase().includes(q));
  const allShownPicked = shown.length > 0 && shown.every((u) => picked.has(u.userId));
  const toggle = (id: string) => setPicked((p) => { const n = new Set(p); if (n.has(id)) n.delete(id); else n.add(id); return n; });

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 8, border: '1px solid var(--line)', borderRadius: 10, padding: 12 }}>
      <input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Search people…" aria-label="Search people to add" style={{ ...inputStyle, padding: '8px 12px' }} />
      <label style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 12, color: 'var(--txt-mut)', cursor: 'pointer' }}>
        <input type="checkbox" checked={allShownPicked} disabled={shown.length === 0} style={{ accentColor: 'var(--brand)' }}
          onChange={(e) => setPicked((p) => { const n = new Set(p); shown.forEach((u) => e.target.checked ? n.add(u.userId) : n.delete(u.userId)); return n; })} />
        Select all shown ({shown.length})
      </label>
      <div style={{ maxHeight: 220, overflowY: 'auto', border: '1px solid var(--line)', borderRadius: 8 }}>
        {shown.length === 0 && <div style={{ padding: 14, fontSize: 12.5, color: 'var(--txt-dim)', textAlign: 'center' }}>No one to add.</div>}
        {shown.map((u, i) => (
          <label key={u.userId} style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '8px 12px', borderTop: i ? '1px solid var(--line)' : 'none', cursor: 'pointer' }}>
            <input type="checkbox" checked={picked.has(u.userId)} onChange={() => toggle(u.userId)} style={{ accentColor: 'var(--brand)' }} />
            <span style={{ minWidth: 0 }}>
              <span style={{ display: 'block', fontSize: 13, color: 'var(--txt)', fontWeight: 600 }}>{u.fullName}</span>
              <span style={{ display: 'block', fontSize: 11.5, color: 'var(--txt-dim)' }}>{u.employeeCode ? `${u.employeeCode} · ` : ''}{u.email}</span>
            </span>
          </label>
        ))}
      </div>
      <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
        <span style={{ fontSize: 12, color: 'var(--txt-dim)' }}>{picked.size} selected{picked.size > 100 ? ' — at most 100 at a time' : ''}</span>
        <div style={{ flex: 1 }} />
        <button style={{ ...secondaryBtn, padding: '7px 12px', fontSize: 12 }} disabled={picked.size === 0 || busy} onClick={() => setPicked(new Set())}>Clear</button>
        <button style={{ ...primaryBtn, padding: '7px 14px', fontSize: 12.5, opacity: picked.size === 0 || picked.size > 100 || busy ? 0.6 : 1 }}
          disabled={picked.size === 0 || picked.size > 100 || busy}
          onClick={() => { onSubmit([...picked]); setPicked(new Set()); }}>
          Add {picked.size || ''} {picked.size === 1 ? 'user' : 'users'}
        </button>
      </div>
    </div>
  );
}
