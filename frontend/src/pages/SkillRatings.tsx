import { useCallback, useEffect, useState } from 'react';
import { skillsApi, type Skill, type SkillRating, type TeamSkillRating } from '../api/skills';
import { card, CardTitle, Empty, ErrorBox, ghostBtn, inputStyle, labelStyle, primaryBtn, td, th } from './bench/ui';

/** 1–5 picker; the selected value is filled, the rest are outlined. */
function RatingPicker({ value, onChange, disabled }: { value: number | null; onChange: (n: number) => void; disabled?: boolean }) {
  return (
    <span role="group" aria-label="Rating 1 to 5" style={{ display: 'inline-flex', gap: 4 }}>
      {[1, 2, 3, 4, 5].map(n => (
        <button key={n} type="button" disabled={disabled} aria-pressed={value === n} onClick={() => onChange(n)}
          style={{ ...ghostBtn, padding: '4px 10px', background: value === n ? 'var(--brand)' : 'var(--raised)', color: value === n ? '#fff' : 'var(--txt)', cursor: disabled ? 'not-allowed' : 'pointer' }}>
          {n}
        </button>
      ))}
    </span>
  );
}

/** US-A07: the employee's own 1–5 ratings, picked from the HR-managed taxonomy (never free text). */
export function SkillRatingsCard({ token }: { token: string }) {
  const [mine, setMine] = useState<SkillRating[]>([]);
  const [taxonomy, setTaxonomy] = useState<Skill[]>([]);
  const [skillId, setSkillId] = useState('');
  const [rating, setRating] = useState(3);
  const [error, setError] = useState('');

  const reload = useCallback(() => {
    skillsApi.mine(token).then(setMine).catch(e => setError(e instanceof Error ? e.message : 'Failed to load ratings'));
  }, [token]);
  useEffect(() => { reload(); skillsApi.taxonomy(token).then(setTaxonomy).catch(() => setTaxonomy([])); }, [token, reload]);

  async function run(action: () => Promise<void>) {
    setError('');
    try { await action(); reload(); } catch (e) { setError(e instanceof Error ? e.message : 'Request failed'); }
  }

  const listed = new Set(mine.map(r => r.skillId));
  const available = taxonomy.filter(s => !listed.has(s.id));

  return (
    <div style={{ ...card, marginTop: 14 }}>
      <CardTitle>Skill ratings</CardTitle>
      <p style={{ margin: '-6px 0 12px', fontSize: 12.5, color: 'var(--txt-mut)' }}>
        Rate yourself 1 (beginner) to 5 (expert) on skills from the company list. Your manager can endorse or adjust the rating.
      </p>
      <ErrorBox message={error} />

      <div style={{ display: 'flex', gap: 10, alignItems: 'flex-end', flexWrap: 'wrap', marginBottom: 14 }}>
        <div style={{ flex: '1 1 220px' }}>
          <label style={labelStyle} htmlFor="skill-pick">Add a skill</label>
          <select id="skill-pick" value={skillId} onChange={e => setSkillId(e.target.value)} style={inputStyle}>
            <option value="">{available.length ? 'Select a skill' : 'No more skills to add'}</option>
            {available.map(s => <option key={s.id} value={s.id}>{s.skill} · {s.category}</option>)}
          </select>
        </div>
        <div>
          <label style={labelStyle}>Your rating</label>
          <RatingPicker value={rating} onChange={setRating} />
        </div>
        <button type="button" style={primaryBtn(!skillId)} disabled={!skillId}
          onClick={() => run(async () => { await skillsApi.rateSelf(token, skillId, rating); setSkillId(''); })}>
          Add
        </button>
      </div>

      {mine.length === 0 ? <Empty>You haven't rated any skills yet</Empty> : (
        <table style={{ width: '100%', borderCollapse: 'collapse' }}>
          <thead><tr><th style={th}>Skill</th><th style={th}>Your rating</th><th style={th}>Manager rating</th><th style={th} /></tr></thead>
          <tbody>
            {mine.map(r => (
              <tr key={r.skillId}>
                <td style={td}>{r.skill} <span style={{ color: 'var(--txt-dim)' }}>· {r.category}</span></td>
                <td style={td}><RatingPicker value={r.selfRating} onChange={n => run(() => skillsApi.rateSelf(token, r.skillId, n))} /></td>
                <td style={{ ...td, color: r.managerRating ? 'var(--txt)' : 'var(--txt-dim)' }}>{r.managerRating ?? 'Not yet endorsed'}</td>
                <td style={td}><button type="button" style={ghostBtn} onClick={() => run(() => skillsApi.removeSelf(token, r.skillId))}>Remove</button></td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}

/** US-A07: a manager's (or HR's) endorsement of their direct reports' skill ratings. */
export function TeamSkillEndorsementsCard({ token }: { token: string }) {
  const [rows, setRows] = useState<TeamSkillRating[] | null>(null);
  const [error, setError] = useState('');

  const reload = useCallback(() => {
    skillsApi.team(token).then(setRows).catch(e => { setRows([]); setError(e instanceof Error ? e.message : 'Failed to load team ratings'); });
  }, [token]);
  useEffect(reload, [reload]);

  async function endorse(r: TeamSkillRating, n: number) {
    setError('');
    try { await skillsApi.rateAsManager(token, r.employeeId, r.skillId, n); reload(); }
    catch (e) { setError(e instanceof Error ? e.message : 'Failed to save rating'); }
  }

  return (
    <div style={{ ...card, marginTop: 14 }}>
      <CardTitle>Team skill endorsements</CardTitle>
      <p style={{ margin: '-6px 0 12px', fontSize: 12.5, color: 'var(--txt-mut)' }}>
        Confirm or override the ratings your direct reports gave themselves.
      </p>
      <ErrorBox message={error} />
      {rows === null ? <Empty>Loading…</Empty> : rows.length === 0 ? <Empty>No skill ratings from your direct reports yet</Empty> : (
        <table style={{ width: '100%', borderCollapse: 'collapse' }}>
          <thead><tr><th style={th}>Employee</th><th style={th}>Skill</th><th style={th}>Self rating</th><th style={th}>Your rating</th></tr></thead>
          <tbody>
            {rows.map(r => (
              <tr key={`${r.employeeId}-${r.skillId}`}>
                <td style={td}>{r.employeeName}</td>
                <td style={td}>{r.skill}</td>
                <td style={td}>{r.selfRating}</td>
                <td style={td}><RatingPicker value={r.managerRating} onChange={n => endorse(r, n)} /></td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
