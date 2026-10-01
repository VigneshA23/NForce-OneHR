import { useEffect, useState } from 'react';
import { Plus, Pencil, Trash2, Sparkles, BookOpen } from 'lucide-react';
import { useToast } from '../../../context/ToastContext';
import { profileSkillsApi, profileLearningApi, type SkillEntry, type LearningEntry } from '../../../api/profile';
import { SectionHeader } from '../shared';
import { SkillModal } from './SkillModal';
import { LearningEntryModal } from './LearningEntryModal';

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
  Beginner: 'var(--txt-dim)', Intermediate: 'var(--info)', Expert: 'var(--ok)',
};

function SkillsCard({ token }: { token: string }) {
  const { showToast } = useToast();
  const [skills, setSkills] = useState<SkillEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [modalTarget, setModalTarget] = useState<SkillEntry | 'new' | null>(null);

  function reload() {
    setLoading(true);
    profileSkillsApi.list(token).then(setSkills).catch(() => {}).finally(() => setLoading(false));
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
                background: `color-mix(in srgb, ${LEVEL_TONE[s.proficiencyLevel]} 16%, transparent)`,
                color: LEVEL_TONE[s.proficiencyLevel],
              }}>
                {s.proficiencyLevel}
              </span>
              <button onClick={() => setModalTarget(s)} aria-label="Edit skill" style={ICON_BTN_STYLE}><Pencil size={11} /></button>
              <button onClick={() => handleDelete(s.id)} aria-label="Delete skill" style={{ ...ICON_BTN_STYLE, color: 'var(--risk)' }}><Trash2 size={11} /></button>
            </div>
          ))}
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
        />
      )}
    </div>
  );
}

function LearningCard({ token }: { token: string }) {
  const { showToast } = useToast();
  const [entries, setEntries] = useState<LearningEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [modalTarget, setModalTarget] = useState<LearningEntry | 'new' | null>(null);

  function reload() {
    setLoading(true);
    profileLearningApi.list(token).then(setEntries).catch(() => {}).finally(() => setLoading(false));
  }
  useEffect(reload, [token]);

  async function handleDelete(id: string) {
    try {
      await profileLearningApi.remove(token, id);
      setEntries(prev => prev.filter(e => e.id !== id));
      showToast('success', 'Entry removed');
    } catch (e) {
      showToast('error', e instanceof Error ? e.message : 'Delete failed');
    }
  }

  return (
    <div className="nf-profile-card" style={{ background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, padding: '20px 24px' }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 18 }}>
        <SectionHeader title="What I Learned" badge="Last 6–12 months" icon={BookOpen} />
        <button onClick={() => setModalTarget('new')} style={ADD_BTN_STYLE}>
          <Plus size={13} /> Add Entry
        </button>
      </div>
      {loading ? (
        <div style={{ color: 'var(--txt-mut)', fontSize: 13 }}>Loading…</div>
      ) : entries.length === 0 ? (
        <div style={{ color: 'var(--txt-dim)', fontSize: 13 }}>
          No entries yet — add a course, certification, project, or new skill you picked up.
        </div>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
          {entries.map(e => (
            <div key={e.id} style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 10, padding: '12px 14px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 8 }}>
              <div style={{ minWidth: 0 }}>
                <div style={{ fontSize: 13.5, fontWeight: 600, color: 'var(--txt)' }}>{e.title}</div>
                {e.description && <div style={{ fontSize: 12.5, color: 'var(--txt-mut)', marginTop: 2 }}>{e.description}</div>}
                <div style={{ fontSize: 11, color: 'var(--txt-dim)', marginTop: 2 }}>
                  {new Date(e.entryDate).toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' })}
                </div>
              </div>
              <div style={{ display: 'flex', gap: 6, flexShrink: 0 }}>
                <button onClick={() => setModalTarget(e)} aria-label="Edit entry" style={ICON_BTN_STYLE}><Pencil size={13} /></button>
                <button onClick={() => handleDelete(e.id)} aria-label="Delete entry" style={{ ...ICON_BTN_STYLE, color: 'var(--risk)' }}><Trash2 size={13} /></button>
              </div>
            </div>
          ))}
        </div>
      )}
      {modalTarget && (
        <LearningEntryModal
          token={token}
          existing={modalTarget === 'new' ? null : modalTarget}
          onClose={() => setModalTarget(null)}
          onSaved={saved => {
            setEntries(prev => {
              const idx = prev.findIndex(e => e.id === saved.id);
              return idx >= 0 ? prev.map((e, i) => i === idx ? saved : e) : [saved, ...prev];
            });
          }}
        />
      )}
    </div>
  );
}

// Employee self-service only (ONEHR profile growth tracking, phase 1). Manager/lead visibility
// into Skills & Expertise / What I Learned is an explicit later phase, not implemented here.
export function GrowthTab({ token }: { token: string }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
      <SkillsCard token={token} />
      <LearningCard token={token} />
    </div>
  );
}
