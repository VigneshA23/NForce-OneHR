import { useEffect, useState } from 'react';
import { Plus, Pencil, Trash2, Sparkles } from 'lucide-react';
import { useToast } from '../../../context/ToastContext';
import { profileSkillsApi, type SkillEntry } from '../../../api/profile';
import { skillRequestApi, type SkillRequestEntry } from '../../../api/talentMasterData';
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

// Extracted from GrowthTab.tsx (My Profile → Skills) so the same "Skills & Expertise" card —
// pick a taxonomy skill or request a brand-new one, admin-configurable proficiency levels, track
// request status — can also render on Employee Services → My Skills, without duplicating the
// component. Still backed by the same `profile/skills` + `org/talent/skill-requests` endpoints
// either place it's used.
export function SkillsCard({ token }: { token: string }) {
  const { showToast } = useToast();
  const [skills, setSkills] = useState<SkillEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [modalTarget, setModalTarget] = useState<SkillEntry | 'new' | null>(null);
  const [requests, setRequests] = useState<SkillRequestEntry[]>([]);

  function reload() {
    setLoading(true);
    profileSkillsApi.list(token).then(setSkills).catch(() => {}).finally(() => setLoading(false));
    skillRequestApi.listMine(token).then(setRequests).catch(() => {});
  }
  useEffect(reload, [token]);

  async function handleDelete(id: string) {
    try {
      await profileSkillsApi.remove(token, id);
      setSkills(prev => prev.filter(s => s.id !== id));
      showToast('success', 'Skill removed');
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Delete failed');
    }
  }

  return (
    <div className="nf-profile-card" style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '20px 24px' }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 18 }}>
        <SectionHeader title="Skills & Expertise" icon={Sparkles} />
        <button onClick={() => setModalTarget('new')} style={ADD_BTN_STYLE}>
          <Plus size={13} /> Add Skill
        </button>
      </div>
      {loading ? (
        <div style={{ color: 'var(--txt-mut)', fontSize: 13 }}>Loading…</div>
      ) : skills.length === 0 ? (
        <div style={{ color: 'var(--txt-dim)', fontSize: 13 }}>No skills added yet.</div>
      ) : (
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
          {skills.map(s => (
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
