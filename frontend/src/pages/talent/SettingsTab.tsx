import { useEffect, useState } from 'react';
import { usePaged } from '../../components/usePaged';
import { TablePagination } from '../../components/TablePagination';
import { ActionDock } from '../../components/ActionDock';
import { Plus, Check, X, Pencil, Power, PowerOff } from 'lucide-react';
import { useAuthStore } from '../../store/authStore';
import { useToast } from '../../context/ToastContext';
import type { SkillMasterOption } from '../../api/talentMasterData';
import {
  skillSettingsApi, proficiencyLevelSettingsApi, certificateCategorySettingsApi,
  skillRequestSettingsApi,
  type SkillMasterSettingsEntry, type ProficiencyLevelSettingsEntry, type CertificateCategorySettingsEntry,
  type SkillRequestQueueEntry,
} from '../../api/talentOrg';

const card: React.CSSProperties = { background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 16, boxShadow: '0 6px 22px rgba(16, 24, 40, .07)', overflow: 'hidden' };
const thS: React.CSSProperties = { padding: '12px 16px', textAlign: 'left', fontSize: 11, fontWeight: 800, color: 'var(--brand)', textTransform: 'uppercase', letterSpacing: '.09em', whiteSpace: 'nowrap', borderBottom: '1px solid color-mix(in srgb, var(--brand) 22%, var(--line))', background: 'color-mix(in srgb, var(--brand) 7%, var(--raised))' };
const tdS: React.CSSProperties = { padding: '13px 16px', fontSize: 13.5, color: 'var(--txt-mut)', borderBottom: '1px solid var(--line)', verticalAlign: 'middle' };
const inputStyle: React.CSSProperties = { padding: '10px 14px', borderRadius: 12, border: '1px solid var(--line2)', background: 'var(--panel)', color: 'var(--txt)', fontSize: 13.5, fontFamily: 'inherit', outline: 'none' };
const btnPrimary: React.CSSProperties = { display: 'flex', alignItems: 'center', gap: 6, padding: '10px 18px', background: 'linear-gradient(135deg, var(--brand), color-mix(in srgb, var(--brand) 68%, #000))', boxShadow: '0 6px 14px color-mix(in srgb, var(--brand) 28%, transparent)', border: 'none', borderRadius: 12, color: '#fff', fontSize: 13, fontWeight: 700, cursor: 'pointer' };

function Pill({ active }: { active: boolean }) {
  return <span className={`nf-rep-pill${active ? '' : ' is-off'}`}>{active ? 'Active' : 'Inactive'}</span>;
}

function SubSection({ title, action, children }: { title: string; action?: React.ReactNode; children: React.ReactNode }) {
  return (
    <div style={card}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '14px 18px', borderBottom: '1px solid var(--line)', background: 'linear-gradient(90deg, color-mix(in srgb, var(--brand) 9%, var(--panel)), var(--panel))' }}>
        <h3 style={{ fontSize: 16, fontWeight: 800, letterSpacing: '-.01em', flex: 1, color: 'var(--txt)', margin: 0, fontFamily: 'Inter, sans-serif' }}>{title}</h3>
        {action}
      </div>
      <div style={{ overflowX: 'auto' }}>{children}</div>
    </div>
  );
}

// ── Skills master list ──────────────────────────────────────────────────────

function SkillsSection({ token }: { token: string }) {
  const { showToast } = useToast();
  const [skills, setSkills] = useState<SkillMasterSettingsEntry[]>([]);
  const skillPaged = usePaged(skills);
  const [loading, setLoading] = useState(true);
  const [name, setName] = useState('');
  const [category, setCategory] = useState('');

  function load() {
    skillSettingsApi.listAll(token).then(setSkills).catch(e => showToast('error', e instanceof Error ? e.message : 'Could not load skills')).finally(() => setLoading(false));
  }
  useEffect(load, [token]); // eslint-disable-line react-hooks/exhaustive-deps

  async function create() {
    if (!name.trim()) return;
    try {
      await skillSettingsApi.create(token, { name: name.trim(), category: category.trim() || undefined });
      setName(''); setCategory(''); load();
      showToast('success', 'Skill added');
    } catch (e) { showToast('error', e instanceof Error ? e.message : 'Could not add skill'); }
  }

  async function toggle(s: SkillMasterSettingsEntry) {
    try {
      await (s.active ? skillSettingsApi.deactivate(token, s.id) : skillSettingsApi.reactivate(token, s.id));
      load();
    } catch (e) { showToast('error', e instanceof Error ? e.message : 'Could not update skill'); }
  }

  return (
    <SubSection title="Skills master list">
      <div style={{ display: 'flex', gap: 8, padding: '16px 18px', borderBottom: '1px solid var(--line)', flexWrap: 'wrap' }}>
        <input value={name} onChange={e => setName(e.target.value)} placeholder="New skill name" style={inputStyle} />
        <input value={category} onChange={e => setCategory(e.target.value)} placeholder="Category (optional)" style={inputStyle} />
        <button onClick={create} disabled={!name.trim()} style={{ ...btnPrimary, opacity: name.trim() ? 1 : 0.5 }}><Plus size={13} /> Add</button>
      </div>
      <table className="nf-rep-table" style={{ width: '100%', borderCollapse: 'collapse' }}>
        <thead><tr>{['Name', 'Category', 'Aliases', 'In use', 'Status', ''].map(h => <th key={h} style={thS}>{h}</th>)}</tr></thead>
        <tbody>
          {loading ? (
            <tr><td colSpan={6} style={{ ...tdS, textAlign: 'center' }}>Loading…</td></tr>
          ) : skills.length === 0 ? (
            <tr><td colSpan={6} style={{ ...tdS, textAlign: 'center' }}>No skills yet.</td></tr>
          ) : skillPaged.pageRows.map(s => (
            <tr key={s.id}>
              <td style={{ ...tdS, color: 'var(--txt)', fontWeight: 600 }}>{s.name}</td>
              <td style={tdS}>{s.category ?? '–'}</td>
              <td style={tdS}>{s.aliases.length > 0 ? s.aliases.join(', ') : '–'}</td>
              <td style={tdS}>{s.usageCount}</td>
              <td style={tdS}><Pill active={s.active} /></td>
              <td style={{ ...tdS, textAlign: 'right' }}>
                <ActionDock items={[{ label: s.active ? 'Deactivate' : 'Reactivate', icon: s.active ? PowerOff : Power, onClick: () => toggle(s) }]} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <TablePagination {...skillPaged.pagerProps} noun="skills" />
    </SubSection>
  );
}

// ── Proficiency levels ───────────────────────────────────────────────────────

function ProficiencyLevelsSection({ token }: { token: string }) {
  const { showToast } = useToast();
  const [levels, setLevels] = useState<ProficiencyLevelSettingsEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [name, setName] = useState('');

  function load() {
    proficiencyLevelSettingsApi.listAll(token).then(ls => setLevels(ls.sort((a, b) => a.sortOrder - b.sortOrder)))
      .catch(e => showToast('error', e instanceof Error ? e.message : 'Could not load levels')).finally(() => setLoading(false));
  }
  useEffect(load, [token]); // eslint-disable-line react-hooks/exhaustive-deps

  async function create() {
    if (!name.trim()) return;
    try {
      await proficiencyLevelSettingsApi.create(token, { name: name.trim() });
      setName(''); load();
      showToast('success', 'Level added');
    } catch (e) { showToast('error', e instanceof Error ? e.message : 'Could not add level'); }
  }

  async function toggle(l: ProficiencyLevelSettingsEntry) {
    try {
      await (l.active ? proficiencyLevelSettingsApi.deactivate(token, l.id) : proficiencyLevelSettingsApi.reactivate(token, l.id));
      load();
    } catch (e) { showToast('error', e instanceof Error ? e.message : (l.active ? 'Could not deactivate - it may still be in use' : 'Could not reactivate level')); }
  }

  return (
    <SubSection title="Proficiency levels">
      <div style={{ display: 'flex', gap: 8, padding: '16px 18px', borderBottom: '1px solid var(--line)', flexWrap: 'wrap' }}>
        <input value={name} onChange={e => setName(e.target.value)} placeholder="New level name" style={inputStyle} />
        <button onClick={create} disabled={!name.trim()} style={{ ...btnPrimary, opacity: name.trim() ? 1 : 0.5 }}><Plus size={13} /> Add</button>
      </div>
      <table className="nf-rep-table" style={{ width: '100%', borderCollapse: 'collapse' }}>
        <thead><tr>{['Order', 'Name', 'In use', 'Status', ''].map(h => <th key={h} style={thS}>{h}</th>)}</tr></thead>
        <tbody>
          {loading ? (
            <tr><td colSpan={5} style={{ ...tdS, textAlign: 'center' }}>Loading…</td></tr>
          ) : levels.map(l => (
            <tr key={l.id}>
              <td style={tdS}>{l.sortOrder}</td>
              <td style={{ ...tdS, color: 'var(--txt)', fontWeight: 600 }}>{l.name}</td>
              <td style={tdS}>{l.usageCount}</td>
              <td style={tdS}><Pill active={l.active} /></td>
              <td style={{ ...tdS, textAlign: 'right' }}>
                <ActionDock items={[{ label: l.active ? 'Deactivate' : 'Reactivate', icon: l.active ? PowerOff : Power, onClick: () => toggle(l) }]} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </SubSection>
  );
}

// ── Certificate categories ───────────────────────────────────────────────────

function CertificateCategoriesSection({ token }: { token: string }) {
  const { showToast } = useToast();
  const [cats, setCats] = useState<CertificateCategorySettingsEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [name, setName] = useState('');
  const [hasExpiry, setHasExpiry] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editName, setEditName] = useState('');
  const [editHasExpiry, setEditHasExpiry] = useState(false);
  const [saving, setSaving] = useState(false);

  function load() {
    certificateCategorySettingsApi.listAll(token).then(setCats)
      .catch(e => showToast('error', e instanceof Error ? e.message : 'Could not load categories')).finally(() => setLoading(false));
  }
  useEffect(load, [token]); // eslint-disable-line react-hooks/exhaustive-deps

  async function create() {
    if (!name.trim()) return;
    try {
      await certificateCategorySettingsApi.create(token, { name: name.trim(), hasExpiry });
      setName(''); setHasExpiry(false); load();
      showToast('success', 'Category added');
    } catch (e) { showToast('error', e instanceof Error ? e.message : 'Could not add category'); }
  }

  function startEdit(c: CertificateCategorySettingsEntry) {
    setEditingId(c.id);
    setEditName(c.name);
    setEditHasExpiry(c.hasExpiry);
  }

  function cancelEdit() {
    setEditingId(null);
  }

  async function saveEdit(id: string) {
    if (!editName.trim()) return;
    setSaving(true);
    try {
      await certificateCategorySettingsApi.update(token, id, { name: editName.trim(), hasExpiry: editHasExpiry });
      setEditingId(null);
      load();
      showToast('success', 'Category updated');
    } catch (e) { showToast('error', e instanceof Error ? e.message : 'Could not update category'); }
    finally { setSaving(false); }
  }

  async function toggle(c: CertificateCategorySettingsEntry) {
    try {
      await (c.active ? certificateCategorySettingsApi.deactivate(token, c.id) : certificateCategorySettingsApi.reactivate(token, c.id));
      load();
    } catch (e) { showToast('error', e instanceof Error ? e.message : 'Could not update category'); }
  }

  return (
    <SubSection title="Certificate categories">
      <div style={{ display: 'flex', gap: 8, padding: '16px 18px', borderBottom: '1px solid var(--line)', flexWrap: 'wrap', alignItems: 'center' }}>
        <input value={name} onChange={e => setName(e.target.value)} placeholder="New category name" style={inputStyle} />
        <label style={{ display: 'flex', alignItems: 'center', gap: 5, fontSize: 12, color: 'var(--txt-mut)' }}>
          <input type="checkbox" checked={hasExpiry} onChange={e => setHasExpiry(e.target.checked)} /> Has expiry
        </label>
        <button onClick={create} disabled={!name.trim()} style={{ ...btnPrimary, opacity: name.trim() ? 1 : 0.5 }}><Plus size={13} /> Add</button>
      </div>
      <table className="nf-rep-table" style={{ width: '100%', borderCollapse: 'collapse' }}>
        <thead><tr>{['Name', 'Has Expiry', 'In use', 'Status', ''].map(h => <th key={h} style={thS}>{h}</th>)}</tr></thead>
        <tbody>
          {loading ? (
            <tr><td colSpan={5} style={{ ...tdS, textAlign: 'center' }}>Loading…</td></tr>
          ) : cats.length === 0 ? (
            <tr><td colSpan={5} style={{ ...tdS, textAlign: 'center' }}>No categories yet.</td></tr>
          ) : cats.map(c => editingId === c.id ? (
            <tr key={c.id}>
              <td style={tdS}><input value={editName} onChange={e => setEditName(e.target.value)} style={inputStyle} /></td>
              <td style={tdS}>
                <label style={{ display: 'flex', alignItems: 'center', gap: 5, fontSize: 12, color: 'var(--txt-mut)' }}>
                  <input type="checkbox" checked={editHasExpiry} onChange={e => setEditHasExpiry(e.target.checked)} /> Has expiry
                </label>
              </td>
              <td style={tdS}>{c.usageCount}</td>
              <td style={tdS}><Pill active={c.active} /></td>
              <td style={{ ...tdS, textAlign: 'right' }}>
                <div style={{ display: 'flex', gap: 6, justifyContent: 'flex-end' }}>
                  <button onClick={() => saveEdit(c.id)} disabled={!editName.trim() || saving} className="nf-act-btn" style={{ opacity: !editName.trim() || saving ? 0.6 : 1 }}>
                    <Check size={11} /> Save
                  </button>
                  <button onClick={cancelEdit} className="nf-act-btn"><X size={11} /> Cancel</button>
                </div>
              </td>
            </tr>
          ) : (
            <tr key={c.id}>
              <td style={{ ...tdS, color: 'var(--txt)', fontWeight: 600 }}>{c.name}</td>
              <td style={tdS}>{c.hasExpiry ? 'Yes' : 'No'}</td>
              <td style={tdS}>{c.usageCount}</td>
              <td style={tdS}><Pill active={c.active} /></td>
              <td style={{ ...tdS, textAlign: 'right' }}>
                <ActionDock items={[
                  { label: 'Edit', icon: Pencil, onClick: () => startEdit(c) },
                  { label: c.active ? 'Deactivate' : 'Reactivate', icon: c.active ? PowerOff : Power, onClick: () => toggle(c) },
                ]} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </SubSection>
  );
}

// ── Skill requests queue ─────────────────────────────────────────────────────

function SkillRequestsSection({ token }: { token: string }) {
  const { showToast } = useToast();
  const [requests, setRequests] = useState<SkillRequestQueueEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [mapping, setMapping] = useState<Record<string, string>>({});
  const [skills, setSkills] = useState<SkillMasterOption[]>([]);

  function load() {
    Promise.all([skillRequestSettingsApi.listPending(token), skillSettingsApi.listAll(token)])
      .then(([reqs, all]) => { setRequests(reqs); setSkills(all); })
      .catch(e => showToast('error', e instanceof Error ? e.message : 'Could not load skill requests'))
      .finally(() => setLoading(false));
  }
  useEffect(load, [token]); // eslint-disable-line react-hooks/exhaustive-deps

  async function approve(id: string) {
    try { await skillRequestSettingsApi.approve(token, id); setRequests(prev => prev.filter(r => r.id !== id)); showToast('success', 'Request approved'); }
    catch (e) { showToast('error', e instanceof Error ? e.message : 'Could not approve request'); }
  }
  async function mapToExisting(id: string) {
    const existingSkillId = mapping[id];
    if (!existingSkillId) return;
    try { await skillRequestSettingsApi.mapToExisting(token, id, existingSkillId); setRequests(prev => prev.filter(r => r.id !== id)); showToast('success', 'Request mapped'); }
    catch (e) { showToast('error', e instanceof Error ? e.message : 'Could not map request'); }
  }
  async function reject(id: string) {
    try { await skillRequestSettingsApi.reject(token, id); setRequests(prev => prev.filter(r => r.id !== id)); showToast('success', 'Request rejected'); }
    catch (e) { showToast('error', e instanceof Error ? e.message : 'Could not reject request'); }
  }

  return (
    <SubSection title="Pending skill requests" action={<span className="nf-rep-pill is-warn">{requests.length} pending</span>}>
      <table className="nf-rep-table" style={{ width: '100%', borderCollapse: 'collapse' }}>
        <thead><tr>{['Requested by', 'Skill name', 'Category / note', 'Map to existing', ''].map(h => <th key={h} style={thS}>{h}</th>)}</tr></thead>
        <tbody>
          {loading ? (
            <tr><td colSpan={5} style={{ ...tdS, textAlign: 'center' }}>Loading…</td></tr>
          ) : requests.length === 0 ? (
            <tr><td colSpan={5} style={{ ...tdS, textAlign: 'center' }}>No pending requests.</td></tr>
          ) : requests.map(r => (
            <tr key={r.id}>
              <td style={{ ...tdS, color: 'var(--txt)', fontWeight: 600 }}>{r.requesterName}</td>
              <td style={tdS}>{r.name}</td>
              <td style={tdS}>{[r.category, r.note].filter(Boolean).join(' · ') || '–'}</td>
              <td style={tdS}>
                <select value={mapping[r.id] ?? ''} onChange={e => setMapping(prev => ({ ...prev, [r.id]: e.target.value }))} style={inputStyle}>
                  <option value="">– choose existing skill –</option>
                  {skills.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}
                </select>
              </td>
              <td style={{ ...tdS, whiteSpace: 'nowrap' }}>
                <div style={{ display: 'flex', gap: 6, justifyContent: 'flex-end' }}>
                  <button onClick={() => approve(r.id)} className="nf-act-btn"><Check size={11} /> Approve new</button>
                  <button onClick={() => mapToExisting(r.id)} disabled={!mapping[r.id]} className="nf-act-btn" style={{ opacity: mapping[r.id] ? 1 : 0.5 }}>Map</button>
                  <button onClick={() => reject(r.id)} className="nf-act-btn"><X size={11} /> Reject</button>
                </div>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </SubSection>
  );
}

// ── Page ──────────────────────────────────────────────────────────────────────

export type SettingsSubTab = 'skills' | 'levels' | 'categories' | 'requests';

const SUB_TABS: { key: SettingsSubTab; label: string }[] = [
  { key: 'skills', label: 'Skills' },
  { key: 'levels', label: 'Proficiency Levels' },
  { key: 'categories', label: 'Certificate Categories' },
  { key: 'requests', label: 'Skill Requests' },
];

export function SettingsTab({ initialSub }: { initialSub?: SettingsSubTab } = {}) {
  const token = useAuthStore(s => s.token)!;
  const [sub, setSub] = useState<SettingsSubTab>(initialSub ?? 'skills');

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
        {SUB_TABS.map(t => (
          <button key={t.key} type="button" aria-pressed={sub === t.key} onClick={() => setSub(t.key)} className={`nf-skill-chip${sub === t.key ? ' is-on' : ''}`}>
            {t.label}
          </button>
        ))}
      </div>

      {sub === 'skills' && <SkillsSection token={token} />}
      {sub === 'levels' && <ProficiencyLevelsSection token={token} />}
      {sub === 'categories' && <CertificateCategoriesSection token={token} />}
      {sub === 'requests' && <SkillRequestsSection token={token} />}
    </div>
  );
}
