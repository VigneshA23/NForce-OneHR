import { useEffect, useMemo, useState } from 'react';
import { Search, Users } from 'lucide-react';
import { useAuthStore } from '../store/authStore';
import { employeesApi, type EmployeeRecord } from '../api/employees';
import { projectsApi, projectAllocationsApi, type ProjectRecord, type AllocationResult } from '../api/projectAllocations';

const card: React.CSSProperties = { background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 10 };
const inputStyle: React.CSSProperties = {
  background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6,
  padding: '7px 10px', fontSize: 12.5, color: 'var(--txt)', outline: 'none', fontFamily: 'inherit',
};
const labelStyle: React.CSSProperties = { fontSize: 11, fontWeight: 600, color: 'var(--txt-mut)', display: 'block', marginBottom: 5 };

function ResultsTable({ results, employees }: { results: AllocationResult[]; employees: EmployeeRecord[] }) {
  const nameFor = (id: string) => employees.find(e => e.userId === id)?.fullName ?? id;
  const successCount = results.filter(r => r.status === 'SUCCESS').length;
  return (
    <div style={{ ...card, padding: '14px 16px', marginTop: 16 }}>
      <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--txt)', marginBottom: 10 }}>
        {successCount} of {results.length} allocated successfully
      </div>
      <table style={{ width: '100%', borderCollapse: 'collapse' }}>
        <thead>
          <tr>
            <th style={{ textAlign: 'left', padding: '6px 8px', fontSize: 11, color: 'var(--txt-dim)', textTransform: 'uppercase', borderBottom: '1px solid var(--line)' }}>Employee</th>
            <th style={{ textAlign: 'left', padding: '6px 8px', fontSize: 11, color: 'var(--txt-dim)', textTransform: 'uppercase', borderBottom: '1px solid var(--line)' }}>Status</th>
            <th style={{ textAlign: 'left', padding: '6px 8px', fontSize: 11, color: 'var(--txt-dim)', textTransform: 'uppercase', borderBottom: '1px solid var(--line)' }}>Reason</th>
          </tr>
        </thead>
        <tbody>
          {results.map(r => (
            <tr key={r.employeeId}>
              <td style={{ padding: '7px 8px', fontSize: 12.5, color: 'var(--txt)', borderBottom: '1px solid var(--line)' }}>{nameFor(r.employeeId)}</td>
              <td style={{ padding: '7px 8px', borderBottom: '1px solid var(--line)' }}>
                <span style={{
                  fontSize: 10.5, fontWeight: 700, padding: '2px 8px', borderRadius: 20,
                  background: r.status === 'SUCCESS' ? 'rgba(47,182,124,.15)' : 'rgba(228,55,61,.12)',
                  color: r.status === 'SUCCESS' ? 'var(--ok)' : 'var(--risk)',
                }}>{r.status}</span>
              </td>
              <td style={{ padding: '7px 8px', fontSize: 12.5, color: 'var(--txt-mut)', borderBottom: '1px solid var(--line)' }}>{r.reason ?? '—'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export default function ProjectAllocationsPage() {
  const token = useAuthStore(s => s.token) ?? '';

  const [employees, setEmployees] = useState<EmployeeRecord[]>([]);
  const [projects, setProjects] = useState<ProjectRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');

  const [employeeSearch, setEmployeeSearch] = useState('');
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [projectId, setProjectId] = useState('');
  const [newProjectName, setNewProjectName] = useState('');
  const [capacityPercent, setCapacityPercent] = useState(100);
  const [startDate, setStartDate] = useState('');
  const [endDate, setEndDate] = useState('');

  const [submitting, setSubmitting] = useState(false);
  const [formError, setFormError] = useState('');
  const [results, setResults] = useState<AllocationResult[] | null>(null);

  function load() {
    setLoading(true);
    setLoadError('');
    Promise.all([employeesApi.list(token), projectsApi.list(token)])
      .then(([emps, projs]) => {
        setEmployees(emps.filter(e => e.active));
        setProjects(projs);
      })
      .catch(e => setLoadError(e instanceof Error ? e.message : 'Failed to load employees/projects'))
      .finally(() => setLoading(false));
  }

  useEffect(load, [token]);

  const filteredEmployees = useMemo(() => {
    const q = employeeSearch.trim().toLowerCase();
    if (!q) return employees;
    return employees.filter(e => e.fullName.toLowerCase().includes(q) || e.employeeCode.toLowerCase().includes(q));
  }, [employees, employeeSearch]);

  function toggle(id: string) {
    setSelected(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  }

  async function handleCreateProject() {
    const name = newProjectName.trim();
    if (!name) return;
    try {
      const created = await projectsApi.create(token, name);
      setProjects(prev => [...prev, created]);
      setProjectId(created.id);
      setNewProjectName('');
    } catch (e) {
      setFormError(e instanceof Error ? e.message : 'Failed to create project');
    }
  }

  async function handleSubmit() {
    setFormError('');
    if (selected.size === 0) { setFormError('Select at least one employee'); return; }
    if (!projectId) { setFormError('Select a project'); return; }
    if (!startDate || !endDate) { setFormError('Start and end dates are required'); return; }

    setSubmitting(true);
    setResults(null);
    try {
      const res = await projectAllocationsApi.bulkAllocate(token, {
        employeeIds: Array.from(selected),
        projectId,
        capacityPercent,
        startDate,
        endDate,
      });
      setResults(res.results);
    } catch (e) {
      setFormError(e instanceof Error ? e.message : 'Bulk allocation failed');
    } finally {
      setSubmitting(false);
    }
  }

  if (loading) return <div style={{ padding: 24, color: 'var(--txt-mut)' }}>Loading…</div>;
  if (loadError) return <div style={{ padding: 24, color: 'var(--risk)' }}>{loadError}</div>;

  return (
    <div>
      <div style={{ marginBottom: 18 }}>
        <h1 style={{ fontSize: 20, fontWeight: 700, color: 'var(--txt)', margin: 0, fontFamily: 'Inter, sans-serif' }}>Project Allocations</h1>
        <p style={{ margin: '4px 0 0', color: 'var(--txt-mut)', fontSize: 13 }}>
          Allocate multiple employees to a project in one go — each is validated independently.
        </p>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 320px', gap: 16, alignItems: 'start' }}>
        {/* Employee multi-select */}
        <div style={{ ...card, padding: '14px 16px' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 10 }}>
            <Users size={14} style={{ color: 'var(--brand)' }} />
            <span style={{ fontSize: 13, fontWeight: 700, color: 'var(--txt)' }}>Select Employees</span>
            <span style={{ fontSize: 12, color: 'var(--txt-mut)' }}>({selected.size} selected)</span>
          </div>
          <div style={{ position: 'relative', marginBottom: 10 }}>
            <Search size={13} style={{ position: 'absolute', left: 9, top: 9, color: 'var(--txt-dim)' }} />
            <input
              placeholder="Search by name or employee code"
              value={employeeSearch}
              onChange={e => setEmployeeSearch(e.target.value)}
              style={{ ...inputStyle, width: '100%', paddingLeft: 28, boxSizing: 'border-box' }}
            />
          </div>
          <div style={{ maxHeight: 360, overflowY: 'auto', border: '1px solid var(--line)', borderRadius: 6 }}>
            {filteredEmployees.map(e => (
              <label key={e.userId} style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '8px 10px', borderBottom: '1px solid var(--line)', cursor: 'pointer' }}>
                <input type="checkbox" checked={selected.has(e.userId)} onChange={() => toggle(e.userId)} style={{ width: 14, height: 14 }} />
                <span style={{ fontSize: 12.5, color: 'var(--txt)' }}>{e.fullName}</span>
                <span style={{ fontSize: 11, color: 'var(--txt-dim)' }}>{e.employeeCode}</span>
              </label>
            ))}
            {filteredEmployees.length === 0 && (
              <div style={{ padding: 16, fontSize: 12.5, color: 'var(--txt-dim)', textAlign: 'center' }}>No employees match</div>
            )}
          </div>
        </div>

        {/* Allocation details */}
        <div style={{ ...card, padding: '14px 16px' }}>
          <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--txt)', marginBottom: 10 }}>Allocation Details</div>

          <label style={labelStyle}>Project</label>
          <select value={projectId} onChange={e => setProjectId(e.target.value)} style={{ ...inputStyle, width: '100%', marginBottom: 8, boxSizing: 'border-box' }}>
            <option value="">Select a project</option>
            {projects.map(p => <option key={p.id} value={p.id}>{p.name}</option>)}
          </select>
          <div style={{ display: 'flex', gap: 6, marginBottom: 14 }}>
            <input placeholder="New project name" value={newProjectName} onChange={e => setNewProjectName(e.target.value)}
              style={{ ...inputStyle, flex: 1, boxSizing: 'border-box' }} />
            <button type="button" onClick={handleCreateProject} disabled={!newProjectName.trim()}
              style={{ padding: '7px 12px', background: 'var(--raised)', border: '1px solid var(--line2)', borderRadius: 6, fontSize: 12, color: 'var(--txt)', cursor: newProjectName.trim() ? 'pointer' : 'not-allowed' }}>
              Add
            </button>
          </div>

          <label style={labelStyle}>Capacity %</label>
          <input type="number" min={1} max={100} value={capacityPercent}
            onChange={e => setCapacityPercent(Number(e.target.value))}
            style={{ ...inputStyle, width: '100%', marginBottom: 8, boxSizing: 'border-box' }} />

          <label style={labelStyle}>Start Date</label>
          <input type="date" value={startDate} onChange={e => setStartDate(e.target.value)}
            style={{ ...inputStyle, width: '100%', marginBottom: 8, boxSizing: 'border-box' }} />

          <label style={labelStyle}>End Date</label>
          <input type="date" value={endDate} onChange={e => setEndDate(e.target.value)}
            style={{ ...inputStyle, width: '100%', marginBottom: 14, boxSizing: 'border-box' }} />

          {formError && (
            <div role="alert" style={{ background: 'rgba(228,55,61,.1)', border: '1px solid rgba(228,55,61,.3)', borderRadius: 6, padding: '8px 10px', marginBottom: 12, color: 'var(--risk)', fontSize: 12 }}>
              {formError}
            </div>
          )}

          <button type="button" onClick={handleSubmit} disabled={submitting}
            style={{ width: '100%', padding: '9px 14px', background: 'var(--brand)', border: 'none', borderRadius: 6, fontSize: 13, fontWeight: 600, color: '#fff', cursor: submitting ? 'not-allowed' : 'pointer', opacity: submitting ? 0.7 : 1 }}>
            {submitting ? 'Allocating…' : `Allocate ${selected.size || ''} Employee${selected.size === 1 ? '' : 's'}`}
          </button>
        </div>
      </div>

      {results && <ResultsTable results={results} employees={employees} />}
    </div>
  );
}
