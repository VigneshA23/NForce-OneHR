import { useCallback, useEffect, useState } from 'react';
import * as XLSX from 'xlsx';
import { benchApi, BENCH_REASON_LABELS, CLOSE_REASON_LABELS, type AgingConfig, type AllocationOption, type BenchCloseReason, type BenchListRow } from '../../api/bench';
import { skillsApi, type Skill } from '../../api/skills';
import { toExportRows } from './benchExport';
import { card, CardTitle, Empty, ErrorBox, ghostBtn, inputStyle, labelStyle, Pill, primaryBtn, td, th, todayLocal } from './ui';

function EndBenchPanel({ token, row, onDone, onCancel }: { token: string; row: BenchListRow; onDone: () => void; onCancel: () => void }) {
  const [allocations, setAllocations] = useState<AllocationOption[]>([]);
  const [allocationId, setAllocationId] = useState('');
  const [closeReason, setCloseReason] = useState<BenchCloseReason | ''>('');
  const [note, setNote] = useState('');
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    benchApi.allocationOptions(token, row.id).then(setAllocations).catch(() => setAllocations([]));
  }, [token, row.id]);

  async function submit() {
    setError('');
    if (!allocationId && !closeReason) { setError('Link an allocation, or choose a reason for closing without one'); return; }
    setSaving(true);
    try {
      await benchApi.end(token, row.id, allocationId
        ? { allocationId, closeNote: note || undefined }
        : { closeReason: closeReason as BenchCloseReason, closeNote: note || undefined });
      onDone();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to end bench');
    } finally {
      setSaving(false);
    }
  }

  return (
    <div style={{ ...card, marginBottom: 12, borderColor: 'var(--brand)' }}>
      <CardTitle>End bench — {row.employeeName}</CardTitle>
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 12 }}>
        <div>
          <label style={labelStyle}>Link an allocation</label>
          <select value={allocationId} onChange={e => { setAllocationId(e.target.value); if (e.target.value) setCloseReason(''); }} style={inputStyle}>
            <option value="">None</option>
            {allocations.map(a => (
              <option key={a.id} value={a.id}>{a.projectName} · {a.capacityPercent}% · {a.startDate} → {a.endDate}</option>
            ))}
          </select>
        </div>
        <div>
          <label style={labelStyle}>…or close without an allocation</label>
          <select value={closeReason} disabled={!!allocationId} onChange={e => setCloseReason(e.target.value as BenchCloseReason)} style={inputStyle}>
            <option value="">Select a reason</option>
            {(Object.keys(CLOSE_REASON_LABELS) as BenchCloseReason[]).map(r => <option key={r} value={r}>{CLOSE_REASON_LABELS[r]}</option>)}
          </select>
        </div>
        <div>
          <label style={labelStyle}>Note (optional)</label>
          <input value={note} onChange={e => setNote(e.target.value)} maxLength={500} style={inputStyle} />
        </div>
      </div>
      <div style={{ marginTop: 12 }}><ErrorBox message={error} /></div>
      <div style={{ display: 'flex', gap: 8 }}>
        <button type="button" onClick={submit} disabled={saving} style={primaryBtn(saving)}>{saving ? 'Ending…' : 'End bench'}</button>
        <button type="button" onClick={onCancel} style={ghostBtn}>Cancel</button>
      </div>
    </div>
  );
}

/** US-A04 aging filter + US-A08 skill search + US-A09 export of exactly what is on screen + US-A06 end. */
export default function BenchListSection({ token }: { token: string }) {
  const [config, setConfig] = useState<AgingConfig | null>(null);
  const [taxonomy, setTaxonomy] = useState<Skill[]>([]);
  const [bucket, setBucket] = useState('');
  const [skillIds, setSkillIds] = useState<string[]>([]);
  const [mode, setMode] = useState<'AND' | 'OR'>('OR');
  const [rows, setRows] = useState<BenchListRow[] | null>(null);
  const [error, setError] = useState('');
  const [ending, setEnding] = useState<BenchListRow | null>(null);

  useEffect(() => {
    benchApi.agingConfig(token).then(setConfig).catch(() => setConfig(null));
    skillsApi.taxonomy(token).then(setTaxonomy).catch(() => setTaxonomy([]));
  }, [token]);

  const load = useCallback(() => {
    benchApi.list(token, { bucket: bucket || undefined, skillIds, mode })
      .then(r => { setRows(r); setError(''); })
      .catch(e => setError(e instanceof Error ? e.message : 'Failed to load the bench list'));
  }, [token, bucket, skillIds, mode]);

  useEffect(load, [load]);

  function toggleSkill(id: string) {
    setSkillIds(prev => (prev.includes(id) ? prev.filter(s => s !== id) : [...prev, id]));
  }

  function exportXlsx() {
    const sheet = toExportRows(rows ?? [], skillIds.length);
    const wb = XLSX.utils.book_new();
    XLSX.utils.book_append_sheet(wb, XLSX.utils.json_to_sheet(sheet), 'Bench');
    XLSX.writeFile(wb, `bench-list-${todayLocal()}.xlsx`);
  }

  return (
    <div>
      <div style={{ ...card, marginBottom: 12 }}>
        <div style={{ display: 'flex', gap: 16, alignItems: 'flex-end', flexWrap: 'wrap' }}>
          <div style={{ width: 200 }}>
            <label style={labelStyle}>Aging bucket</label>
            <select value={bucket} onChange={e => setBucket(e.target.value)} style={inputStyle}>
              <option value="">All buckets</option>
              {(config?.buckets ?? []).map(b => <option key={b.label} value={b.label}>{b.label}</option>)}
            </select>
          </div>
          <div style={{ flex: 1, minWidth: 260 }}>
            <label style={labelStyle}>Skills ({skillIds.length} selected)</label>
            <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
              {taxonomy.length === 0 && <span style={{ fontSize: 12, color: 'var(--txt-dim)' }}>No skills in the taxonomy yet — add some in Settings.</span>}
              {taxonomy.map(s => (
                <button key={s.id} type="button" onClick={() => toggleSkill(s.id)} aria-pressed={skillIds.includes(s.id)}
                  style={{ ...ghostBtn, background: skillIds.includes(s.id) ? 'var(--brand)' : 'var(--raised)', color: skillIds.includes(s.id) ? '#fff' : 'var(--txt)' }}>
                  {s.skill}
                </button>
              ))}
            </div>
          </div>
          <div style={{ width: 170 }}>
            <label style={labelStyle}>Match</label>
            <select value={mode} onChange={e => setMode(e.target.value as 'AND' | 'OR')} style={inputStyle}>
              <option value="OR">Any selected skill (OR)</option>
              <option value="AND">All selected skills (AND)</option>
            </select>
          </div>
          <button type="button" onClick={exportXlsx} disabled={!rows?.length} style={{ ...primaryBtn(!rows?.length) }}>Export Excel</button>
        </div>
      </div>

      {ending && <EndBenchPanel token={token} row={ending} onCancel={() => setEnding(null)} onDone={() => { setEnding(null); load(); }} />}

      <div style={card}>
        <CardTitle>Employees on bench {rows ? `(${rows.length})` : ''}</CardTitle>
        <ErrorBox message={error} />
        {!rows ? <Empty>Loading…</Empty> : rows.length === 0 ? <Empty>No benched employees match these filters</Empty> : (
          <table style={{ width: '100%', borderCollapse: 'collapse' }}>
            <thead>
              <tr>
                <th style={th}>Employee</th><th style={th}>Reason</th><th style={th}>Days</th><th style={th}>Bucket</th>
                {skillIds.length > 0 && <th style={th}>Match</th>}<th style={th}>Skills</th><th style={th} />
              </tr>
            </thead>
            <tbody>
              {rows.map(r => (
                <tr key={r.id}>
                  <td style={td}>{r.employeeName}</td>
                  <td style={td}>{BENCH_REASON_LABELS[r.reasonCode]}</td>
                  <td style={td}>{r.daysOnBench}</td>
                  <td style={td}>{r.agingBucket ? <Pill tone="warn">{r.agingBucket}</Pill> : '—'}</td>
                  {skillIds.length > 0 && <td style={td}>{r.matchScore}/{skillIds.length}</td>}
                  <td style={{ ...td, color: r.skills.length ? 'var(--txt)' : 'var(--txt-dim)' }}>{r.skills.join(', ') || '—'}</td>
                  <td style={{ ...td, textAlign: 'right' }}><button type="button" style={ghostBtn} onClick={() => setEnding(r)}>End bench</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}
