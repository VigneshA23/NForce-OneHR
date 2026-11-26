/**
 * PN-04 - Auto-assignment rules (Organization Masters -> Penalization Policy -> Auto-assignment Rules).
 * HR defines rules (department / location / shift / employment type / grade) in priority order; the
 * first matching rule (top to bottom) assigns its policy. An explicit employee allocation always
 * overrides a rule. Built on the shared rule engine, so the same screen serves leave plans and WFH
 * policies later: only `domain` and the policy list differ. `mock` swaps the API for canned data
 * (design-review preview only).
 */
import { useEffect, useState } from 'react';
import { ArrowDown, ArrowUp, Pencil, Plus, RefreshCw, SlidersHorizontal, Trash2, X } from 'lucide-react';
import { assignmentRulesApi, type AssignmentDomain, type AssignmentRule, type AssignmentRuleInput } from '../../api/assignmentRules';
import { orgApi, type DepartmentRow, type DesignationRow, type LocationRow, type ShiftRow } from '../../api/org';
import { penalisationPoliciesApi, type PenalisationPolicySummary } from '../../api/penalisationPolicies';
import { useToast } from '../../context/ToastContext';
import { useCan } from '../../lib/access';
import '../attendance/myPenalties.css';

const EMPLOYMENT_TYPES = ['FULL_TIME', 'PART_TIME', 'CONTRACT', 'INTERN'];
const typeLabel = (t: string) => t.replace('_', ' ').toLowerCase().replace(/^\w/, c => c.toUpperCase());

export interface AssignmentRulesMock {
  rules: AssignmentRule[];
  policies: { id: string; name: string }[];
  departments: { id: string; name: string }[];
  locations: { id: string; name: string }[];
  shifts: { id: string; name: string }[];
  grades: string[];
}

const panel: React.CSSProperties = { background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10, overflow: 'hidden' };
const th: React.CSSProperties = { padding: '9px 12px', textAlign: 'left', fontSize: 10.5, fontWeight: 700, letterSpacing: '.06em', textTransform: 'uppercase', color: 'var(--txt-dim)', background: 'var(--raised)', borderBottom: '1px solid var(--line)', whiteSpace: 'nowrap' };
const td: React.CSSProperties = { padding: '11px 12px', fontSize: 12.5, color: 'var(--txt-mut)', borderBottom: '1px solid var(--line)', verticalAlign: 'middle' };
const btn = (primary = false): React.CSSProperties => ({
  display: 'inline-flex', alignItems: 'center', gap: 6, padding: '7px 12px', borderRadius: 7, fontSize: 12.5, fontWeight: 600, cursor: 'pointer',
  border: primary ? 'none' : '1px solid var(--line2)', background: primary ? 'var(--brand)' : 'var(--raised)', color: primary ? '#fff' : 'var(--txt-mut)',
});
const iconBtn: React.CSSProperties = { background: 'transparent', border: 'none', cursor: 'pointer', color: 'var(--txt-mut)', padding: 4, borderRadius: 5, display: 'inline-flex' };

export default function AssignmentRulesSection({ token, domain = 'PENALIZATION', mock }: { token: string; domain?: AssignmentDomain; mock?: AssignmentRulesMock }) {
  const { showToast } = useToast();
  const can = useCan();
  const canEdit = mock ? true : can('penalization', 'EDIT');
  const [rules, setRules] = useState<AssignmentRule[]>(mock?.rules ?? []);
  const [policies, setPolicies] = useState<{ id: string; name: string }[]>(mock?.policies ?? []);
  const [departments, setDepartments] = useState<{ id: string; name: string }[]>(mock?.departments ?? []);
  const [locations, setLocations] = useState<{ id: string; name: string }[]>(mock?.locations ?? []);
  const [shifts, setShifts] = useState<{ id: string; name: string }[]>(mock?.shifts ?? []);
  const [grades, setGrades] = useState<string[]>(mock?.grades ?? []);
  const [loading, setLoading] = useState(!mock);
  const [error, setError] = useState('');
  const [editing, setEditing] = useState<AssignmentRule | 'new' | null>(null);
  const [busy, setBusy] = useState(false);

  async function load() {
    if (mock) return;
    setLoading(true); setError('');
    try {
      const [r, pol, dep, loc, sh, des] = await Promise.all([
        assignmentRulesApi.list(domain, token),
        penalisationPoliciesApi.list(token) as Promise<PenalisationPolicySummary[]>,
        orgApi.listDepartments(token) as Promise<DepartmentRow[]>,
        orgApi.listLocations(token) as Promise<LocationRow[]>,
        orgApi.listShifts(token) as Promise<ShiftRow[]>,
        orgApi.listDesignations(token) as Promise<DesignationRow[]>,
      ]);
      setRules(r);
      setPolicies(pol.filter(p => p.status === 'ACTIVE').map(p => ({ id: p.id, name: p.name })));
      setDepartments(dep.filter(d => d.active).map(d => ({ id: d.id, name: d.name })));
      setLocations(loc.filter(l => l.active).map(l => ({ id: l.id, name: l.name })));
      setShifts(sh.map(s => ({ id: s.id, name: s.name })));
      setGrades([...new Set(des.map(d => d.grade).filter((g): g is string => !!g && g.trim() !== ''))].sort());
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not load auto-assignment rules');
    } finally { setLoading(false); }
  }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => { load(); }, [token, domain]);

  async function run(fn: () => Promise<unknown>, okMsg: string) {
    setBusy(true);
    try { await fn(); showToast('success', okMsg); await load(); }
    catch (e) { showToast('error', e instanceof Error ? e.message : 'Action failed'); }
    finally { setBusy(false); }
  }

  function move(i: number, d: -1 | 1) {
    const j = i + d;
    if (j < 0 || j >= rules.length) return;
    const next = [...rules];
    [next[i], next[j]] = [next[j], next[i]];
    if (mock) { setRules(next.map((r, k) => ({ ...r, priority: (k + 1) * 10 }))); return; }
    run(() => assignmentRulesApi.reorder(domain, next.map(r => r.id), token), 'Priority updated - assignments re-evaluated');
  }

  function save(input: AssignmentRuleInput, existing: AssignmentRule | null) {
    if (mock) {
      const name = (id?: string | null, list: { id: string; name: string }[] = []) => list.find(x => x.id === id)?.name ?? null;
      const crit = [input.departmentId && 'Department = ' + name(input.departmentId, departments), input.locationId && 'Location = ' + name(input.locationId, locations),
        input.shiftId && 'Shift = ' + name(input.shiftId, shifts), input.employmentType && 'Employment type = ' + typeLabel(input.employmentType).toLowerCase(), input.grade && 'Grade = ' + input.grade].filter(Boolean).join(' AND ');
      const rule: AssignmentRule = {
        id: existing?.id ?? 'mock-' + Date.now(), domain, targetId: input.targetId, targetName: name(input.targetId, policies), name: input.name,
        priority: existing?.priority ?? (rules.length + 1) * 10, departmentId: input.departmentId ?? null, departmentName: name(input.departmentId, departments),
        locationId: input.locationId ?? null, locationName: name(input.locationId, locations), shiftId: input.shiftId ?? null, shiftName: name(input.shiftId, shifts),
        employmentType: input.employmentType ?? null, grade: input.grade ?? null, active: input.active ?? true, criteriaText: crit, assignedCount: existing?.assignedCount ?? 0, updatedAt: new Date().toISOString(),
      };
      setRules(existing ? rules.map(r => (r.id === existing.id ? rule : r)) : [...rules, rule]);
      setEditing(null); showToast('success', 'Rule saved (preview - nothing was stored)');
      return Promise.resolve();
    }
    return run(async () => { existing ? await assignmentRulesApi.update(existing.id, input, token) : await assignmentRulesApi.create(input, token); setEditing(null); },
      'Rule saved - assignments re-evaluated');
  }

  return (
    <div data-testid="assignment-rules" className="nf-mp" style={{ display: 'flex', flexDirection: 'column', gap: 14, padding: '16px 18px' }}>
      <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 12, flexWrap: 'wrap' }}>
        <div style={{ maxWidth: 640 }}>
          <h2 style={{ margin: 0, fontSize: 14, color: 'var(--txt)' }}>Auto-assignment rules</h2>
          <p style={{ margin: '4px 0 0', fontSize: 12, color: 'var(--txt-mut)', lineHeight: 1.5 }}>
            Assign a policy automatically by department, location, shift, employment type or grade, so new joiners never need allocating by hand.
            Rules are checked <b>top to bottom</b>; the first match wins. An <b>explicit employee allocation always overrides a rule</b>.
            Assignments re-evaluate whenever a rule or an employee's details change, and the effective dates are recorded.
          </p>
        </div>
        {canEdit && (
          <div style={{ display: 'flex', gap: 8 }}>
            <button disabled={busy} onClick={() => mock ? showToast('success', 'Re-evaluated (preview): 0 employees changed') : run(async () => { const r = await assignmentRulesApi.reevaluate(domain, token); showToast('success', r.changed + ' employee assignment(s) changed'); }, 'Re-evaluation complete')} style={btn()}><RefreshCw size={13} /> Re-evaluate all</button>
            <button data-testid="add-rule" onClick={() => setEditing('new')} style={btn(true)}><Plus size={13} /> Add rule</button>
          </div>
        )}
      </div>

      <div style={panel}>
        {loading ? <div style={{ padding: 28, textAlign: 'center', color: 'var(--txt-dim)', fontSize: 12 }}>Loading rules…</div>
          : error ? <div role="alert" style={{ padding: 28, textAlign: 'center', color: 'var(--risk)', fontSize: 12 }}>{error}</div>
          : rules.length === 0 ? <div style={{ padding: '32px 20px', textAlign: 'center', color: 'var(--txt-dim)', fontSize: 12.5 }}>No rules yet. Employees follow their explicit allocation, or the organization default.</div>
          : (
            <div className="nf-req-wrap">
              <table className="nf-req-table" style={{ width: '100%', borderCollapse: 'collapse' }}>
                <thead><tr>{['Order', 'Rule', 'When (all must match)', 'Assign policy', 'Employees', 'Status', ''].map(h => <th key={h} style={{ ...th, background: 'transparent' }}>{h}</th>)}</tr></thead>
                <tbody>
                  {rules.map((r, i) => (
                    <tr key={r.id} data-testid="rule-row" style={{ opacity: r.active ? 1 : 0.55, ['--rc' as string]: r.active ? 'var(--brand)' : 'var(--txt-dim)' }}>
                      <td data-label="Order" style={{ ...td, whiteSpace: 'nowrap' }}>
                        <b style={{ color: 'var(--txt)', marginRight: 6 }}>{i + 1}</b>
                        {canEdit && <>
                          <button aria-label="Move up" disabled={i === 0 || busy} onClick={() => move(i, -1)} style={{ ...iconBtn, opacity: i === 0 ? 0.3 : 1 }}><ArrowUp size={13} /></button>
                          <button aria-label="Move down" disabled={i === rules.length - 1 || busy} onClick={() => move(i, 1)} style={{ ...iconBtn, opacity: i === rules.length - 1 ? 0.3 : 1 }}><ArrowDown size={13} /></button>
                        </>}
                      </td>
                      <td data-label="Rule" style={{ ...td, color: 'var(--txt)', fontWeight: 600 }}>{r.name}</td>
                      <td data-label="When" style={td}>{r.criteriaText}</td>
                      <td data-label="Policy" style={{ ...td, color: 'var(--txt)' }}>{r.targetName ?? '-'}</td>
                      <td data-label="Employees" style={td}>{r.assignedCount}</td>
                      <td data-label="Status" style={td}>{r.active ? 'Active' : 'Inactive'}</td>
                      <td data-label="Action" style={{ ...td, whiteSpace: 'nowrap', textAlign: 'right' }}>
                        {canEdit && <>
                          <button aria-label="Edit rule" onClick={() => setEditing(r)} style={iconBtn}><Pencil size={14} /></button>
                          <button aria-label="Delete rule" onClick={() => {
                            if (mock) { setRules(rules.filter(x => x.id !== r.id)); return; }
                            if (window.confirm('Delete rule "' + r.name + '"? Employees it assigned fall back to the next matching rule, the legacy assignment or the organization default.')) run(() => assignmentRulesApi.remove(r.id, token), 'Rule deleted - assignments re-evaluated');
                          }} style={{ ...iconBtn, color: 'var(--risk)' }}><Trash2 size={14} /></button>
                        </>}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
      </div>

      {editing && (
        <RuleModal
          rule={editing === 'new' ? null : editing} domain={domain} policies={policies} departments={departments} locations={locations} shifts={shifts} grades={grades}
          busy={busy} onClose={() => setEditing(null)} onSave={save}
        />
      )}
    </div>
  );
}

function RuleModal({ rule, domain, policies, departments, locations, shifts, grades, busy, onClose, onSave }: {
  rule: AssignmentRule | null; domain: AssignmentDomain;
  policies: { id: string; name: string }[]; departments: { id: string; name: string }[]; locations: { id: string; name: string }[]; shifts: { id: string; name: string }[]; grades: string[];
  busy: boolean; onClose: () => void; onSave: (input: AssignmentRuleInput, existing: AssignmentRule | null) => void | Promise<unknown>;
}) {
  const [f, setF] = useState<AssignmentRuleInput>({
    domain, name: rule?.name ?? '', targetId: rule?.targetId ?? '', departmentId: rule?.departmentId ?? null, locationId: rule?.locationId ?? null,
    shiftId: rule?.shiftId ?? null, employmentType: rule?.employmentType ?? null, grade: rule?.grade ?? null, active: rule?.active ?? true,
  });
  const hasCriterion = !!(f.departmentId || f.locationId || f.shiftId || f.employmentType || f.grade);
  const valid = f.name.trim() !== '' && f.targetId !== '' && hasCriterion;
  const field: React.CSSProperties = { width: '100%', boxSizing: 'border-box', background: 'var(--raised)', color: 'var(--txt)', border: '1px solid var(--line2)', borderRadius: 9, padding: '9px 11px', fontSize: 13, outline: 'none' };
  const label: React.CSSProperties = { display: 'block', fontSize: 10.5, fontWeight: 700, letterSpacing: '.06em', textTransform: 'uppercase', color: 'var(--txt-dim)', margin: '0 0 6px' };
  const sel = (key: 'departmentId' | 'locationId' | 'shiftId', opts: { id: string; name: string }[]) => (
    <select value={f[key] ?? ''} onChange={e => setF({ ...f, [key]: e.target.value || null })} style={field}>
      <option value="">Any</option>{opts.map(o => <option key={o.id} value={o.id}>{o.name}</option>)}
    </select>
  );
  const nameOf = (list: { id: string; name: string }[], id: string | null | undefined) => list.find(o => o.id === id)?.name;
  const parts = [
    nameOf(departments, f.departmentId) && 'in ' + nameOf(departments, f.departmentId),
    nameOf(locations, f.locationId) && 'at ' + nameOf(locations, f.locationId),
    nameOf(shifts, f.shiftId) && 'on ' + nameOf(shifts, f.shiftId),
    f.employmentType && 'as ' + typeLabel(f.employmentType).toLowerCase(),
    f.grade && 'with grade ' + f.grade,
  ].filter(Boolean) as string[];
  const policyName = nameOf(policies, f.targetId);
  const group = (title: string, hint: string, children: React.ReactNode) => (
    <div style={{ border: '1px solid var(--line)', borderRadius: 12, padding: '14px 16px', background: 'color-mix(in srgb, var(--raised) 55%, var(--panel))' }}>
      <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--txt)' }}>{title}</div>
      <div style={{ fontSize: 11.5, color: 'var(--txt-mut)', margin: '2px 0 12px' }}>{hint}</div>
      {children}
    </div>
  );
  const cell = (l: string, c: React.ReactNode) => <div style={{ minWidth: 0 }}><label style={label}>{l}</label>{c}</div>;
  return (
    <div role="dialog" aria-modal="true" onClick={onClose} style={{ position: 'fixed', inset: 0, zIndex: 1000, background: 'rgba(8,10,20,.55)', backdropFilter: 'blur(3px)', display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 16 }}>
      <div onClick={e => e.stopPropagation()} style={{ width: 'min(600px,100%)', maxHeight: '92vh', display: 'flex', flexDirection: 'column', background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 16, overflow: 'hidden', boxShadow: '0 30px 70px -20px rgba(0,0,0,.55)' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '16px 20px', borderBottom: '1px solid var(--line)', background: 'linear-gradient(120deg, color-mix(in srgb, var(--brand) 16%, var(--panel)), var(--panel) 70%)' }}>
          <div style={{ width: 38, height: 38, borderRadius: 11, display: 'flex', alignItems: 'center', justifyContent: 'center', color: '#fff', background: 'linear-gradient(135deg, var(--brand-bright, var(--brand)), var(--brand))', flexShrink: 0 }}><SlidersHorizontal size={18} /></div>
          <div style={{ flex: 1, minWidth: 0 }}>
            <h3 style={{ margin: 0, fontSize: 16, fontWeight: 700, color: 'var(--txt)' }}>{rule ? 'Edit rule' : 'Add auto-assignment rule'}</h3>
            <div style={{ fontSize: 12, color: 'var(--txt-mut)', marginTop: 2 }}>Employees who match are given this policy automatically.</div>
          </div>
          <button aria-label="Close" onClick={onClose} style={iconBtn}><X size={16} /></button>
        </div>

        <div style={{ padding: '16px 20px', overflowY: 'auto', display: 'flex', flexDirection: 'column', gap: 14 }}>
          {group('Rule', 'Name it and choose the policy it assigns.', (
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: 12 }}>
              {cell('Rule name', <input data-testid="rule-name" value={f.name} onChange={e => setF({ ...f, name: e.target.value })} maxLength={150} placeholder="e.g. Engineering, Bangalore" style={field} />)}
              {cell('Assign this policy', (
                <select data-testid="rule-policy" value={f.targetId} onChange={e => setF({ ...f, targetId: e.target.value })} style={field}>
                  <option value="">Select a policy</option>{policies.map(p => <option key={p.id} value={p.id}>{p.name}</option>)}
                </select>
              ))}
            </div>
          ))}

          {group('Conditions', 'An employee must match all of the conditions you set. Leave the rest on Any.', (
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: 12 }}>
              {cell('Department', sel('departmentId', departments))}
              {cell('Location', sel('locationId', locations))}
              {cell('Shift', sel('shiftId', shifts))}
              {cell('Employment type', (
                <select value={f.employmentType ?? ''} onChange={e => setF({ ...f, employmentType: e.target.value || null })} style={field}>
                  <option value="">Any</option>{EMPLOYMENT_TYPES.map(t => <option key={t} value={t}>{typeLabel(t)}</option>)}
                </select>
              ))}
              {cell('Grade', (
                <select value={f.grade ?? ''} onChange={e => setF({ ...f, grade: e.target.value || null })} style={field}>
                  <option value="">Any</option>{grades.map(g => <option key={g} value={g}>{g}</option>)}
                </select>
              ))}
            </div>
          ))}

          <div data-testid="rule-summary" style={{ borderRadius: 12, padding: '12px 14px', fontSize: 12.5, lineHeight: 1.55, color: 'var(--txt)', background: 'color-mix(in srgb, var(--brand) 10%, var(--panel))', border: '1px solid color-mix(in srgb, var(--brand) 30%, var(--line))' }}>
            {hasCriterion
              ? <>Employees <b>{parts.join(', ')}</b> will get <b>{policyName ?? 'the policy you pick'}</b>.</>
              : <span style={{ color: 'var(--warn)' }}>Pick at least one condition to see who this rule covers.</span>}
          </div>

          <label style={{ display: 'flex', gap: 10, alignItems: 'center', fontSize: 13, color: 'var(--txt)', cursor: 'pointer' }}>
            <input type="checkbox" checked={f.active ?? true} onChange={e => setF({ ...f, active: e.target.checked })} style={{ width: 16, height: 16, accentColor: 'var(--brand)' }} />
            Rule is active
          </label>
        </div>

        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8, padding: '12px 20px', borderTop: '1px solid var(--line)', background: 'var(--panel)' }}>
          <button onClick={onClose} style={btn()}>Cancel</button>
          <button data-testid="rule-save" disabled={!valid || busy} onClick={() => onSave({ ...f, name: f.name.trim() }, rule)} style={{ ...btn(true), opacity: !valid || busy ? 0.55 : 1 }}>Save rule</button>
        </div>
      </div>
    </div>
  );
}
