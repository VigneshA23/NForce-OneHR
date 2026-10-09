import { useEffect, useState } from 'react';
import { Plus, Check, X, RotateCcw, ScanLine } from 'lucide-react';
import { useAuthStore } from '../../store/authStore';
import { useToast } from '../../context/ToastContext';
import type { SkillMasterOption } from '../../api/talentMasterData';
import {
  skillSettingsApi, proficiencyLevelSettingsApi, certificateCategorySettingsApi,
  skillRequestSettingsApi, migrationReviewApi,
  type SkillMasterSettingsEntry, type ProficiencyLevelSettingsEntry, type CertificateCategorySettingsEntry,
  type SkillRequestQueueEntry, type SkillMigrationCandidate, type MigrationScanSummary,
} from '../../api/talentOrg';

const card: React.CSSProperties = { background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, overflow: 'hidden' };
const thS: React.CSSProperties = { padding: '9px 14px', textAlign: 'left', fontSize: 11, fontWeight: 700, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.06em', borderBottom: '1px solid var(--line)', background: 'var(--raised)' };
const tdS: React.CSSProperties = { padding: '10px 14px', fontSize: 12.5, color: 'var(--txt-mut)', borderBottom: '1px solid var(--line)', verticalAlign: 'middle' };
const inputStyle: React.CSSProperties = { padding: '7px 10px', borderRadius: 6, border: '1px solid var(--line2)', background: 'var(--shell)', color: 'var(--txt)', fontSize: 12.5, fontFamily: 'inherit' };
const btnPrimary: React.CSSProperties = { display: 'flex', alignItems: 'center', gap: 6, padding: '7px 12px', background: '#B11116', border: 'none', borderRadius: 7, color: '#fff', fontSize: 12, fontWeight: 700, cursor: 'pointer' };
const btnGhost: React.CSSProperties = { display: 'flex', alignItems: 'center', gap: 5, padding: '5px 9px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, color: 'var(--txt)', fontSize: 11.5, fontWeight: 600, cursor: 'pointer' };

function Pill({ active }: { active: boolean }) {
  return (
    <span style={{ fontSize: 10.5, fontWeight: 700, padding: '2px 8px', borderRadius: 20, color: active ? '#2FB67C' : 'var(--txt-dim)', background: active ? 'rgba(47,182,124,.15)' : 'var(--raised2)' }}>
      {active ? 'Active' : 'Inactive'}
    </span>
  );
}

function SubSection({ title, action, children }: { title: string; action?: React.ReactNode; children: React.ReactNode }) {
  return (
    <div style={card}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '12px 16px', borderBottom: '1px solid var(--line)', background: 'var(--raised)' }}>
        <h3 style={{ fontSize: 13, flex: 1, color: 'var(--txt)', margin: 0, fontFamily: 'Inter, sans-serif' }}>{title}</h3>
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
      <div style={{ display: 'flex', gap: 8, padding: 14, borderBottom: '1px solid var(--line)', flexWrap: 'wrap' }}>
        <input value={name} onChange={e => setName(e.target.value)} placeholder="New skill name" style={inputStyle} />
        <input value={category} onChange={e => setCategory(e.target.value)} placeholder="Category (optional)" style={inputStyle} />
        <button onClick={create} disabled={!name.trim()} style={{ ...btnPrimary, opacity: name.trim() ? 1 : 0.5 }}><Plus size={13} /> Add</button>
      </div>
      <table style={{ width: '100%', borderCollapse: 'collapse' }}>
        <thead><tr>{['Name', 'Category', 'Aliases', 'In use', 'Status', ''].map(h => <th key={h} style={thS}>{h}</th>)}</tr></thead>
        <tbody>
          {loading ? (
            <tr><td colSpan={6} style={{ ...tdS, textAlign: 'center' }}>Loading…</td></tr>
          ) : skills.length === 0 ? (
            <tr><td colSpan={6} style={{ ...tdS, textAlign: 'center' }}>No skills yet.</td></tr>
          ) : skills.map(s => (
            <tr key={s.id}>
              <td style={{ ...tdS, color: 'var(--txt)', fontWeight: 600 }}>{s.name}</td>
              <td style={tdS}>{s.category ?? '—'}</td>
              <td style={tdS}>{s.aliases.length > 0 ? s.aliases.join(', ') : '—'}</td>
              <td style={tdS}>{s.usageCount}</td>
              <td style={tdS}><Pill active={s.active} /></td>
              <td style={{ ...tdS, textAlign: 'right' }}>
                <button onClick={() => toggle(s)} style={btnGhost}>
                  {s.active ? <><X size={11} /> Deactivate</> : <><RotateCcw size={11} /> Reactivate</>}
                </button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
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
    } catch (e) { showToast('error', e instanceof Error ? e.message : (l.active ? 'Could not deactivate — it may still be in use' : 'Could not reactivate level')); }
  }

  return (
    <SubSection title="Proficiency levels">
      <div style={{ display: 'flex', gap: 8, padding: 14, borderBottom: '1px solid var(--line)', flexWrap: 'wrap' }}>
        <input value={name} onChange={e => setName(e.target.value)} placeholder="New level name" style={inputStyle} />
        <button onClick={create} disabled={!name.trim()} style={{ ...btnPrimary, opacity: name.trim() ? 1 : 0.5 }}><Plus size={13} /> Add</button>
      </div>
      <table style={{ width: '100%', borderCollapse: 'collapse' }}>
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
                <button onClick={() => toggle(l)} style={btnGhost}>
                  {l.active ? <><X size={11} /> Deactivate</> : <><RotateCcw size={11} /> Reactivate</>}
                </button>
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

  async function toggle(c: CertificateCategorySettingsEntry) {
    try {
      await (c.active ? certificateCategorySettingsApi.deactivate(token, c.id) : certificateCategorySettingsApi.reactivate(token, c.id));
      load();
    } catch (e) { showToast('error', e instanceof Error ? e.message : 'Could not update category'); }
  }

  return (
    <SubSection title="Certificate categories">
      <div style={{ display: 'flex', gap: 8, padding: 14, borderBottom: '1px solid var(--line)', flexWrap: 'wrap', alignItems: 'center' }}>
        <input value={name} onChange={e => setName(e.target.value)} placeholder="New category name" style={inputStyle} />
        <label style={{ display: 'flex', alignItems: 'center', gap: 5, fontSize: 12, color: 'var(--txt-mut)' }}>
          <input type="checkbox" checked={hasExpiry} onChange={e => setHasExpiry(e.target.checked)} /> Has expiry
        </label>
        <button onClick={create} disabled={!name.trim()} style={{ ...btnPrimary, opacity: name.trim() ? 1 : 0.5 }}><Plus size={13} /> Add</button>
      </div>
      <table style={{ width: '100%', borderCollapse: 'collapse' }}>
        <thead><tr>{['Name', 'Has Expiry', 'In use', 'Status', ''].map(h => <th key={h} style={thS}>{h}</th>)}</tr></thead>
        <tbody>
          {loading ? (
            <tr><td colSpan={5} style={{ ...tdS, textAlign: 'center' }}>Loading…</td></tr>
          ) : cats.length === 0 ? (
            <tr><td colSpan={5} style={{ ...tdS, textAlign: 'center' }}>No categories yet.</td></tr>
          ) : cats.map(c => (
            <tr key={c.id}>
              <td style={{ ...tdS, color: 'var(--txt)', fontWeight: 600 }}>{c.name}</td>
              <td style={tdS}>{c.hasExpiry ? 'Yes' : 'No'}</td>
              <td style={tdS}>{c.usageCount}</td>
              <td style={tdS}><Pill active={c.active} /></td>
              <td style={{ ...tdS, textAlign: 'right' }}>
                <button onClick={() => toggle(c)} style={btnGhost}>
                  {c.active ? <><X size={11} /> Deactivate</> : <><RotateCcw size={11} /> Reactivate</>}
                </button>
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
    <SubSection title="Pending skill requests" action={<span style={{ fontSize: 11.5, color: 'var(--txt-dim)' }}>{requests.length} pending</span>}>
      <table style={{ width: '100%', borderCollapse: 'collapse' }}>
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
              <td style={tdS}>{[r.category, r.note].filter(Boolean).join(' · ') || '—'}</td>
              <td style={tdS}>
                <select value={mapping[r.id] ?? ''} onChange={e => setMapping(prev => ({ ...prev, [r.id]: e.target.value }))} style={inputStyle}>
                  <option value="">— choose existing skill —</option>
                  {skills.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}
                </select>
              </td>
              <td style={{ ...tdS, whiteSpace: 'nowrap' }}>
                <div style={{ display: 'flex', gap: 6, justifyContent: 'flex-end' }}>
                  <button onClick={() => approve(r.id)} style={btnGhost}><Check size={11} /> Approve new</button>
                  <button onClick={() => mapToExisting(r.id)} disabled={!mapping[r.id]} style={{ ...btnGhost, opacity: mapping[r.id] ? 1 : 0.5 }}>Map</button>
                  <button onClick={() => reject(r.id)} style={btnGhost}><X size={11} /> Reject</button>
                </div>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </SubSection>
  );
}

// ── Migration review ──────────────────────────────────────────────────────────

function MigrationReviewSection({ token }: { token: string }) {
  const { showToast } = useToast();
  const [candidates, setCandidates] = useState<SkillMigrationCandidate[]>([]);
  const [discarded, setDiscarded] = useState<SkillMigrationCandidate[]>([]);
  const [skills, setSkills] = useState<SkillMasterOption[]>([]);
  const [loading, setLoading] = useState(true);
  const [scanning, setScanning] = useState(false);
  const [lastScan, setLastScan] = useState<MigrationScanSummary | null>(null);
  const [mapping, setMapping] = useState<Record<string, string>>({});

  function load() {
    Promise.all([migrationReviewApi.listPending(token), migrationReviewApi.listAll(token), skillSettingsApi.listAll(token)])
      .then(([pending, all, s]) => { setCandidates(pending); setDiscarded(all.filter(c => c.status === 'Discarded')); setSkills(s); })
      .catch(e => showToast('error', e instanceof Error ? e.message : 'Could not load migration candidates'))
      .finally(() => setLoading(false));
  }
  useEffect(load, [token]); // eslint-disable-line react-hooks/exhaustive-deps

  async function reopen(id: string) {
    try { await migrationReviewApi.reopen(token, id); load(); }
    catch (e) { showToast('error', e instanceof Error ? e.message : 'Could not reopen candidate'); }
  }

  async function scan() {
    setScanning(true);
    try {
      const summary = await migrationReviewApi.scan(token);
      setLastScan(summary);
      load();
    } catch (e) { showToast('error', e instanceof Error ? e.message : 'Scan failed'); }
    finally { setScanning(false); }
  }

  async function resolveMap(id: string) {
    const existingSkillId = mapping[id];
    if (!existingSkillId) return;
    try { await migrationReviewApi.resolveMapExisting(token, id, existingSkillId); setCandidates(prev => prev.filter(c => c.id !== id)); }
    catch (e) { showToast('error', e instanceof Error ? e.message : 'Could not resolve candidate'); }
  }
  async function resolveCreate(c: SkillMigrationCandidate) {
    try { await migrationReviewApi.resolveCreateNew(token, c.id); setCandidates(prev => prev.filter(x => x.id !== c.id)); }
    catch (e) { showToast('error', e instanceof Error ? e.message : 'Could not create skill from candidate'); }
  }
  async function resolveDiscard(id: string) {
    try { await migrationReviewApi.resolveDiscard(token, id); load(); }
    catch (e) { showToast('error', e instanceof Error ? e.message : 'Could not discard candidate'); }
  }

  return (
    <SubSection title="Migration review — legacy free-text skills" action={
      <button onClick={scan} disabled={scanning} style={{ ...btnPrimary, opacity: scanning ? 0.7 : 1 }}>
        <ScanLine size={13} /> {scanning ? 'Scanning…' : 'Scan for unmigrated skills'}
      </button>
    }>
      {lastScan && (
        <div style={{ padding: '10px 16px', fontSize: 12, color: 'var(--txt-dim)', borderBottom: '1px solid var(--line)' }}>
          Scanned {lastScan.totalUnmigratedRows} unmigrated rows · {lastScan.candidatesCreated} new candidates · {lastScan.candidatesUpdated} updated · {lastScan.autoMatched} auto-matched
        </div>
      )}
      <table style={{ width: '100%', borderCollapse: 'collapse' }}>
        <thead><tr>{['Raw text', 'Occurrences', 'Proposed match', 'Map to existing', ''].map(h => <th key={h} style={thS}>{h}</th>)}</tr></thead>
        <tbody>
          {loading ? (
            <tr><td colSpan={5} style={{ ...tdS, textAlign: 'center' }}>Loading…</td></tr>
          ) : candidates.length === 0 ? (
            <tr><td colSpan={5} style={{ ...tdS, textAlign: 'center' }}>No pending candidates — run a scan to look for unmigrated entries.</td></tr>
          ) : candidates.map(c => (
            <tr key={c.id}>
              <td style={{ ...tdS, color: 'var(--txt)', fontWeight: 600 }}>{c.rawText}</td>
              <td style={tdS}>{c.occurrenceCount}</td>
              <td style={tdS}>{c.proposedSkillName ?? <span style={{ fontStyle: 'italic' }}>none</span>}</td>
              <td style={tdS}>
                <select value={mapping[c.id] ?? ''} onChange={e => setMapping(prev => ({ ...prev, [c.id]: e.target.value }))} style={inputStyle}>
                  <option value="">— choose existing skill —</option>
                  {skills.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}
                </select>
              </td>
              <td style={{ ...tdS, whiteSpace: 'nowrap' }}>
                <div style={{ display: 'flex', gap: 6, justifyContent: 'flex-end' }}>
                  <button onClick={() => resolveMap(c.id)} disabled={!mapping[c.id]} style={{ ...btnGhost, opacity: mapping[c.id] ? 1 : 0.5 }}>Map</button>
                  <button onClick={() => resolveCreate(c)} style={btnGhost}><Plus size={11} /> Create new</button>
                  <button onClick={() => resolveDiscard(c.id)} style={btnGhost}><RotateCcw size={11} /> Discard</button>
                </div>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {discarded.length > 0 && (
        <>
          <div style={{ padding: '10px 16px', fontSize: 11.5, fontWeight: 700, color: 'var(--txt-dim)', textTransform: 'uppercase', letterSpacing: '.05em', borderTop: '1px solid var(--line)' }}>
            Discarded — can be reopened
          </div>
          <table style={{ width: '100%', borderCollapse: 'collapse' }}>
            <tbody>
              {discarded.map(c => (
                <tr key={c.id}>
                  <td style={{ ...tdS, color: 'var(--txt)', fontWeight: 600 }}>{c.rawText}</td>
                  <td style={tdS}>{c.occurrenceCount} occurrence{c.occurrenceCount === 1 ? '' : 's'}</td>
                  <td style={{ ...tdS, textAlign: 'right' }}>
                    <button onClick={() => reopen(c.id)} style={btnGhost}><RotateCcw size={11} /> Reopen</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
    </SubSection>
  );
}

// ── Page ──────────────────────────────────────────────────────────────────────

type SettingsSubTab = 'skills' | 'levels' | 'categories' | 'requests' | 'migration';

const SUB_TABS: { key: SettingsSubTab; label: string }[] = [
  { key: 'skills', label: 'Skills' },
  { key: 'levels', label: 'Proficiency Levels' },
  { key: 'categories', label: 'Certificate Categories' },
  { key: 'requests', label: 'Skill Requests' },
  { key: 'migration', label: 'Migration Review' },
];

export function SettingsTab() {
  const token = useAuthStore(s => s.token)!;
  const [sub, setSub] = useState<SettingsSubTab>('skills');

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
        {SUB_TABS.map(t => (
          <button key={t.key} onClick={() => setSub(t.key)} style={{
            padding: '6px 12px', borderRadius: 20, fontSize: 12, fontWeight: 600, cursor: 'pointer',
            border: sub === t.key ? '1px solid #B11116' : '1px solid var(--line2)',
            background: sub === t.key ? 'rgba(177,17,22,.1)' : 'var(--shell)',
            color: sub === t.key ? '#B11116' : 'var(--txt-mut)',
          }}>
            {t.label}
          </button>
        ))}
      </div>

      {sub === 'skills' && <SkillsSection token={token} />}
      {sub === 'levels' && <ProficiencyLevelsSection token={token} />}
      {sub === 'categories' && <CertificateCategoriesSection token={token} />}
      {sub === 'requests' && <SkillRequestsSection token={token} />}
      {sub === 'migration' && <MigrationReviewSection token={token} />}
    </div>
  );
}
