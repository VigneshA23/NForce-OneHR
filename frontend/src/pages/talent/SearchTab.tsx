import { useEffect, useMemo, useState } from 'react';
import { Search, X, Award, Bookmark, Trash2, Sparkles } from 'lucide-react';
import { PersonAvatar } from '../../components/PersonCell';
import { useAuthStore } from '../../store/authStore';
import { useToast } from '../../context/ToastContext';
import { skillMasterApi, proficiencyLevelApi, type SkillMasterOption, type ProficiencyLevelOption } from '../../api/talentMasterData';
import {
  talentSearchApi, type TalentSearchRequest, type TalentSearchResultItem, type SavedTalentSearch,
} from '../../api/talentOrg';

const inputStyle: React.CSSProperties = { padding: '10px 14px', borderRadius: 12, border: '1px solid var(--line2)', background: 'var(--panel)', color: 'var(--txt)', fontSize: 13.5, fontFamily: 'inherit', outline: 'none' };
const card: React.CSSProperties = { background: 'linear-gradient(180deg, color-mix(in srgb, var(--brand) 4%, var(--panel)) 0%, var(--panel) 110px)', border: '1px solid var(--line)', borderRadius: 16, boxShadow: '0 6px 22px rgba(16, 24, 40, .07)' };

function SkillPill({ name, onRemove }: { name: string; onRemove: () => void }) {
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5, fontSize: 12.5, fontWeight: 700, padding: '5px 8px 5px 12px', borderRadius: 999, background: 'color-mix(in srgb, var(--brand) 11%, var(--panel))', border: '1px solid color-mix(in srgb, var(--brand) 28%, transparent)', color: 'var(--brand)' }}>
      {name}
      <button onClick={onRemove} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--brand)', display: 'flex', padding: 2 }}><X size={12} /></button>
    </span>
  );
}

export function SearchTab() {
  const token = useAuthStore(s => s.token)!;
  const { showToast } = useToast();

  const [skills, setSkills] = useState<SkillMasterOption[]>([]);
  const [levels, setLevels] = useState<ProficiencyLevelOption[]>([]);
  const [saved, setSaved] = useState<SavedTalentSearch[]>([]);

  const [skillQuery, setSkillQuery] = useState('');
  const [selectedSkillIds, setSelectedSkillIds] = useState<string[]>([]);
  const [matchMode, setMatchMode] = useState<'ALL' | 'ANY'>('ANY');
  const [minLevelId, setMinLevelId] = useState('');
  const [certified, setCertified] = useState<'any' | 'yes' | 'no'>('any');
  const [department, setDepartment] = useState('');
  const [location, setLocation] = useState('');
  const [managerName, setManagerName] = useState('');
  const [learningStatus, setLearningStatus] = useState('');

  const [results, setResults] = useState<TalentSearchResultItem[] | null>(null);
  const [searching, setSearching] = useState(false);
  const [saveName, setSaveName] = useState('');

  useEffect(() => {
    Promise.all([skillMasterApi.listActive(token), proficiencyLevelApi.listActive(token), talentSearchApi.listSaved(token)])
      .then(([s, l, sv]) => { setSkills(s); setLevels(l.sort((a, b) => a.sortOrder - b.sortOrder)); setSaved(sv); })
      .catch(e => showToast('error', e instanceof Error ? e.message : 'Could not load search filters'));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token]);

  const skillOptions = useMemo(() => {
    const q = skillQuery.trim().toLowerCase();
    return skills.filter(s => !selectedSkillIds.includes(s.id) && (!q || s.name.toLowerCase().includes(q))).slice(0, 8);
  }, [skills, skillQuery, selectedSkillIds]);

  // Typed text that doesn't resolve to an existing skill must never be silently ignored — Search
  // running "unfiltered" while that text still sits in the box looks like it searched for it.
  const hasUnresolvedSkillText = skillQuery.trim().length > 0 && skillOptions.length === 0;

  function buildRequest(): TalentSearchRequest {
    return {
      skillIds: selectedSkillIds.length ? selectedSkillIds : undefined,
      matchMode,
      minimumProficiencyLevelId: minLevelId || undefined,
      certified: certified === 'any' ? null : certified === 'yes',
      department: department.trim() || undefined,
      location: location.trim() || undefined,
      managerName: managerName.trim() || undefined,
      learningStatus: learningStatus || undefined,
      sortBy: 'BEST_MATCH',
    };
  }

  async function runSearch(req?: TalentSearchRequest) {
    setSearching(true);
    try {
      const res = await talentSearchApi.search(token, req ?? buildRequest());
      setResults(res.items);
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Search failed');
    } finally {
      setSearching(false);
    }
  }

  async function saveCurrentSearch() {
    if (!saveName.trim()) return;
    try {
      const s = await talentSearchApi.save(token, saveName.trim(), buildRequest());
      setSaved(prev => [s, ...prev]);
      setSaveName('');
      showToast('success', 'Search saved');
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Could not save search');
    }
  }

  async function deleteSaved(id: string) {
    try {
      await talentSearchApi.deleteSaved(token, id);
      setSaved(prev => prev.filter(s => s.id !== id));
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Could not delete saved search');
    }
  }

  function loadSaved(s: SavedTalentSearch) {
    setSelectedSkillIds(s.query.skillIds ?? []);
    setMatchMode(s.query.matchMode ?? 'ANY');
    setMinLevelId(s.query.minimumProficiencyLevelId ?? '');
    setCertified(s.query.certified === true ? 'yes' : s.query.certified === false ? 'no' : 'any');
    setDepartment(s.query.department ?? '');
    setLocation(s.query.location ?? '');
    setManagerName(s.query.managerName ?? '');
    setLearningStatus(s.query.learningStatus ?? '');
    runSearch(s.query);
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <div style={{ ...card, padding: 20, display: 'flex', flexDirection: 'column', gap: 16 }}>
        <div className="nf-opp-head" style={{ marginBottom: 0 }}>
          <span className="nf-opp-ico"><Sparkles size={18} /></span>
          <div>
            <div style={{ fontSize: 16, fontWeight: 800, letterSpacing: '-.01em', color: 'var(--txt)' }}>Find talent</div>
            <div style={{ fontSize: 12, color: 'var(--txt-mut)', marginTop: 3 }}>Search by skill, proficiency, certification and more.</div>
          </div>
        </div>
        <div>
          <div style={{ fontSize: 11, fontWeight: 800, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.08em', marginBottom: 8 }}>Skills</div>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6, marginBottom: 8 }}>
            {selectedSkillIds.map(id => {
              const s = skills.find(x => x.id === id);
              return s ? <SkillPill key={id} name={s.name} onRemove={() => setSelectedSkillIds(prev => prev.filter(x => x !== id))} /> : null;
            })}
          </div>
          <div style={{ position: 'relative', maxWidth: 380 }}>
            <input value={skillQuery} onChange={e => setSkillQuery(e.target.value)} placeholder="Search skills to add…" style={{ ...inputStyle, width: '100%' }} />
            {skillQuery.trim() && skillOptions.length > 0 && (
              <div style={{ position: 'absolute', top: '100%', left: 0, right: 0, marginTop: 4, background: 'var(--panel)', border: '1px solid var(--line2)', borderRadius: 12, zIndex: 10, overflow: 'hidden', boxShadow: '0 12px 28px rgba(16,24,40,.16)' }}>
                {skillOptions.map(s => (
                  <button key={s.id} onClick={() => { setSelectedSkillIds(prev => [...prev, s.id]); setSkillQuery(''); }}
                    style={{ display: 'block', width: '100%', textAlign: 'left', padding: '7px 10px', background: 'none', border: 'none', cursor: 'pointer', fontSize: 12.5, color: 'var(--txt)' }}>
                    {s.name}
                  </button>
                ))}
              </div>
            )}
          </div>
          {hasUnresolvedSkillText && (
            <div style={{ fontSize: 11.5, color: 'var(--txt-dim)', marginTop: 5 }}>
              No matching skill found. Skills are managed in Settings - clear this to search, or pick from an existing skill.
            </div>
          )}
          {selectedSkillIds.length > 1 && (
            <div style={{ display: 'flex', gap: 10, marginTop: 10, fontSize: 12 }}>
              <label style={{ display: 'flex', alignItems: 'center', gap: 5, color: 'var(--txt-mut)' }}>
                <input type="radio" checked={matchMode === 'ANY'} onChange={() => setMatchMode('ANY')} /> Any of these
              </label>
              <label style={{ display: 'flex', alignItems: 'center', gap: 5, color: 'var(--txt-mut)' }}>
                <input type="radio" checked={matchMode === 'ALL'} onChange={() => setMatchMode('ALL')} /> All of these
              </label>
            </div>
          )}
        </div>

        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 10 }}>
          <select value={minLevelId} onChange={e => setMinLevelId(e.target.value)} style={inputStyle}>
            <option value="">Any proficiency level</option>
            {levels.map(l => <option key={l.id} value={l.id}>Min: {l.name}</option>)}
          </select>
          <select value={certified} onChange={e => setCertified(e.target.value as 'any' | 'yes' | 'no')} style={inputStyle}>
            <option value="any">Certified: any</option>
            <option value="yes">Certified: yes</option>
            <option value="no">Certified: no</option>
          </select>
          <input value={department} onChange={e => setDepartment(e.target.value)} placeholder="Department" style={inputStyle} />
          <input value={location} onChange={e => setLocation(e.target.value)} placeholder="Location" style={inputStyle} />
          <input value={managerName} onChange={e => setManagerName(e.target.value)} placeholder="Manager name" style={inputStyle} />
          <select value={learningStatus} onChange={e => setLearningStatus(e.target.value)} style={inputStyle}>
            <option value="">Learning status: any</option>
            <option value="Not Started">Not Started</option>
            <option value="In Progress">In Progress</option>
            <option value="Completed">Completed</option>
          </select>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <button onClick={() => runSearch()} disabled={searching || hasUnresolvedSkillText}
            title={hasUnresolvedSkillText ? 'Clear the unmatched skill text first' : undefined} style={{
            display: 'flex', alignItems: 'center', gap: 7, padding: '10px 22px', background: 'linear-gradient(135deg, var(--brand), color-mix(in srgb, var(--brand) 68%, #000))', border: 'none',
            boxShadow: '0 6px 14px color-mix(in srgb, var(--brand) 28%, transparent)',
            borderRadius: 12, color: '#fff', fontSize: 13.5, fontWeight: 700,
            cursor: searching || hasUnresolvedSkillText ? 'default' : 'pointer', opacity: searching || hasUnresolvedSkillText ? 0.6 : 1,
          }}>
            <Search size={13} /> {searching ? 'Searching…' : 'Search'}
          </button>
          <input value={saveName} onChange={e => setSaveName(e.target.value)} placeholder="Name this search…" style={{ ...inputStyle, width: 210 }} />
          <button onClick={saveCurrentSearch} disabled={!saveName.trim() || hasUnresolvedSkillText} style={{
            display: 'flex', alignItems: 'center', gap: 6, padding: '10px 16px', background: 'var(--raised)', border: '1px solid var(--line2)',
            borderRadius: 12, color: 'var(--txt)', fontSize: 13.5, fontWeight: 700,
            cursor: saveName.trim() && !hasUnresolvedSkillText ? 'pointer' : 'default', opacity: saveName.trim() && !hasUnresolvedSkillText ? 1 : 0.5,
          }}>
            <Bookmark size={13} /> Save
          </button>
        </div>

        {saved.length > 0 && (
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, paddingTop: 8, borderTop: '1px solid var(--line)' }}>
            {saved.map(s => (
              <span key={s.id} style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 12.5, padding: '5px 8px 5px 12px', borderRadius: 999, background: 'color-mix(in srgb, var(--brand) 6%, var(--raised))', border: '1px solid var(--line2)' }}>
                <button onClick={() => loadSaved(s)} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt)', fontWeight: 600 }}>{s.name}</button>
                <button onClick={() => deleteSaved(s.id)} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--txt-dim)', display: 'flex', padding: 2 }}><Trash2 size={11} /></button>
              </span>
            ))}
          </div>
        )}
      </div>

      {results !== null && (
        <div style={{ ...card, overflow: 'hidden' }}>
          <div style={{ padding: '14px 20px', borderBottom: '1px solid var(--line)', background: 'linear-gradient(90deg, color-mix(in srgb, var(--brand) 9%, var(--panel)), var(--panel))', fontSize: 15, fontWeight: 800, color: 'var(--txt)' }}>
            {results.length} matching employee{results.length === 1 ? '' : 's'}
          </div>
          {results.length === 0 ? (
            <div style={{ padding: 30, textAlign: 'center', color: 'var(--txt-dim)', fontSize: 13 }}>No employees match these filters.</div>
          ) : (
            results.map((r, i) => (
              <div key={r.employeeUserId} className="nf-dir-row" style={{ cursor: 'default', borderTop: i === 0 ? 'none' : '1px solid var(--line)' }}>
                <PersonAvatar userId={r.employeeUserId} name={r.employeeName} size={40} />
                <div style={{ flex: '0 0 220px', minWidth: 0 }}>
                  <div style={{ fontSize: 14, fontWeight: 700, color: 'var(--txt)' }}>{r.employeeName}</div>
                  <div style={{ fontSize: 11, color: 'var(--txt-dim)' }}>{r.employeeCode} · {r.departmentName ?? '–'}{r.managerName ? ` · reports to ${r.managerName}` : ''}</div>
                </div>
                <div style={{ flex: 1, display: 'flex', flexWrap: 'wrap', gap: 5 }}>
                  {r.matchingSkills.map((s, j) => (
                    <span key={j} style={{ fontSize: 11, fontWeight: 700, padding: '3px 10px', borderRadius: 999, background: 'color-mix(in srgb, var(--brand) 10%, var(--panel))', border: '1px solid color-mix(in srgb, var(--brand) 26%, transparent)', color: 'var(--brand)' }}>
                      {s.skillName} · {s.proficiencyLevel}
                    </span>
                  ))}
                </div>
                <div style={{ flex: '0 0 70px', textAlign: 'right', fontSize: 11.5, color: 'var(--txt-mut)' }}>
                  {r.certificates.length > 0 && <div className="nf-cert-badge"><Award size={12} /> {r.certificates.length}</div>}
                </div>
              </div>
            ))
          )}
        </div>
      )}
    </div>
  );
}
