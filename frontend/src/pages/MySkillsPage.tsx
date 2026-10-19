import { useCallback, useEffect, useState } from 'react';
import PageTitleBlock from '../components/PageTitleBlock';
import { Award, Star } from 'lucide-react';
import { useAuthStore } from '../store/authStore';
import { skillsApi, type Skill, type SkillRating, type TeamSkillRating } from '../api/skills';
import { card, CardTitle, Empty, ErrorBox, ghostBtn, inputStyle, labelStyle, primaryBtn, td, th } from './bench/ui';
import { SkillsCard } from './profile/tabs/SkillsCard';
import { SectionHeader } from './profile/shared';

function Stars({ value, onPick, disabled }: { value: number | null; onPick?: (n: number) => void; disabled?: boolean }) {
  return (
    <span style={{ display: 'inline-flex', gap: 3 }}>
      {[1, 2, 3, 4, 5].map(n => (
        <button key={n} type="button" disabled={disabled || !onPick} onClick={() => onPick?.(n)} aria-label={`${n} out of 5`}
          style={{
            width: 24, height: 24, borderRadius: 6, fontSize: 12, fontWeight: 700,
            border: '1px solid var(--line2)', cursor: onPick && !disabled ? 'pointer' : 'default',
            background: value !== null && n <= value ? 'var(--brand)' : 'var(--raised)',
            color: value !== null && n <= value ? '#fff' : 'var(--txt-mut)',
          }}>{n}</button>
      ))}
    </span>
  );
}

/** US-A07: pick skills from the managed taxonomy and self-rate 1-5; a manager can rate their reports' skills. */
export default function MySkillsPage() {
  const token = useAuthStore(s => s.token) ?? '';
  const role = useAuthStore(s => s.user?.role);
  const canEndorse = role === 'MANAGER' || role === 'HR_ADMIN' || role === 'SUPER_ADMIN';

  const [taxonomy, setTaxonomy] = useState<Skill[]>([]);
  const [mine, setMine] = useState<SkillRating[]>([]);
  const [team, setTeam] = useState<TeamSkillRating[]>([]);
  const [pick, setPick] = useState('');
  const [pickRating, setPickRating] = useState(3);
  const [error, setError] = useState('');

  const load = useCallback(() => {
    skillsApi.mine(token).then(setMine).catch(e => setError(e instanceof Error ? e.message : 'Failed to load your skills'));
    if (canEndorse) skillsApi.team(token).then(setTeam).catch(() => setTeam([]));
  }, [token, canEndorse]);

  useEffect(() => { load(); skillsApi.taxonomy(token).then(setTaxonomy).catch(() => undefined); }, [token, load]);

  const run = async (fn: () => Promise<unknown>) => {
    setError('');
    try { await fn(); load(); } catch (e) { setError(e instanceof Error ? e.message : 'Something went wrong'); }
  };

  const available = taxonomy.filter(s => !mine.some(m => m.skillId === s.id));

  return (
    <div>
      <PageTitleBlock card icon={Award} style={{ marginBottom: 14 }}>
        <h1 style={{ fontSize: 20, fontWeight: 700, color: 'var(--txt)', margin: 0, fontFamily: 'Inter, sans-serif' }}>My Skills</h1>
        <p style={{ margin: '4px 0 0', color: 'var(--txt-mut)', fontSize: 13 }}>
          Keep your skills up to date so HR and managers can find you for relevant work.
        </p>
      </PageTitleBlock>
      <ErrorBox message={error} />

      {/* Skills & Expertise — the same card used on My Profile → Skills (profile/tabs/SkillsCard),
          now also reachable from here: pick an existing skill or request a brand-new one (HR
          reviews it under Talent & Performance → Settings → Skill Requests), with admin-configured
          proficiency levels and per-request status. Kept on My Profile too — this is the same
          component/data either place, not a copy, so nothing can drift between the two. */}
      <div style={{ marginBottom: 20 }}>
        <SkillsCard token={token} />
      </div>

      {/* The org-wide taxonomy + 1-5 self-rating below is a separate system from the card above —
          it specifically feeds Bench Management's skill search and Internal Opportunities'
          required-skill matching, so it stays as its own section rather than being folded into
          Skills & Expertise above. */}
      <div style={{ marginBottom: 14 }}>
        <SectionHeader title="Organization Skill Ratings" icon={Star} />
        <p style={{ margin: '-10px 0 0', color: 'var(--txt-mut)', fontSize: 12.5 }}>
          Self-rate yourself on skills HR tracks org-wide — used to match you to bench opportunities and skill searches.
        </p>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: '340px minmax(0,1fr)', gap: 16, alignItems: 'start', marginBottom: canEndorse ? 16 : 0 }}>
        <div style={card}>
          <CardTitle>Add a skill</CardTitle>
          <label style={labelStyle}>Skill</label>
          <select value={pick} onChange={e => setPick(e.target.value)} style={{ ...inputStyle, marginBottom: 8 }}>
            <option value="">{taxonomy.length === 0 ? 'No skills available — ask HR to add some' : 'Select a skill'}</option>
            {available.map(s => <option key={s.id} value={s.id}>{s.skill} ({s.category})</option>)}
          </select>
          <label style={labelStyle}>How well do you know it?</label>
          <div style={{ marginBottom: 14 }}><Stars value={pickRating} onPick={setPickRating} /></div>
          <button type="button" style={{ ...primaryBtn(!pick), width: '100%' }} disabled={!pick}
            onClick={() => run(async () => { await skillsApi.rateSelf(token, pick, pickRating); setPick(''); })}>Add skill</button>
        </div>

        <div style={card}>
          <CardTitle>My skills</CardTitle>
          {mine.length === 0 ? <Empty>You haven't listed any skills yet</Empty> : (
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead><tr><th style={th}>Skill</th><th style={th}>My rating</th><th style={th}>Manager rating</th><th style={th} /></tr></thead>
              <tbody>
                {mine.map(m => (
                  <tr key={m.skillId}>
                    <td style={td}>{m.skill} <span style={{ color: 'var(--txt-dim)' }}>· {m.category}</span></td>
                    <td style={td}><Stars value={m.selfRating} onPick={n => run(() => skillsApi.rateSelf(token, m.skillId, n))} /></td>
                    <td style={td}>{m.managerRating !== null ? <Stars value={m.managerRating} /> : <span style={{ color: 'var(--txt-dim)' }}>Not rated</span>}</td>
                    <td style={{ ...td, textAlign: 'right' }}><button type="button" style={ghostBtn} onClick={() => run(() => skillsApi.removeSelf(token, m.skillId))}>Remove</button></td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </div>

      {canEndorse && (
        <div style={card}>
          <CardTitle>My team's skills</CardTitle>
          {team.length === 0 ? <Empty>Your direct reports haven't listed any skills yet</Empty> : (
            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
              <thead><tr><th style={th}>Employee</th><th style={th}>Skill</th><th style={th}>Their rating</th><th style={th}>Your rating</th></tr></thead>
              <tbody>
                {team.map(t => (
                  <tr key={`${t.employeeId}-${t.skillId}`}>
                    <td style={td}>{t.employeeName}</td>
                    <td style={td}>{t.skill}</td>
                    <td style={td}><Stars value={t.selfRating} /></td>
                    <td style={td}><Stars value={t.managerRating} onPick={n => run(() => skillsApi.rateAsManager(token, t.employeeId, t.skillId, n))} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      )}
    </div>
  );
}
