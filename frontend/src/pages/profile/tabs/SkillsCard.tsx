import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Plus, Pencil, Trash2, Sparkles, Search, Handshake, Info } from 'lucide-react';
import { useToast } from '../../../context/ToastContext';
import { profileSkillsApi, type SkillEntry } from '../../../api/profile';
import { skillRequestApi, type SkillRequestEntry } from '../../../api/talentMasterData';
import { opportunitiesApi } from '../../../api/opportunities';
import { SectionHeader } from '../shared';
import { SkillModal } from './SkillModal';

const ADD_BTN_STYLE: React.CSSProperties = {
  display: 'flex', alignItems: 'center', gap: 5, padding: '5px 12px',
  background: 'var(--brand)', border: 'none', borderRadius: 6,
  fontSize: 12, fontWeight: 600, color: '#fff', cursor: 'pointer',
};
const ICON_BTN_STYLE: React.CSSProperties = {
  background: 'none', border: '1px solid var(--line2)', borderRadius: 6, padding: 5,
  cursor: 'pointer', color: 'var(--txt-mut)', display: 'flex',
};

const LEVEL_TONE: Record<string, string> = {
  Beginner: 'var(--txt-dim)', Intermediate: 'var(--info)', Advanced: 'var(--brand-bright)', Expert: 'var(--ok)',
};

const REQUEST_STATUS_TONE: Record<string, string> = {
  Pending: 'var(--warn)', Approved: 'var(--ok)', Mapped: 'var(--ok)', Rejected: 'var(--risk)',
};

// A skill list that's gone stale is as unhelpful to HR/managers as an empty one — this is the
// same "keep it current" window used elsewhere in the app for similar nudges (e.g. document
// expiry warnings), not a value with any special significance of its own.
const STALE_AFTER_DAYS = 90;

function daysSince(iso: string): number {
  return (Date.now() - new Date(iso).getTime()) / 86_400_000;
}

function StatTile({ label, value, tone }: { label: string; value: number; tone?: string }) {
  return (
    <div style={{ flex: '1 1 120px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 8, padding: '10px 14px' }}>
      <div style={{ fontSize: 18, fontWeight: 800, color: tone ?? 'var(--txt)', fontVariantNumeric: 'tabular-nums' }}>{value}</div>
      <div style={{ fontSize: 11, color: 'var(--txt-mut)', marginTop: 2 }}>{label}</div>
    </div>
  );
}

// Extracted from GrowthTab.tsx (My Profile → Skills) so the same "Skills & Expertise" card —
// pick a taxonomy skill or request a brand-new one, admin-configurable proficiency levels, track
// request status — can also render on Employee Services → My Skills, without duplicating the
// component. Still backed by the same `profile/skills` + `org/talent/skill-requests` endpoints
// either place it's used.
export function SkillsCard({ token }: { token: string }) {
  const { showToast } = useToast();
  const navigate = useNavigate();
  const [skills, setSkills] = useState<SkillEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [modalTarget, setModalTarget] = useState<SkillEntry | 'new' | null>(null);
  const [requests, setRequests] = useState<SkillRequestEntry[]>([]);
  const [search, setSearch] = useState('');
  // How many currently-open Internal Opportunities ask for a skill this employee already has
  // (matched by name — Opportunities are posted against a separate org-wide skill taxonomy, not
  // this profile skill list, so this is a best-effort name match rather than a shared ID).
  // Silently stays 0 if the request fails or the account can't see any opportunities (the
  // endpoint only ever returns open ones you're eligible to express interest in) — this is a
  // bonus nudge, not something worth its own error state.
  const [matchingOpenOpportunities, setMatchingOpenOpportunities] = useState(0);

  function reload() {
    setLoading(true);
    profileSkillsApi.list(token).then(setSkills).catch(() => {}).finally(() => setLoading(false));
    skillRequestApi.listMine(token).then(setRequests).catch(() => {});
  }
  useEffect(reload, [token]);

  useEffect(() => {
    if (skills.length === 0) { setMatchingOpenOpportunities(0); return; }
    const mine = new Set(skills.map(s => s.skillName.trim().toLowerCase()));
    opportunitiesApi.list(token).then(opportunities => {
      const count = opportunities.filter(o =>
        o.status === 'OPEN' && o.requiredSkills.some(s => mine.has(s.trim().toLowerCase()))
      ).length;
      setMatchingOpenOpportunities(count);
    }).catch(() => setMatchingOpenOpportunities(0));
  }, [token, skills]);

  async function handleDelete(id: string) {
    try {
      await profileSkillsApi.remove(token, id);
      setSkills(prev => prev.filter(s => s.id !== id));
      showToast('success', 'Skill removed');
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Delete failed');
    }
  }

  const pendingCount = useMemo(() => requests.filter(r => r.status === 'Pending').length, [requests]);
  const approvedThisMonth = useMemo(() => {
    const now = new Date();
    return requests.filter(r => {
      if (r.status !== 'Approved' && r.status !== 'Mapped') return false;
      if (!r.reviewedAt) return false;
      const reviewed = new Date(r.reviewedAt);
      return reviewed.getFullYear() === now.getFullYear() && reviewed.getMonth() === now.getMonth();
    }).length;
  }, [requests]);

  const staleReminder = useMemo(() => {
    if (loading) return null;
    if (skills.length === 0) return "You haven't added any skills yet — add one so HR and managers can find you for relevant work.";
    const mostRecentUpdate = Math.max(...skills.map(s => new Date(s.updatedAt).getTime()));
    if (daysSince(new Date(mostRecentUpdate).toISOString()) >= STALE_AFTER_DAYS) {
      return `It's been ${STALE_AFTER_DAYS}+ days since you updated your skills — take a moment to review them.`;
    }
    return null;
  }, [skills, loading]);

  const q = search.trim().toLowerCase();
  const filteredSkills = q ? skills.filter(s => s.skillName.toLowerCase().includes(q)) : skills;

  return (
    <div className="nf-profile-card" style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '20px 24px' }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 14 }}>
        <SectionHeader title="Skills & Expertise" icon={Sparkles} />
        <button onClick={() => setModalTarget('new')} style={ADD_BTN_STYLE}>
          <Plus size={13} /> Add Skill
        </button>
      </div>

      {!loading && (skills.length > 0 || requests.length > 0) && (
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, marginBottom: 14 }}>
          <StatTile label="Skills listed" value={skills.length} />
          <StatTile label="Requests pending" value={pendingCount} tone={pendingCount > 0 ? 'var(--warn)' : undefined} />
          <StatTile label="Approved this month" value={approvedThisMonth} tone={approvedThisMonth > 0 ? 'var(--ok)' : undefined} />
        </div>
      )}

      {staleReminder && (
        <div style={{
          display: 'flex', alignItems: 'flex-start', gap: 8, marginBottom: 14, padding: '10px 12px',
          background: 'color-mix(in srgb, var(--warn) 10%, transparent)', border: '1px solid color-mix(in srgb, var(--warn) 30%, transparent)',
          borderRadius: 8, fontSize: 12.5, color: 'var(--txt-mut)',
        }}>
          <Info size={14} color="var(--warn)" style={{ flexShrink: 0, marginTop: 1 }} />
          <span>{staleReminder}</span>
        </div>
      )}

      {matchingOpenOpportunities > 0 && (
        <button
          onClick={() => navigate('/bench-opportunities')}
          style={{
            display: 'flex', alignItems: 'center', gap: 8, width: '100%', textAlign: 'left',
            marginBottom: 14, padding: '10px 12px', cursor: 'pointer',
            background: 'color-mix(in srgb, var(--brand) 8%, transparent)', border: '1px solid color-mix(in srgb, var(--brand) 25%, transparent)',
            borderRadius: 8, fontSize: 12.5, color: 'var(--txt)',
          }}
        >
          <Handshake size={14} color="var(--brand-bright)" style={{ flexShrink: 0 }} />
          <span style={{ flex: 1 }}>
            <b>{matchingOpenOpportunities}</b> open Internal Opportunit{matchingOpenOpportunities === 1 ? 'y needs' : 'ies need'} a skill you have
          </span>
          <span style={{ color: 'var(--brand-bright)', fontWeight: 600, whiteSpace: 'nowrap' }}>View →</span>
        </button>
      )}

      {skills.length > 4 && (
        <div style={{ position: 'relative', marginBottom: 12 }}>
          <Search size={13} style={{ position: 'absolute', left: 10, top: '50%', transform: 'translateY(-50%)', color: 'var(--txt-dim)' }} aria-hidden />
          <input
            value={search}
            onChange={e => setSearch(e.target.value)}
            placeholder="Search your skills…"
            style={{
              width: '100%', boxSizing: 'border-box', padding: '7px 10px 7px 30px',
              background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6,
              fontSize: 12.5, color: 'var(--txt)', outline: 'none',
            }}
          />
        </div>
      )}

      {loading ? (
        <div style={{ color: 'var(--txt-mut)', fontSize: 13 }}>Loading…</div>
      ) : skills.length === 0 ? (
        <div style={{ color: 'var(--txt-dim)', fontSize: 13 }}>No skills added yet.</div>
      ) : filteredSkills.length === 0 ? (
        <div style={{ color: 'var(--txt-dim)', fontSize: 13 }}>No skills match "{search}".</div>
      ) : (
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
          {filteredSkills.map(s => (
            <div key={s.id} style={{
              display: 'flex', alignItems: 'center', gap: 7, padding: '6px 6px 6px 12px',
              background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 20,
            }}>
              <span style={{ fontSize: 12.5, fontWeight: 600, color: 'var(--txt)' }}>{s.skillName}</span>
              <span style={{
                fontSize: 9.5, fontWeight: 700, padding: '2px 7px', borderRadius: 10,
                // Proficiency levels are admin-configurable (Organization > Talent & Skills >
                // Settings) as of the picker rework, so a custom-renamed level falls back to a
                // neutral tone here rather than rendering `undefined` into the color-mix().
                background: `color-mix(in srgb, ${LEVEL_TONE[s.proficiencyLevel] ?? 'var(--txt-dim)'} 16%, transparent)`,
                color: LEVEL_TONE[s.proficiencyLevel] ?? 'var(--txt-dim)',
              }}>
                {s.proficiencyLevel}
              </span>
              <button onClick={() => setModalTarget(s)} aria-label="Edit skill" style={ICON_BTN_STYLE}><Pencil size={11} /></button>
              <button onClick={() => handleDelete(s.id)} aria-label="Delete skill" style={{ ...ICON_BTN_STYLE, color: 'var(--risk)' }}><Trash2 size={11} /></button>
            </div>
          ))}
        </div>
      )}
      {requests.length > 0 && (
        <div style={{ marginTop: 16, paddingTop: 14, borderTop: '1px solid var(--line)' }}>
          <div style={{ fontSize: 11, fontWeight: 600, color: 'var(--txt-mut)', textTransform: 'uppercase', letterSpacing: '.05em', marginBottom: 8 }}>
            My Skill Requests
          </div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
            {requests.map(r => (
              <div key={r.id} style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 12.5 }}>
                <span style={{ color: 'var(--txt)', fontWeight: 600 }}>{r.name}</span>
                <span style={{
                  fontSize: 9.5, fontWeight: 700, padding: '2px 7px', borderRadius: 10,
                  background: `color-mix(in srgb, ${REQUEST_STATUS_TONE[r.status]} 16%, transparent)`,
                  color: REQUEST_STATUS_TONE[r.status],
                }}>
                  {r.status}
                </span>
                {r.mappedSkillName && r.mappedSkillName !== r.name && (
                  <span style={{ color: 'var(--txt-dim)' }}>→ added as "{r.mappedSkillName}"</span>
                )}
              </div>
            ))}
          </div>
        </div>
      )}
      {modalTarget && (
        <SkillModal
          token={token}
          existing={modalTarget === 'new' ? null : modalTarget}
          onClose={() => setModalTarget(null)}
          onSaved={saved => {
            setSkills(prev => {
              const idx = prev.findIndex(s => s.id === saved.id);
              return idx >= 0 ? prev.map((s, i) => i === idx ? saved : s) : [...prev, saved];
            });
          }}
          onRequestSubmitted={() => skillRequestApi.listMine(token).then(setRequests).catch(() => {})}
        />
      )}
    </div>
  );
}
