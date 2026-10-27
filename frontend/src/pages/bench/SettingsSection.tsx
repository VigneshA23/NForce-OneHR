import { useEffect, useState } from 'react';
import { employeesApi, type EmployeeRecord } from '../../api/employees';
import { benchApi, type BandAssignment, type BucketInput, type CompensationBand } from '../../api/bench';
import { skillsApi, type Skill } from '../../api/skills';
import { card, CardTitle, Empty, ErrorBox, ghostBtn, inputStyle, labelStyle, money, primaryBtn, td, th } from './ui';

/** US-A04/US-A05: HR edits the aging buckets and the notification threshold. */
function AgingConfigCard({ token }: { token: string }) {
  const [buckets, setBuckets] = useState<BucketInput[]>([]);
  const [threshold, setThreshold] = useState(30);
  const [error, setError] = useState('');
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    benchApi.agingConfig(token).then(c => { setBuckets(c.buckets); setThreshold(c.notifyThresholdDays); }).catch(() => undefined);
  }, [token]);

  function update(i: number, patch: Partial<BucketInput>) {
    setSaved(false);
    setBuckets(prev => prev.map((b, idx) => (idx === i ? { ...b, ...patch } : b)));
  }

  async function save() {
    setError(''); setSaved(false);
    try {
      const cfg = await benchApi.saveAgingConfig(token, { buckets, notifyThresholdDays: threshold });
      setBuckets(cfg.buckets); setThreshold(cfg.notifyThresholdDays); setSaved(true);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to save');
    }
  }

  return (
    <div style={card}>
      <CardTitle>Aging buckets &amp; notification</CardTitle>
      <table style={{ width: '100%', borderCollapse: 'collapse', marginBottom: 10 }}>
        <thead><tr><th style={th}>Label</th><th style={th}>From day</th><th style={th}>To day</th><th style={th} /></tr></thead>
        <tbody>
          {buckets.map((b, i) => (
            <tr key={i}>
              <td style={td}><input value={b.label} onChange={e => update(i, { label: e.target.value })} style={inputStyle} /></td>
              <td style={{ ...td, width: 90 }}><input type="number" min={0} value={b.minDays} onChange={e => update(i, { minDays: Number(e.target.value) })} style={inputStyle} /></td>
              <td style={{ ...td, width: 110 }}>
                <input type="number" min={0} placeholder="open-ended" value={b.maxDays ?? ''}
                  onChange={e => update(i, { maxDays: e.target.value === '' ? null : Number(e.target.value) })} style={inputStyle} />
              </td>
              <td style={{ ...td, width: 40 }}>
                <button type="button" aria-label={`Remove ${b.label}`} style={ghostBtn} onClick={() => { setSaved(false); setBuckets(prev => prev.filter((_, idx) => idx !== i)); }}>×</button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <button type="button" style={{ ...ghostBtn, marginBottom: 14 }} onClick={() => {
        setSaved(false);
        const last = buckets[buckets.length - 1];
        setBuckets(prev => [...prev.map((b, idx) => (idx === prev.length - 1 && b.maxDays === null ? { ...b, maxDays: b.minDays + 29 } : b)),
          { label: 'New bucket', minDays: last ? (last.maxDays ?? last.minDays + 29) + 1 : 0, maxDays: null }]);
      }}>+ Add bucket</button>

      <label style={labelStyle}>Notify the manager once a report reaches (days on bench)</label>
      <input type="number" min={1} value={threshold} onChange={e => { setThreshold(Number(e.target.value)); setSaved(false); }} style={{ ...inputStyle, width: 120, marginBottom: 14 }} />

      <ErrorBox message={error} />
      <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
        <button type="button" style={primaryBtn()} onClick={save}>Save</button>
        {saved && <span style={{ fontSize: 12, color: 'var(--ok)' }}>Saved</span>}
      </div>
    </div>
  );
}

/** US-A07: HR manages the skill taxonomy every rating, search and opportunity points at. */
function TaxonomyCard({ token }: { token: string }) {
  const [skills, setSkills] = useState<Skill[]>([]);
  const [skill, setSkill] = useState('');
  const [category, setCategory] = useState('');
  const [error, setError] = useState('');

  useEffect(() => { skillsApi.taxonomy(token).then(setSkills).catch(() => undefined); }, [token]);

  async function add() {
    setError('');
    try {
      const created = await skillsApi.addSkill(token, skill.trim(), category.trim());
      setSkills(prev => [...prev, created]);
      setSkill('');
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to add skill');
    }
  }

  return (
    <div style={card}>
      <CardTitle>Skill taxonomy</CardTitle>
      <div style={{ display: 'flex', gap: 8, marginBottom: 10 }}>
        <input placeholder="Skill (e.g. Java)" value={skill} onChange={e => setSkill(e.target.value)} style={inputStyle} />
        <input placeholder="Category (e.g. Engineering)" value={category} onChange={e => setCategory(e.target.value)} style={inputStyle} />
        <button type="button" style={primaryBtn(!skill.trim() || !category.trim())} disabled={!skill.trim() || !category.trim()} onClick={add}>Add</button>
      </div>
      <ErrorBox message={error} />
      {skills.length === 0 ? <Empty>No skills yet</Empty> : (
        <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
          {skills.map(s => (
            <span key={s.id} style={{ fontSize: 12, padding: '3px 10px', borderRadius: 20, background: 'var(--raised)', border: '1px solid var(--line2)', color: 'var(--txt)' }}>
              {s.skill} <span style={{ color: 'var(--txt-dim)' }}>· {s.category}</span>
            </span>
          ))}
        </div>
      )}
    </div>
  );
}

/** US-A13: average-CTC bands and each employee's band label (never an individual salary). */
function BandsCard({ token }: { token: string }) {
  const [bands, setBands] = useState<CompensationBand[]>([]);
  const [assignments, setAssignments] = useState<BandAssignment[]>([]);
  const [employees, setEmployees] = useState<EmployeeRecord[]>([]);
  const [label, setLabel] = useState('');
  const [avgCtc, setAvgCtc] = useState('');
  const [employeeId, setEmployeeId] = useState('');
  const [bandId, setBandId] = useState('');
  const [error, setError] = useState('');

  const reload = () => {
    benchApi.bands(token).then(setBands).catch(() => undefined);
    benchApi.bandAssignments(token).then(setAssignments).catch(() => undefined);
  };
  useEffect(() => {
    reload();
    employeesApi.list(token).then(l => setEmployees(l.filter(e => e.active))).catch(() => undefined);
  }, [token]); // eslint-disable-line react-hooks/exhaustive-deps

  async function addBand() {
    setError('');
    try { await benchApi.addBand(token, label.trim(), Number(avgCtc)); setLabel(''); setAvgCtc(''); reload(); }
    catch (e) { setError(e instanceof Error ? e.message : 'Failed to add band'); }
  }

  async function assign() {
    setError('');
    try { await benchApi.assignBand(token, employeeId, bandId || null); setEmployeeId(''); reload(); }
    catch (e) { setError(e instanceof Error ? e.message : 'Failed to assign band'); }
  }

  return (
    <div style={card}>
      <CardTitle>Compensation bands (for the bench cost view)</CardTitle>
      <div style={{ display: 'flex', gap: 8, marginBottom: 10 }}>
        <input placeholder="Band label (e.g. B2)" value={label} onChange={e => setLabel(e.target.value)} style={inputStyle} />
        <input type="number" min={0} placeholder="Average CTC" value={avgCtc} onChange={e => setAvgCtc(e.target.value)} style={inputStyle} />
        <button type="button" style={primaryBtn(!label.trim() || avgCtc === '')} disabled={!label.trim() || avgCtc === ''} onClick={addBand}>Add band</button>
      </div>
      {bands.length > 0 && (
        <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', marginBottom: 12 }}>
          {bands.map(b => (
            <span key={b.id} style={{ fontSize: 12, padding: '3px 10px', borderRadius: 20, background: 'var(--raised)', border: '1px solid var(--line2)', color: 'var(--txt)' }}>
              {b.bandLabel} <span style={{ color: 'var(--txt-dim)' }}>· {money(b.avgCtc)}</span>
            </span>
          ))}
        </div>
      )}
      <div style={{ display: 'flex', gap: 8, marginBottom: 10 }}>
        <select value={employeeId} onChange={e => setEmployeeId(e.target.value)} style={inputStyle}>
          <option value="">Select an employee</option>
          {employees.map(e => <option key={e.userId} value={e.userId}>{e.fullName} ({e.employeeCode})</option>)}
        </select>
        <select value={bandId} onChange={e => setBandId(e.target.value)} style={inputStyle}>
          <option value="">No band (clear)</option>
          {bands.map(b => <option key={b.id} value={b.id}>{b.bandLabel}</option>)}
        </select>
        <button type="button" style={primaryBtn(!employeeId)} disabled={!employeeId} onClick={assign}>Assign</button>
      </div>
      <ErrorBox message={error} />
      {assignments.length > 0 && (
        <table style={{ width: '100%', borderCollapse: 'collapse' }}>
          <thead><tr><th style={th}>Employee</th><th style={th}>Band</th></tr></thead>
          <tbody>{assignments.map(a => <tr key={a.employeeId}><td style={td}>{a.employeeName}</td><td style={td}>{a.bandLabel}</td></tr>)}</tbody>
        </table>
      )}
    </div>
  );
}

export default function SettingsSection({ token }: { token: string }) {
  return (
    <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0,1fr) minmax(0,1fr)', gap: 16, alignItems: 'start' }}>
      <AgingConfigCard token={token} />
      <div style={{ display: 'grid', gap: 16 }}>
        <TaxonomyCard token={token} />
        <BandsCard token={token} />
      </div>
    </div>
  );
}
