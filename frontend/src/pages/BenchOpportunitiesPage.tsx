import { useCallback, useEffect, useState } from 'react';
import PageTitleBlock from '../components/PageTitleBlock';
import { Briefcase } from 'lucide-react';
import { useAuthStore } from '../store/authStore';
import { hasPermission } from '../lib/permissions';
import { opportunitiesApi, type InterestedEmployee, type Opportunity } from '../api/opportunities';
import { skillsApi, type Skill } from '../api/skills';
import { projectsApi, type ProjectRecord } from '../api/projectAllocations';
import { card, CardTitle, Empty, ErrorBox, ghostBtn, inputStyle, labelStyle, Pill, primaryBtn, todayLocal } from './bench/ui';

function PostForm({ token, skills, onPosted }: { token: string; skills: Skill[]; onPosted: () => void }) {
  const [title, setTitle] = useState('');
  const [weeks, setWeeks] = useState(4);
  const [skillIds, setSkillIds] = useState<string[]>([]);
  const [error, setError] = useState('');

  async function submit() {
    setError('');
    if (!title.trim()) { setError('Give the opportunity a title'); return; }
    if (skillIds.length === 0) { setError('Pick at least one required skill'); return; }
    try {
      await opportunitiesApi.post(token, { title: title.trim(), durationWeeks: weeks, requiredSkillIds: skillIds });
      setTitle(''); setSkillIds([]); onPosted();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to post the opportunity');
    }
  }

  return (
    <div style={{ ...card, marginBottom: 16 }}>
      <CardTitle>Post an internal opportunity</CardTitle>
      <div style={{ display: 'grid', gridTemplateColumns: '2fr 1fr', gap: 12, marginBottom: 10 }}>
        <div><label style={labelStyle}>Role needed</label><input value={title} onChange={e => setTitle(e.target.value)} style={inputStyle} /></div>
        <div><label style={labelStyle}>Duration (weeks)</label><input type="number" min={1} value={weeks} onChange={e => setWeeks(Number(e.target.value))} style={inputStyle} /></div>
      </div>
      <label style={labelStyle}>Required skills</label>
      <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', marginBottom: 12 }}>
        {skills.length === 0 && <span style={{ fontSize: 12, color: 'var(--txt-dim)' }}>The skill taxonomy is empty — HR can add skills under Bench → Settings.</span>}
        {skills.map(s => {
          const on = skillIds.includes(s.id);
          return (
            <button key={s.id} type="button" aria-pressed={on} onClick={() => setSkillIds(p => (on ? p.filter(x => x !== s.id) : [...p, s.id]))}
              style={{ ...ghostBtn, background: on ? 'var(--brand)' : 'var(--raised)', color: on ? '#fff' : 'var(--txt)' }}>{s.skill}</button>
          );
        })}
      </div>
      <ErrorBox message={error} />
      <button type="button" style={primaryBtn()} onClick={submit}>Post opportunity</button>
    </div>
  );
}

function AssignPanel({ token, opp, onDone, onCancel }: { token: string; opp: Opportunity; onDone: () => void; onCancel: () => void }) {
  const [interested, setInterested] = useState<InterestedEmployee[]>([]);
  const [projects, setProjects] = useState<ProjectRecord[]>([]);
  const [employeeId, setEmployeeId] = useState('');
  const [projectId, setProjectId] = useState('');
  const [capacity, setCapacity] = useState(100);
  const [start, setStart] = useState(() => todayLocal());
  const [end, setEnd] = useState('');
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    opportunitiesApi.interests(token, opp.id).then(setInterested).catch(() => setInterested([]));
    projectsApi.list(token).then(p => setProjects(p.filter(x => x.active))).catch(() => setProjects([]));
  }, [token, opp.id]);

  async function submit() {
    setError('');
    if (!employeeId || !projectId || !end) { setError('Choose an employee, a project and an end date'); return; }
    setSaving(true);
    try {
      await opportunitiesApi.close(token, opp.id, { employeeId, projectId, capacityPercent: capacity, startDate: start, endDate: end });
      onDone();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to assign');
    } finally {
      setSaving(false);
    }
  }

  return (
    <div style={{ ...card, marginTop: 10, borderColor: 'var(--brand)' }}>
      <CardTitle>Assign — {opp.title}</CardTitle>
      <div style={{ fontSize: 11.5, color: 'var(--txt-dim)', marginBottom: 10 }}>
        This creates the project allocation, ends the employee's bench status and closes the opportunity together — if any step fails, none of them happen.
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12, marginBottom: 10 }}>
        <div>
          <label style={labelStyle}>Employee (interested)</label>
          <select value={employeeId} onChange={e => setEmployeeId(e.target.value)} style={inputStyle}>
            <option value="">{interested.length ? 'Select an employee' : 'Nobody has expressed interest yet'}</option>
            {interested.map(i => <option key={i.employeeId} value={i.employeeId}>{i.employeeName}</option>)}
          </select>
        </div>
        <div>
          <label style={labelStyle}>Project</label>
          <select value={projectId} onChange={e => setProjectId(e.target.value)} style={inputStyle}>
            <option value="">Select a project</option>
            {projects.map(p => <option key={p.id} value={p.id}>{p.name}</option>)}
          </select>
        </div>
        <div><label style={labelStyle}>Capacity %</label><input type="number" min={1} max={100} value={capacity} onChange={e => setCapacity(Number(e.target.value))} style={inputStyle} /></div>
        <div style={{ display: 'flex', gap: 8 }}>
          <div style={{ flex: 1 }}><label style={labelStyle}>Start</label><input type="date" value={start} onChange={e => setStart(e.target.value)} style={inputStyle} /></div>
          <div style={{ flex: 1 }}><label style={labelStyle}>End</label><input type="date" value={end} onChange={e => setEnd(e.target.value)} style={inputStyle} /></div>
        </div>
      </div>
      <ErrorBox message={error} />
      <div style={{ display: 'flex', gap: 8 }}>
        <button type="button" style={primaryBtn(saving)} disabled={saving} onClick={submit}>{saving ? 'Assigning…' : 'Assign & close'}</button>
        <button type="button" style={ghostBtn} onClick={onCancel}>Cancel</button>
      </div>
    </div>
  );
}

/** US-A10/A11/A12: internal opportunities — post (HR/Manager), express interest (benched), assign & close (HR). */
export default function BenchOpportunitiesPage() {
  const token = useAuthStore(s => s.token) ?? '';
  // V241 — the permissions OpportunityController requires, seeded to the old role sets:
  // CREATE to Manager/HR Admin/Super Admin, APPROVE (assign & close) to HR Admin/Super Admin.
  const permissions = useAuthStore(s => s.permissions);
  const canPost = hasPermission(permissions, 'opportunities', 'CREATE', 'ALL');
  const canAssign = hasPermission(permissions, 'opportunities', 'APPROVE', 'ALL');

  const [opps, setOpps] = useState<Opportunity[] | null>(null);
  const [skills, setSkills] = useState<Skill[]>([]);
  const [error, setError] = useState('');
  const [assigning, setAssigning] = useState<string | null>(null);
  const [expanded, setExpanded] = useState<string | null>(null);
  const [interests, setInterests] = useState<InterestedEmployee[]>([]);

  const load = useCallback(() => {
    opportunitiesApi.list(token).then(setOpps).catch(e => setError(e instanceof Error ? e.message : 'Failed to load opportunities'));
  }, [token]);

  useEffect(() => { load(); skillsApi.taxonomy(token).then(setSkills).catch(() => undefined); }, [token, load]);

  async function interest(id: string) {
    setError('');
    try { await opportunitiesApi.expressInterest(token, id); load(); }
    catch (e) { setError(e instanceof Error ? e.message : 'Failed to express interest'); }
  }

  async function toggleInterests(id: string) {
    if (expanded === id) { setExpanded(null); return; }
    setExpanded(id);
    setInterests(await opportunitiesApi.interests(token, id).catch(() => []));
  }

  return (
    <div>
      <PageTitleBlock card icon={Briefcase} style={{ marginBottom: 14 }}>
        <h1 style={{ fontSize: 20, fontWeight: 700, color: 'var(--txt)', margin: 0, fontFamily: 'Inter, sans-serif' }}>Internal Opportunities</h1>
        <p style={{ margin: '4px 0 0', color: 'var(--txt-mut)', fontSize: 13 }}>
          Short-term internal roles, shown only to employees currently on bench.
        </p>
      </PageTitleBlock>

      {canPost && <PostForm token={token} skills={skills} onPosted={load} />}
      <ErrorBox message={error} />

      {!opps ? <Empty>Loading…</Empty> : opps.length === 0 ? (
        <div style={card}><Empty>{canPost ? 'No opportunities posted yet' : 'There are no open opportunities for you right now. Opportunities are shown to employees who are on bench.'}</Empty></div>
      ) : (
        <div style={{ display: 'grid', gap: 12 }}>
          {opps.map(o => (
            <div key={o.id} style={card}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 12 }}>
                <div>
                  <div style={{ fontSize: 14, fontWeight: 700, color: 'var(--txt)' }}>{o.title}</div>
                  <div style={{ fontSize: 12, color: 'var(--txt-mut)', margin: '3px 0 8px' }}>
                    {o.durationWeeks} week{o.durationWeeks === 1 ? '' : 's'} · posted by {o.postedBy ?? 'unknown'}
                    {o.assignedEmployee ? ` · assigned to ${o.assignedEmployee}` : ''}
                  </div>
                  <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                    {o.requiredSkills.map(s => <Pill key={s} tone="muted">{s}</Pill>)}
                  </div>
                </div>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                  <Pill tone={o.status === 'OPEN' ? 'ok' : 'muted'}>{o.status}</Pill>
                  {canPost && <button type="button" style={ghostBtn} onClick={() => toggleInterests(o.id)}>{o.interestCount} interested</button>}
                  {!canPost && o.status === 'OPEN' && (
                    <button type="button" style={primaryBtn(o.interested)} disabled={o.interested} onClick={() => interest(o.id)}>
                      {o.interested ? 'Interest sent' : "I'm interested"}
                    </button>
                  )}
                  {canAssign && o.status === 'OPEN' && <button type="button" style={primaryBtn()} onClick={() => setAssigning(o.id)}>Assign…</button>}
                </div>
              </div>
              {expanded === o.id && (
                <div style={{ marginTop: 10, fontSize: 12.5, color: 'var(--txt)' }}>
                  {interests.length === 0 ? <span style={{ color: 'var(--txt-dim)' }}>Nobody has expressed interest yet.</span>
                    : interests.map(i => <div key={i.employeeId}>{i.employeeName}</div>)}
                </div>
              )}
              {assigning === o.id && <AssignPanel token={token} opp={o} onCancel={() => setAssigning(null)} onDone={() => { setAssigning(null); load(); }} />}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
